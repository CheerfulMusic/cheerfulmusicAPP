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
    return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', default='/opt/qingfeng-music/current')
    parser.add_argument('--policy', default='/etc/qingfeng-music/release-policy.json')
    args = parser.parse_args()
    print('RELEASE_GUARD_OK ' + verify(args.release, args.policy))
