import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('verify-release.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class GuardTest(unittest.TestCase):
    def fixture(self, missing=False, missing_audio=False):
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
            z.writestr(prefix + 'db/migration/V1__fixture.sql', 'SELECT 1;')
            z.writestr(prefix + 'static/index.html', '<script src="/assets/index-abc.js"></script>')
            z.writestr(prefix + 'static/assets/index-abc.js', 'IdentityPage-abc.js RechargePage-abc.js AnalyticsPage-abc.js `/media/sample.mp3`')
            audio = b'ID3original-audio'
            z.writestr(prefix + 'static/preview-media-manifest.json', json.dumps({'version': 1, 'assets': [{'path': '/media/sample.mp3', 'bytes': len(audio), 'sha256': hashlib.sha256(audio).hexdigest()}]}))
            if not missing_audio:
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
