#!/usr/bin/env python3
"""Fail closed before service start; policy is root-owned and outside releases."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


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
    # Temporary approval for automatic rollback during this deployment only.
    if approval.get('legacyRollback'):
        return digest
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
        if not referenced_audio or referenced_audio != declared_audio:
            raise ValueError('Preview audio references do not match verified assets')
    return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', default='/opt/qingfeng-music/current')
    parser.add_argument('--policy', default='/etc/qingfeng-music/release-policy.json')
    args = parser.parse_args()
    print('RELEASE_GUARD_OK ' + verify(args.release, args.policy))
