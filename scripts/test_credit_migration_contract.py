import importlib.util
import io
from pathlib import Path
import re
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("stability_fixture", ROOT / "scripts/test_stability_gates.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
gate = fixture.gate
SQL = ROOT / "Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration"
LEGACY = ROOT / "Qingfeng_AI_Music/qf-server/src/test/resources/legacy-payment-migrations"


class CreditMigrationContractTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.GateTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def rewrite_jar(self, path, change):
        with zipfile.ZipFile(path) as z:
            items = {n: z.read(n) for n in z.namelist()}
        change(items)
        with zipfile.ZipFile(path, "w") as z:
            for name, data in items.items():
                z.writestr(name, data)
        return path

    def nested_change(self, path, change):
        name = "BOOT-INF/lib/qf-infrastructure-0.1.0-SNAPSHOT.jar"
        def apply(items):
            with zipfile.ZipFile(io.BytesIO(items[name])) as nested:
                entries = {n: nested.read(n) for n in nested.namelist()}
            change(entries)
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as nested:
                for key, data in entries.items():
                    nested.writestr(key, data)
            items[name] = output.getvalue()
        return self.rewrite_jar(path, apply)

    def test_core_39_originals_and_v40_are_one_exact_contract(self):
        actual = {p.name: gate.normalized_migration_sha(p.read_bytes()) for p in SQL.glob("V*__*.sql")}
        contract = gate.verify_migration_set(actual)
        self.assertEqual("7a7778afba67bb1b554068b4ca7a5fa6e173b1a6", contract["coreSourceCommit"])
        self.assertEqual(40, len(actual))
        self.assertEqual(39, len(gate.CORE_MIGRATION_SHA256))

    def test_legacy_fixtures_keep_the_original_executed_bytes(self):
        expected = {
            "V33__credit_entitlement_recovery.sql": "224b8e1428ca7e87e2abfd1d96032d812aac603c0f14c5f71a6c59f865116b1f",
            "V34__payment_return_evidence.sql": "dd725376843e703aef48df42533f810db80ea9c255cf59b4b17c12770d92060c",
        }
        self.assertEqual(expected, {p.name: gate.sha(p.read_bytes()) for p in LEGACY.glob("*.sql")})
        self.assertFalse((SQL / "V33__credit_entitlement_recovery.sql").exists())
        self.assertFalse((SQL / "V34__payment_return_evidence.sql").exists())

    def test_v40_contains_integration_and_no_automatic_activation(self):
        text = (SQL / gate.INTEGRATED_MIGRATION).read_text(encoding="utf-8")
        tables = re.findall(r"CREATE TABLE (\w+)", text)
        self.assertEqual(19, len(tables))
        self.assertIn("p0_credit_native_identity", tables)
        self.assertIn("payment_inbox_review", tables)
        self.assertIn("payment_inbox_attempt", tables)
        self.assertIn("DEFAULT 'LEGACY_UNKNOWN'", text)
        self.assertIn("PRIMARY KEY (provider, merchant_scope, env_scope, event_id)", text)
        self.assertIn("COLLATE utf8mb4_bin", text)
        self.assertNotIn("credit_account", tables)
        self.assertNotIn("payment_webhook_inbox", tables)
        self.assertIn("SELECT owner_id, balance, updated_at FROM credit_account WHERE 1=0", text)
        self.assertIn("CHECK (protocol = 3)", text)
        self.assertIn("VALUES (1, UUID(), 3)", text)
        self.assertIn("VALUES (1,'PREPARING')", text)
        self.assertNotRegex(text, r"(?i)UPDATE\s+p0_credit_cutover_state")
        self.assertEqual(17, len(re.findall(r"CREATE TRIGGER (\w+)", text)))

    def test_complete_packaged_chain_is_verified(self):
        actual = gate.inspect_artifact(self.fixture.jar())
        self.assertEqual(40, len(actual["migrations"]))
        self.assertEqual("40", actual["migrationContract"]["integrationVersion"])

    def test_old_v33_cannot_be_added_beside_canonical_v33(self):
        jar = self.nested_change(self.fixture.jar(), lambda items: items.update({
            "db/migration/V33__credit_entitlement_recovery.sql": (LEGACY / "V33__credit_entitlement_recovery.sql").read_bytes()}))
        with self.assertRaisesRegex(gate.GateError, "Duplicate migration version"):
            gate.inspect_artifact(jar)

    def test_test_only_legacy_sql_is_forbidden_in_production_jar(self):
        jar = self.rewrite_jar(self.fixture.jar(), lambda items: items.update({
            "BOOT-INF/classes/legacy-payment-migrations/V34__payment_return_evidence.sql": b"SELECT 1;"}))
        with self.assertRaisesRegex(gate.GateError, "Test-only or alternate"):
            gate.inspect_artifact(jar)

    def test_missing_v40_does_not_pass_as_an_old_complete_chain(self):
        jar = self.nested_change(self.fixture.jar(), lambda items: items.pop("db/migration/" + gate.INTEGRATED_MIGRATION))
        with self.assertRaisesRegex(gate.GateError, "exactly core V1..V39"):
            gate.inspect_artifact(jar)

    def test_core_edit_is_rejected_even_with_all_versions_present(self):
        jar = self.nested_change(self.fixture.jar(), lambda items: items.update({"db/migration/V33__site_event_geo.sql": b"SELECT 1;"}))
        with self.assertRaisesRegex(gate.GateError, "Canonical migration source changed"):
            gate.inspect_artifact(jar)

    def test_integration_edit_is_rejected_even_with_all_versions_present(self):
        jar = self.nested_change(self.fixture.jar(), lambda items: items.update({"db/migration/" + gate.INTEGRATED_MIGRATION: b"SELECT 1;"}))
        with self.assertRaisesRegex(gate.GateError, "Canonical migration source changed"):
            gate.inspect_artifact(jar)

    def test_eol_normalization_does_not_weaken_raw_approved_contract(self):
        jar = self.fixture.jar()
        contract, pin, value = self.fixture.contract(jar)
        def change(items):
            name = "db/migration/V33__site_event_geo.sql"
            body = items[name].decode("utf-8").replace("\r\n", "\n")
            items[name] = body.replace("\n", "\r\n").encode("utf-8") if b"\r\n" not in items[name] else body.encode("utf-8")
        self.nested_change(jar, change)
        self.assertEqual(value["migrationContract"], gate.inspect_artifact(jar)["migrationContract"])
        with self.assertRaisesRegex(gate.GateError, "Artifact does not match contract"):
            gate.verify_contract(jar, contract, pin, "a" * 40)


if __name__ == "__main__":
    unittest.main()
