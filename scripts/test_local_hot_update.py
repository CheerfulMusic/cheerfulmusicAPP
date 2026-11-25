import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import local_hot_update as hot


class LocalMainUpdateTest(unittest.TestCase):
    def test_unchanged_migrations_and_the_disabled_queue_are_compatible(self):
        base = {"migrations": {"V56.sql": "installed"}}
        hot.compatible_migrations(base, base)
        extra = dict(base["migrations"], **{hot.QUEUE_MIGRATION: "binary"})
        hot.compatible_migrations(base, {"migrations": extra, "migrationContract": {
            "normalizedSha256": {hot.QUEUE_MIGRATION: hot.QUEUE_MIGRATION_HASH}}})

    def test_migration_drift_and_unknown_dependencies_keep_the_old_runtime(self):
        base = {"migrations": {"V56.sql": "installed"}}
        for migrations in ({}, {"V56.sql": "changed"}, {**base["migrations"], "V58.sql": "new"},
                           {**base["migrations"], hot.QUEUE_MIGRATION: "changed"}):
            with self.subTest(migrations=migrations), self.assertRaises(ValueError):
                hot.compatible_migrations(base, {"migrations": migrations,
                    "migrationContract": {"normalizedSha256": {hot.QUEUE_MIGRATION: "changed"}}})

    def test_checkout_updates_the_owned_detached_snapshot_and_preserves_local_edits(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root, slot = base/"repo", base/"slot"
            root.mkdir()
            hot.git(root, "init", "-q", "-b", "main")
            hot.git(root, "config", "user.email", "fixture@localhost")
            hot.git(root, "config", "user.name", "fixture")
            hot.git(root, "config", "commit.gpgsign", "false")
            (root/"track.txt").write_text("first\n")
            hot.git(root, "add", "track.txt")
            hot.git(root, "commit", "-qm", "first")
            first = hot.git(root, "rev-parse", "main")
            hot.checkout(root, slot, first)
            (root/"track.txt").write_text("second\n")
            hot.git(root, "commit", "-qam", "second")
            second = hot.git(root, "rev-parse", "main")
            hot.checkout(root, slot, second)
            self.assertEqual("second\n", (slot/"track.txt").read_text())
            (slot/"track.txt").write_text("uncommitted\n")
            with self.assertRaises(ValueError):
                hot.checkout(root, slot, first)
            self.assertEqual("uncommitted\n", (slot/"track.txt").read_text())
            self.assertEqual(second, hot.git(root, "rev-parse", "main"))

    def test_watch_discards_superseded_build_and_applies_each_new_main(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache = root/"cache"
            cache.mkdir()
            settings = root/".secrets/local-hot-update.json"
            settings.parent.mkdir()
            settings.write_text("{}")
            state_file = root/".cache/runtime/local.json"
            state_file.parent.mkdir(parents=True)
            state = dict(runId="run", mode="production-main", sourceCommit="old", jarSha256="old", frontendRoot="web")
            hot.write(state_file, state)
            head = ["one"]
            applied = []

            def prepare(workspace, config, path, current, commit):
                if commit == "one":
                    head[0] = "two"
                return {"commit": commit}

            def apply(*args, **kwargs):
                applied.append(hot.read(cache/"plan.json")["commit"])
                current = hot.read(state_file)
                current["sourceCommit"] = head[0]
                hot.write(state_file, current)

            def idle(_):
                if head[0] == "two":
                    head[0] = "three"
                else:
                    state_file.unlink()

            with (patch("sys.argv", ["local_hot_update.py", "watch", str(root)]),
                  patch.object(hot, "get_cache", return_value=cache), patch.object(hot, "git", side_effect=lambda *a: head[0]),
                  patch.object(hot, "prepare", side_effect=prepare), patch.object(subprocess, "run", side_effect=apply),
                  patch.object(hot.time, "sleep", side_effect=idle)):
                hot.main()
            self.assertEqual(["two", "three"], applied)
            self.assertEqual("three", json.loads((cache/"status.json").read_text())["backendCommit"])

    def test_a_failed_build_keeps_serving_and_a_new_commit_is_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            settings = root/".secrets/local-hot-update.json"
            settings.parent.mkdir()
            settings.write_text("{}")
            statefile = root/".cache/runtime/local.json"
            statefile.parent.mkdir(parents=True)
            hot.write(statefile, dict(runId="one", mode="production-main", sourceCommit="working", jarSha256="sha", frontendRoot="web"))
            head = ["broken"]
            applied = []

            def build(workspace, config, cache, current, commit):
                if commit == "broken":
                    raise ValueError("fixture compilation failure")
                return {"commit": commit}

            def idle(_):
                if head[0] == "broken":
                    self.assertEqual("failed", hot.read(root/"status.json")["state"])
                    self.assertEqual("working", hot.read(statefile)["sourceCommit"])
                    self.assertEqual([], applied)
                    head[0] = "fixed"
                else:
                    statefile.unlink()

            with (patch("sys.argv", ["local_hot_update.py", "watch", str(root)]),
                  patch.object(hot, "get_cache", return_value=root), patch.object(hot, "git", side_effect=lambda *a: head[0]),
                  patch.object(hot, "prepare", side_effect=build), patch.object(hot.time, "sleep", side_effect=idle),
                  patch.object(subprocess, "run", side_effect=lambda *a, **kw: applied.append(hot.read(root/"plan.json")["commit"]))):
                hot.main()
            self.assertEqual(["fixed"], applied)


if __name__ == "__main__":
    unittest.main()
