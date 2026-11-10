import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("gate", ROOT / "scripts/release_gate.py")
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
spec2 = importlib.util.spec_from_file_location("media", ROOT / "scripts/media_health_check.py")
media = importlib.util.module_from_spec(spec2); spec2.loader.exec_module(media)

class GateTests(unittest.TestCase):
    def setUp(self):
        base = Path(os.environ.get("QF_GATE_TEST_TMP", str(ROOT / ".cache/gate-test-tmp"))); base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)

    def jar(self, corrupt_nested=False, versions=(1,2), bad_audio=False, implicit_import=False):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested,"w") as z:
            for v in versions: z.writestr("db/migration/V%d__fixture.sql"%v,"SELECT %d;"%v)
        path=self.root/"app.jar"; prefix="BOOT-INF/classes/static/"
        audio=b"ID3synthetic-fixture"
        with zipfile.ZipFile(path,"w") as z:
            z.writestr("META-INF/MANIFEST.MF","Main-Class: org.springframework.boot.loader.launch.JarLauncher\n")
            z.writestr("BOOT-INF/classes/release-commit.txt","a"*40)
            z.writestr("BOOT-INF/classes/application.yml","spring: {}")
            z.writestr("BOOT-INF/classes/application-mysql.yml","spring:\n  config:\n    import: optional:file:../config/local.properties" if implicit_import else "spring: {}")
            z.writestr(prefix+"index.html",'<script src="/assets/test.js"></script>')
            z.writestr(prefix+"assets/test.js","synthetic frontend")
            z.writestr(prefix+"media/sample.mp3",audio)
            z.writestr(prefix+"preview-media-manifest.json",json.dumps({"version":1,"assets":[{"path":"/media/sample.mp3","bytes":len(audio),"sha256":("0"*64 if bad_audio else gate.sha(audio))}]}))
            z.writestr("BOOT-INF/lib/qf-infrastructure-0.1.0-SNAPSHOT.jar",b"truncated" if corrupt_nested else nested.getvalue())
        return path

    def contract(self,jar):
        value={"version":1,**gate.inspect_artifact(jar),"frontendSourceTree":"b"*40,
               "localChecks":{k:{"exitCode":0,"logSha256":"c"*64} for k in ("frontendTest","frontendBuild","backendVerify")}}
        p=self.root/"release-contract.json";p.write_bytes(gate.json_bytes(value))
        return p,gate.sha(p.read_bytes()),value

    def frozen_export(self):
        export=self.root/"export"
        sql=export/"Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration"
        static=export/"Qingfeng_AI_Music_VUE/dist"
        sql.mkdir(parents=True);static.mkdir(parents=True)
        (sql/"V1__fixture.sql").write_text("SELECT 1;")
        (static/"index.html").write_text("this build")
        actual={"migrations":{"V1__fixture.sql":gate.sha(b"SELECT 1;")},
                "frontendSha256":gate.sha(gate.json_bytes({"index.html":gate.sha(b"this build")})),
                "sourceCommit":"a"*40}
        return export,actual

    def test_packaged_bytes_match_frozen_export(self):
        export,actual=self.frozen_export()
        gate.check_build_output(export,{"sourceCommit":"a"*40},actual)

    def test_old_packaged_sql_cannot_hide_behind_fresh_manifest(self):
        export,actual=self.frozen_export()
        actual["migrations"]["V1__fixture.sql"]=gate.sha(b"SELECT 2;")
        with self.assertRaisesRegex(gate.GateError,"frozen source"):
            gate.check_build_output(export,{"sourceCommit":"a"*40},actual)

    def test_stale_frontend_cannot_hide_behind_source_commit(self):
        export,actual=self.frozen_export()
        actual["frontendSha256"]=gate.sha(b"old frontend")
        with self.assertRaisesRegex(gate.GateError,"this build output"):
            gate.check_build_output(export,{"sourceCommit":"a"*40},actual)

    def test_export_build_pipeline_is_offline_and_does_not_inherit_provider_secrets(self):
        import types
        workspace=self.root/"workspace";workspace.mkdir()
        java=self.root/"jdk";(java/"bin").mkdir(parents=True);(java/"bin/java.exe").write_bytes(b"fixture")
        fixture_jar=self.jar()
        archive=io.BytesIO()
        with zipfile.ZipFile(archive,"w") as z:
            for version in (1,2):
                z.writestr("Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration/V%d__fixture.sql"%version,"SELECT %d;"%version)
            z.writestr("Qingfeng_AI_Music_VUE/package.json","{}")
        source={"sourceCommit":"a"*40,"frontendSourceTree":"b"*40}
        calls=[]
        def fake_git(_,*args):
            if "--git-common-dir" in args:return str(self.root/".git")
            if args[0]=="show":return "1700000000"
            raise AssertionError(args)
        def fake_process(command,**kwargs):
            calls.append(command)
            env=kwargs["env"]
            for forbidden in ("MYSQL_URL","PAYPAL_CLIENT_SECRET","JAVA_TOOL_OPTIONS"):
                self.assertNotIn(forbidden,env)
            self.assertNotEqual(env["NPM_CONFIG_USERCONFIG"],env["NPM_CONFIG_GLOBALCONFIG"])
            if command[0].endswith("java.exe"):
                kwargs["stdout"].write(b'java version "17"\n')
            elif "ci" in command:
                self.assertIn("--offline",command);self.assertIn("--ignore-scripts",command)
            elif "build" in command:
                dist=Path(kwargs["cwd"])/"dist";dist.mkdir()
                with zipfile.ZipFile(fixture_jar) as z:
                    for name in z.namelist():
                        prefix="BOOT-INF/classes/static/"
                        if name.startswith(prefix):
                            dest=dist/name[len(prefix):];dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(z.read(name))
            elif "verify" in command:
                self.assertIn("-o",command)
                target=Path(kwargs["cwd"])/"qf-server/target";target.mkdir(parents=True)
                (target/"qf-server-0.1.0-SNAPSHOT.jar").write_bytes(fixture_jar.read_bytes())
            return types.SimpleNamespace(returncode=0)
        with patch.object(gate,"check_source",return_value=source),patch.object(gate,"git",side_effect=fake_git),patch.object(gate.subprocess,"check_output",return_value=archive.getvalue()),patch.object(gate.subprocess,"run",side_effect=fake_process),patch.object(gate.shutil,"which",side_effect=lambda name:"fixture-"+name),patch.dict(os.environ,{"MYSQL_URL":"synthetic-forbidden","PAYPAL_CLIENT_SECRET":"synthetic-forbidden","JAVA_TOOL_OPTIONS":"synthetic-forbidden"}):
            result=gate.build(workspace,self.root/"unused",java,self.root/"cache")
        self.assertEqual(5,len(calls))
        self.assertEqual("VERIFIED",result["localBuild"])
        self.assertEqual("BLOCKED",result["productionRelease"])
        self.assertFalse((Path(result["package"]).parent/"source/.secrets").exists())

    def test_implicit_external_configuration_is_rejected(self):
        with self.assertRaisesRegex(gate.GateError,"Implicit external"):
            gate.inspect_artifact(self.jar(implicit_import=True))

    def test_complete_nested_artifact_and_pinned_contract(self):
        jar=self.jar(); p,pin,_=self.contract(jar)
        self.assertEqual("a"*40,gate.verify_contract(jar,p,pin,"a"*40)["sourceCommit"])

    def test_truncated_outer_jar_rejected(self):
        jar=self.jar(); jar.write_bytes(jar.read_bytes()[:80])
        with self.assertRaises(gate.GateError):gate.inspect_artifact(jar)

    def test_truncated_nested_jar_rejected(self):
        with self.assertRaisesRegex(gate.GateError,"archive"):
            gate.inspect_artifact(self.jar(corrupt_nested=True))

    def test_duplicate_zip_members_rejected(self):
        import warnings
        jar=self.jar()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore",UserWarning)
            with zipfile.ZipFile(jar,"a") as z:
                z.writestr("BOOT-INF/classes/release-commit.txt","a"*40)
        with self.assertRaisesRegex(gate.GateError,"Duplicate"):
            gate.inspect_artifact(jar)

    def test_missing_migration_rejected(self):
        with self.assertRaisesRegex(gate.GateError,"migration chain"):gate.inspect_artifact(self.jar(versions=(1,3)))

    def test_preview_byte_mismatch_rejected(self):
        with self.assertRaises(gate.GateError):gate.inspect_artifact(self.jar(bad_audio=True))

    def test_contract_needs_independent_pin(self):
        jar=self.jar();p,_,_=self.contract(jar)
        with self.assertRaisesRegex(gate.GateError,"Independent"):gate.verify_contract(jar,p,"","a"*40)

    def test_rewritten_contract_rejected(self):
        jar=self.jar();p,pin,value=self.contract(jar)
        value["sourceCommit"]="d"*40;p.write_bytes(gate.json_bytes(value))
        with self.assertRaisesRegex(gate.GateError,"trusted approval"):gate.verify_contract(jar,p,pin,"a"*40)

    def test_migration_content_must_match_contract(self):
        jar=self.jar();p,pin,value=self.contract(jar)
        value["migrations"]["V1__fixture.sql"]="0"*64;p.write_bytes(gate.json_bytes(value))
        with self.assertRaisesRegex(gate.GateError,"migrations"):gate.verify_contract(jar,p,gate.sha(p.read_bytes()),"a"*40)

    def test_frontend_content_must_match_contract(self):
        jar=self.jar();p,pin,value=self.contract(jar)
        value["frontendSha256"]="0"*64;p.write_bytes(gate.json_bytes(value))
        with self.assertRaisesRegex(gate.GateError,"frontendSha256"):gate.verify_contract(jar,p,gate.sha(p.read_bytes()),"a"*40)

    def test_failed_local_checks_rejected_even_if_contract_pinned(self):
        jar=self.jar();p,_,value=self.contract(jar)
        value["localChecks"]["backendVerify"]["exitCode"]=1;p.write_bytes(gate.json_bytes(value))
        with self.assertRaisesRegex(gate.GateError,"local check"):gate.verify_contract(jar,p,gate.sha(p.read_bytes()),"a"*40)

    def test_unknown_schema_blocks_before_git_or_network(self):
        jar=self.jar();p,pin,value=self.contract(jar)
        m=self.root/"runtime.json";m.write_text(json.dumps({"artifactContractSha256":pin,"sourceCommit":"a"*40,"jarSha256":value["jarSha256"]}))
        with patch.object(gate,"git",side_effect=AssertionError("must not inspect frontend yet")):
            with self.assertRaisesRegex(gate.GateError,"Schema compatibility UNKNOWN"):gate.check_startup(ROOT,m,jar)

    def test_dirty_source_blocks_before_build_install_or_process_stop(self):
        b=self.root/"baseline.json";b.write_text(json.dumps({"sourceCommit":"a"*40}))
        def fake_git(_,*args):
            if args[:2]==("rev-parse","HEAD"):return "b"*40
            if args[0]=="status":return " M user-file"
            raise AssertionError(args)
        with patch.object(gate,"git",side_effect=fake_git),patch.object(gate.subprocess,"check_output",side_effect=AssertionError("export blocked")):
            with self.assertRaisesRegex(gate.GateError,"Dirty source"):gate.build(ROOT,b,None,self.root)

    def test_wrong_source_and_unsafe_zip_rejected(self):
        jar=self.jar();p,pin,_=self.contract(jar)
        with self.assertRaisesRegex(gate.GateError,"source version"):gate.verify_contract(jar,p,pin,"d"*40)
        raw=io.BytesIO()
        with zipfile.ZipFile(raw,"w") as z:z.writestr("../escape","x")
        with self.assertRaisesRegex(gate.GateError,"Unsafe"):gate.checked_zip(raw.getvalue())

