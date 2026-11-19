#!/usr/bin/env python3
"""Fail closed before service start; policy is root-owned and outside releases."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import zipfile



def gate_module():
    # Installed beside this guard on the new server, or loaded from this source tree.
    path = Path(__file__).with_name("release_gate.py")
    if not path.is_file():
        path = Path(__file__).resolve().parents[2] / "scripts" / "release_gate.py"
    spec = importlib.util.spec_from_file_location("release_gate", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def cdn_preview_audio(archive, manifest):
    remote = manifest.get('remoteAssets', [])
    if not isinstance(remote, list):
        raise ValueError('Invalid CDN preview manifest')
    if not remote:
        return set()
    static = 'BOOT-INF/classes/static/'
    catalog = json.loads(archive.read(static + 'recommendation-assets.json'))
    if catalog.get('version') != 1 or not isinstance(catalog.get('assets'), list):
        raise ValueError('Invalid CDN preview catalog')
    routes = archive.read('BOOT-INF/classes/com/qingfeng/music/server/hotsong/RecommendationSamples.class')
    by_path = {item['path']: item for item in catalog['assets']}
    if len(by_path) != len(catalog['assets']):
        raise ValueError('Duplicate CDN preview catalog path')
    paths = set()
    audio = set()
    for asset in remote:
        path, key, size, digest = (asset.get(name) for name in ('path', 'key', 'bytes', 'sha256'))
        match = re.fullmatch(r'/media/(sample-([a-z0-9-]+)-v1(-preview-128[.]mp3|-cover[.]jpg))', path or '')
        if not match or path in paths or type(size) is not int or size <= 0 or not re.fullmatch('[0-9a-f]{64}', digest or ''):
            raise ValueError('Invalid CDN preview asset')
        filename, track_id, suffix = match.groups()
        kind = 'audio' if suffix.endswith('.mp3') else 'cover'
        key_pattern = r'qingfeng/recommendation-samples/[0-9]{8}/' + kind + '/' + digest[:12] + '/' + re.escape(filename)
        if not re.fullmatch(key_pattern, key or '') or by_path.get(path) != {
            'trackId': track_id, 'kind': kind, 'path': path, 'key': key, 'bytes': size, 'sha256': digest,
        } or key.encode() not in routes:
            raise ValueError('CDN preview asset does not match catalog and server route')
        paths.add(path)
        if kind == 'audio':
            audio.add(path)
    return audio


def verify(release, policy):
    release = Path(release).resolve()
    policy = json.loads(Path(policy).read_text(encoding='utf-8'))
    jar = release / 'app.jar'
    digest_builder = hashlib.sha256()
    with jar.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest_builder.update(chunk)
    digest = digest_builder.hexdigest()
    approval = policy['approved'].get(digest)
    if not approval:
        raise ValueError('Unapproved artifact: refusing to start an older or unverified release')
    source = (release / 'source-commit.txt').read_text().strip()
    if source != approval['sourceCommit'] or not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Source manifest does not match approved artifact')
    # Every entry point uses the same artifact contract; no legacy bypass.
    gate = gate_module()
    gate.verify_contract(jar, release / 'release-contract.json',
                         approval.get('contractSha256', ''), source)
    if not re.fullmatch('[0-9a-f]{64}', approval.get('schemaEvidenceSha256', '')):
        raise ValueError('Schema compatibility evidence missing; refusing to start')
    if approval.get('legacyRollback'):
        raise ValueError('Legacy rollback bypass disabled; approve a verified compatible contract')
    with zipfile.ZipFile(jar) as z:
        if z.read('BOOT-INF/classes/release-commit.txt').decode().strip() != source:
            raise ValueError('Embedded build source does not match release manifest')
        names = z.namelist()
        index = z.read('BOOT-INF/classes/static/index.html').decode()
        entry = re.search(r'<script[^>]+src="([^"]+\.js)"', index).group(1)
        entry_js = z.read('BOOT-INF/classes/static/' + entry.lstrip('/')).decode()
        for page in ('IdentityPage', 'RechargePage', 'AnalyticsPage'):
            match = re.search(page + r'-[A-Za-z0-9_-]+\.js', entry_js)
            if not match:
                raise ValueError('Missing reachable page: ' + page)
            body = z.read('BOOT-INF/classes/static/assets/' + match.group()).decode()
            required = {'IdentityPage': ('cancel-order', 'payment-orders'), 'RechargePage': ('/account#payment-orders',), 'AnalyticsPage': ()}[page]
            if any(marker not in body for marker in required):
                raise ValueError('Missing order actions in ' + page)
        for cls in ('web/app/AnalyticsViewController', 'web/publicapi/SiteEventController', 'payment/PayPalPaymentController'):
            path = 'BOOT-INF/classes/com/qingfeng/music/server/' + cls + '.class'
            if path not in names:
                raise ValueError('Missing server feature: ' + cls)
        if b'/orders/{id}/close' not in z.read('BOOT-INF/classes/com/qingfeng/music/server/payment/PayPalPaymentController.class'):
            raise ValueError('Missing server cancellation endpoint')
        manifest_path = 'BOOT-INF/classes/static/preview-media-manifest.json'
        if manifest_path not in names:
            raise ValueError('Missing preview media manifest')
        manifest = json.loads(z.read(manifest_path))
        if manifest.get('version') != 1 or not manifest.get('assets'):
            raise ValueError('Invalid preview media manifest')
        declared_audio = set()
        for asset in manifest['assets']:
            path = asset['path']
            if not path.startswith('/media/') or '..' in path.split('/'):
                raise ValueError('Invalid preview asset path')
            entry = 'BOOT-INF/classes/static' + path
            if entry not in names:
                raise ValueError('Missing preview asset: ' + path)
            data = z.read(entry)
            if not data or len(data) != asset['bytes'] or hashlib.sha256(data).hexdigest() != asset['sha256']:
                raise ValueError('Preview asset checksum mismatch: ' + path)
            if path.endswith('.mp3'):
                declared_audio.add(path)
        referenced_audio = set()
        for name in names:
            if name.startswith('BOOT-INF/classes/static/assets/') and name.endswith('.js'):
                referenced_audio.update(re.findall(r'''["'`](/media/[^"'`\\\s]+\.mp3)["'`]''', z.read(name).decode()))
        cdn_audio = cdn_preview_audio(z, manifest)
        if not referenced_audio or declared_audio & cdn_audio or referenced_audio != declared_audio | cdn_audio:
            raise ValueError('Preview audio references do not match verified assets')
    return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', default='/opt/qingfeng-music/current')
    parser.add_argument('--policy', default='/etc/qingfeng-music/release-policy.json')
    args = parser.parse_args()
    print('RELEASE_GUARD_OK ' + verify(args.release, args.policy))
