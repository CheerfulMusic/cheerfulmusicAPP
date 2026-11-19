import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('verify-release.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class GuardTest(unittest.TestCase):
    def fixture(self, missing=False, missing_audio=False, cdn=False, mismatched_cdn=False, remote_only=False):
        base = Path(__file__).resolve().parents[2] / '.cache/guard-test-tmp'
        base.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        source = 'a' * 40
        (root / 'source-commit.txt').write_text(source)
        prefix = 'BOOT-INF/classes/'
        with zipfile.ZipFile(root / 'app.jar', 'w') as z:
            z.writestr('META-INF/MANIFEST.MF', 'Main-Class: org.springframework.boot.loader.launch.JarLauncher\n')
            z.writestr(prefix + 'release-commit.txt', source)
            z.writestr(prefix + 'application.yml', 'spring: {}')
            z.writestr(prefix + 'application-mysql.yml', 'spring: {}')
            for migration in sorted((Path(__file__).resolve().parents[1] / 'qf-infrastructure/src/main/resources/db/migration').glob('V*__*.sql')):
                z.writestr(prefix + 'db/migration/' + migration.name, migration.read_bytes())
            z.writestr(prefix + 'static/index.html', '<script src="/assets/index-abc.js"></script>')
            entry = 'IdentityPage-abc.js RechargePage-abc.js AnalyticsPage-abc.js'
            if not remote_only:
                entry += ' `/media/sample.mp3`'
            audio = b'ID3original-audio'
            manifest = {'version': 1, 'assets': [] if remote_only else [
                {'path': '/media/sample.mp3', 'bytes': len(audio), 'sha256': hashlib.sha256(audio).hexdigest()}]}
            if cdn:
                filename = 'sample-cdn-song-v1-preview-128.mp3'
                path, sha = '/media/' + filename, 'f'*64
                key = 'qingfeng/recommendation-samples/20261118/audio/' + sha[:12] + '/' + filename
                asset = {'path': path, 'key': key, 'bytes': 37, 'sha256': sha}
                manifest['remoteAssets'] = [asset]
                catalog = dict(asset, trackId='cdn-song', kind='audio')
                if mismatched_cdn:
                    catalog['bytes'] += 1
                z.writestr(prefix + 'static/recommendation-assets.json', json.dumps({'version': 1, 'assets': [catalog]}))
                z.writestr(prefix + 'com/qingfeng/music/server/hotsong/RecommendationSamples.class', key)
                entry += ' `' + path + '`'
            z.writestr(prefix + 'static/assets/index-abc.js', entry)
            z.writestr(prefix + 'static/preview-media-manifest.json', json.dumps(manifest))
            if not missing_audio and not remote_only:
                z.writestr(prefix + 'static/media/sample.mp3', audio)
            for name, body in [('IdentityPage', 'payment-orders' if missing else 'cancel-order payment-orders'), ('RechargePage', '/account#payment-orders'), ('AnalyticsPage', 'analytics')]:
                z.writestr(prefix + 'static/assets/' + name + '-abc.js', body)
            for cls in ('web/app/AnalyticsViewController', 'web/publicapi/SiteEventController', 'payment/PayPalPaymentController'):
                z.writestr(prefix + 'com/qingfeng/music/server/' + cls + '.class', b'/orders/{id}/close')
        sha = hashlib.sha256((root / 'app.jar').read_bytes()).hexdigest()
        contract_path = root / 'release-contract.json'
        gate = guard.gate_module()
        if missing_audio:
            actual = {'jarSha256': sha, 'sourceCommit': source, 'frontendSha256': '0'*64, 'migrations': {}}
        else:
            actual = gate.inspect_artifact(root / 'app.jar')
        contract = {'version': 1, **actual, 'frontendSourceTree': 'b'*40,
                    'localChecks': {name: {'exitCode': 0, 'logSha256': 'c'*64} for name in ('frontendTest', 'frontendBuild', 'backendVerify')}}
        contract_path.write_bytes(gate.json_bytes(contract))
        policy = root / 'policy.json'
        policy.write_text(json.dumps({'approved': {sha: {'sourceCommit': source, 'contractSha256': hashlib.sha256(contract_path.read_bytes()).hexdigest(), 'schemaEvidenceSha256': 'd'*64}}}))
        return root, policy

    def test_complete_artifact(self):
        root, policy = self.fixture()
        guard.verify(root, policy)

    def test_cataloged_cdn_preview_is_accepted(self):
        root, policy = self.fixture(cdn=True)
        guard.verify(root, policy)

    def test_fully_cdn_hosted_catalog_is_accepted(self):
        root, policy = self.fixture(cdn=True, remote_only=True)
        guard.verify(root, policy)

    def test_cdn_preview_metadata_mismatch_is_rejected(self):
        root, policy = self.fixture(cdn=True, mismatched_cdn=True)
        with self.assertRaisesRegex(ValueError, 'CDN preview asset does not match'):
            guard.verify(root, policy)

    def test_accepts_hashed_recommendations_and_shared_covers_and_checks_legacy_routes(self):
        digest = 'f' * 64
        routes = [
            ('song', 'audio', 'recommendations/song-ffffffffffff-preview-128.mp3',
             '20261119/audio/ffffffffffff/song-ffffffffffff-preview-128.mp3'),
            ('family', 'cover', 'explore-family-v1.webp', '20261119/cover/ffffffffffff/explore-family-v1.webp'),
            ('folk', 'cover', 'explore-family-v1.webp', '20261119/cover/ffffffffffff/explore-family-v1.webp'),
            ('family', 'audio', 'sample-family-v2-preview-128.mp3', 'preview-128-v1/sample-family-v2-preview-128.mp3'),
        ]
        catalog = []
        for track, kind, route, key in routes:
            catalog.append({'trackId': track, 'kind': kind, 'path': '/media/' + route,
                            'key': 'qingfeng/recommendation-samples/' + key, 'bytes': 37, 'sha256': digest})
        manifest = {'remoteAssets': list({item['path']: {k: item[k] for k in ('path', 'key', 'bytes', 'sha256')}
                                          for item in catalog}.values())}
        classes = 'BOOT-INF/classes/com/qingfeng/music/server/'
        for legacy_filename in ('sample-family-v2-preview-128.mp3', 'sample-unrelated-v2-preview-128.mp3'):
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w') as artifact:
                artifact.writestr('BOOT-INF/classes/static/recommendation-assets.json',
                                  json.dumps({'version': 1, 'assets': catalog}))
                artifact.writestr(classes + 'hotsong/RecommendationSamples.class', '\n'.join(
                    item['path'].removeprefix('/media/') + '\n' + item['key'] for item in catalog[:-1]))
                artifact.writestr(classes + 'web/RecommendationOssRedirect.class',
                                  'qingfeng/recommendation-samples/preview-128-v1/\n' + legacy_filename)
            with zipfile.ZipFile(buffer) as artifact:
                if 'family' in legacy_filename:
                    self.assertEqual(guard.cdn_preview_audio(artifact, manifest),
                                     {catalog[0]['path'], catalog[-1]['path']})
                else:
                    with self.assertRaisesRegex(ValueError, 'server route'):
                        guard.cdn_preview_audio(artifact, manifest)

    def test_old_order_component_rejected_even_if_hash_approved(self):
        root, policy = self.fixture(missing=True)
        with self.assertRaisesRegex(ValueError, 'Missing order actions'):
            guard.verify(root, policy)

    def test_unapproved_artifact_rejected(self):
        root, policy = self.fixture()
        policy.write_text('{"approved":{}}')
        with self.assertRaisesRegex(ValueError, 'Unapproved artifact'):
            guard.verify(root, policy)

    def test_source_manifest_mismatch_rejected(self):
        root, policy = self.fixture()
        (root / 'source-commit.txt').write_text('b' * 40)
        with self.assertRaisesRegex(ValueError, 'Source manifest'):
            guard.verify(root, policy)

    def test_missing_preview_audio_rejected_even_if_artifact_approved(self):
        root, policy = self.fixture(missing_audio=True)
        with self.assertRaisesRegex(ValueError, 'Missing or invalid preview content'):
            guard.verify(root, policy)


    def test_legacy_rollback_cannot_bypass_contract(self):
        root, policy = self.fixture()
        approval = json.loads(policy.read_text())
        next(iter(approval['approved'].values()))['legacyRollback'] = True
        policy.write_text(json.dumps(approval))
        with self.assertRaisesRegex(ValueError, 'Legacy rollback bypass disabled'):
            guard.verify(root, policy)

    def test_missing_schema_approval_rejected(self):
        root, policy = self.fixture()
        approval = json.loads(policy.read_text())
        next(iter(approval['approved'].values())).pop('schemaEvidenceSha256')
        policy.write_text(json.dumps(approval))
        with self.assertRaisesRegex(ValueError, 'Schema compatibility'):
            guard.verify(root, policy)

    def test_tampered_contract_rejected(self):
        root, policy = self.fixture()
        (root / 'release-contract.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'trusted approval'):
            guard.verify(root, policy)


if __name__ == '__main__':
    unittest.main()