class MediaTests(unittest.TestCase):
    def props(self):
        return {"qingfeng.cdn.domain":"https://media.fixture.invalid","qingfeng.cdn.key-primary":"synthetic-key",
                "qingfeng.oss.endpoint":"https://oss.fixture.invalid","qingfeng.oss.bucket":"test-fixture",
                "qingfeng.oss.prefix":"fixture/audio/"}
    def invoke(self,results):
        with patch.object(media,"db_query",return_value="oss:12345678-1234-1234-1234-123456789abc.mp3"),patch.object(media,"http_check",side_effect=results),patch.object(media,"write_status") as status,patch.object(media,"maybe_alert") as alert,patch.object(media,"log"),patch.object(media.subprocess,"run",side_effect=AssertionError("no systemctl or other process")):
            value=media.probe(self.props())
            return value,status,alert

    def test_signed_cdn_and_private_origin_are_normal(self):
        value,status,alert=self.invoke([(206,"audio/mpeg","1024"),(403,"","")])
        self.assertEqual(0,value);alert.assert_not_called()
        self.assertNotIn("auth_key",str(status.call_args))

    def test_anonymous_source_open_is_security_failure(self):
        value,_,alert=self.invoke([(206,"audio/mpeg","1024"),(206,"audio/mpeg","1024")])
        self.assertEqual(2,value);alert.assert_called_once()

    def test_cdn_failure_does_not_use_public_origin_or_restart(self):
        value,_,alert=self.invoke([(503,"",""),(403,"","")])
        self.assertEqual(2,value);alert.assert_called_once()

    def test_missing_object_does_not_prove_source_private(self):
        self.assertEqual(2,self.invoke([(206,"audio/mpeg","1024"),(404,"","")])[0])

    def test_invalid_size_cannot_report_success(self):
        self.assertEqual(2,self.invoke([(200,"audio/mpeg","garbage"),(403,"","")])[0])

    def test_default_main_has_zero_production_access(self):
        with patch.object(media,"parse_props",side_effect=AssertionError("no configuration read")),patch.object(media,"db_query",side_effect=AssertionError("no DB")),patch.object(media,"http_check",side_effect=AssertionError("no HTTP")):
            self.assertEqual(2,media.main([]))

    def test_run_without_explicit_paths_cannot_touch_legacy_configuration(self):
        with patch.object(media,"parse_props",side_effect=AssertionError("no legacy config")):
            self.assertEqual(2,media.main(["--run"]))

    def test_legacy_heal_functions_cannot_write_or_restart_or_claim_success(self):
        with patch.object(media,"log"),patch("builtins.open",side_effect=AssertionError("no file write")),patch.object(media.subprocess,"run",side_effect=AssertionError("no process")):
            self.assertFalse(media.rewrite_cdn_domain("https://fixture.invalid"))
            self.assertFalse(media.auto_heal("https://fixture.invalid"))
            self.assertFalse(media.restore_cdn("https://fixture.invalid"))

    def test_legacy_public_fallback_config_is_blocked(self):
        props=self.props();props["qingfeng.cdn.domain"]="https://test-fixture.oss.fixture.invalid"
        with patch.object(media,"log"),patch.object(media,"db_query",side_effect=AssertionError("stop before DB")):
            self.assertEqual(2,media.probe(props))

    def test_probe_lock_prevents_duplicate_execution(self):
        base=ROOT/".cache/gate-test-tmp";base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            nested=[]
            argv=["--run","--props",str(Path(directory)/"fixture.properties"),"--state-dir",directory,"--log-dir",directory]
            def one_probe(_):
                nested.append(media.main(argv))
                return 0
            with patch.object(media,"STATUS",str(Path(directory)/"status.json")),patch.object(media,"parse_props",return_value={}) as parse,patch.object(media,"probe",side_effect=one_probe):
                self.assertEqual(0,media.main(argv))
                self.assertEqual([2],nested)
                self.assertEqual(1,parse.call_count)

    def test_http_timeout_branch_has_fixed_budget(self):
        with patch.object(media.urllib.request,"urlopen",side_effect=TimeoutError("synthetic timeout")) as request:
            result=media.http_check("https://fixture.invalid")
        self.assertEqual(-1,result[0])
        self.assertEqual(20,request.call_args.kwargs["timeout"])

if __name__=="__main__":unittest.main()
