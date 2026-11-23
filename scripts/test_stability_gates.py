import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
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

    def jar(self, corrupt_nested=False, versions=None, bad_audio=False, implicit_import=False):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested,"w") as z:
            if versions is None:
                for migration in sorted((ROOT / "Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration").glob("V*__*.sql")):
                    z.writestr("db/migration/" + migration.name, migration.read_bytes())
            else:
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

    def isolated_cohort(self):
        cache=self.root/".cache";private=cache/"release-gate-cohorts";private.mkdir(parents=True)
        manifest=private/"r1161605.json"
        manifest.write_text(json.dumps({"version":1,"revision":"r1161605",
                                        "carryForwardRevision":"r1161606","compatibilityPort":57346}))
        names={"migration":"p0_integration_migration_20261112",
               "credits":"p0_integrated_credits_20261112",
               "application":"p0_integration_application_20261112",
               "legacy40":"p0_integration_legacy40_20261112",
               "core40":"p0_core40_application_20261112",
               "overlap":"p0_dispute_overlap_20261113",
               "history":"p0_historical_cutover_20261113"}
        configs=[]
        for label,prefix in names.items():
            directory=(cache/("payment-go-live-execution-20261113" if label in ("overlap","history")
                              else "payment-main-integration-20261112"));directory.mkdir(exist_ok=True)
            for revision in (("r1161605","r1161606") if label=="history" else ("r1161605",)):
                cfg={"host":"127.0.0.1","port":23316,"database":prefix+revision,
                     "user":"user_"+label+revision,"password":"private_"+label+revision,
                     "uuid":"05c27d3c-dda3-11f1-aba0-84a938421519"}
                path=directory/(label+"-db-private-"+revision+".json");path.write_text(json.dumps(cfg));configs.append(path)
        return cache,manifest,configs

    def fake_mysql(self, table_count=0, server_uuid="05c27d3c-dda3-11f1-aba0-84a938421519", instances=None):
        class Connection:
            def __init__(self,parameters): self.parameters=parameters
            def __enter__(self): return self
            def __exit__(self,*_): pass
            def cursor(self): return Cursor(self.parameters)
        class Cursor:
            def __init__(self,parameters): self.parameters=parameters
            def __enter__(self): return self
            def __exit__(self,*_): pass
            def execute(self,query): self.query=query
            def fetchone(self):
                if self.query.startswith("SELECT COUNT(*)"):return (table_count,)
                if self.query=="SELECT CURRENT_ROLE()":return ("NONE",)
                if self.parameters["port"]==57346:
                    if instances:
                        instance=instances["compatibility"]
                        return (instance["uuid"],instance["datadir"],57346,"127.0.0.1","8.0.46")
                    return ("47427132-df4d-11f1-89e2-84a938421519","R:\\v45-compatibility-r1\\",57346,"127.0.0.1","8.0.46")
                if instances:
                    instance=instances["financial"]
                    return (instance["uuid"],instance["datadir"],23316,"127.0.0.1","8.0.46",self.parameters["database"])
                return (server_uuid,"R:\\data\\",23316,"127.0.0.1","8.0.46",self.parameters["database"])
            def fetchall(self):
                if self.query=="SHOW GRANTS":
                    name=self.parameters["database"]
                    return [("GRANT USAGE ON *.* TO `user`@`127.0.0.1`",),
                            ("GRANT ALL PRIVILEGES ON `"+name+"`.* TO `user`@`127.0.0.1`",)]
                if self.parameters["port"]==57346:return [("information_schema",),("performance_schema",)]
                return [("information_schema",),("performance_schema",),(self.parameters["database"],)]
        return types.SimpleNamespace(connect=lambda **parameters:Connection(parameters))

    def test_private_fresh_mysql_cohort_is_scoped_to_backend_verify(self):
        cache,manifest,_=self.isolated_cohort()
        with patch.dict(sys.modules,{"pymysql":self.fake_mysql()}):
            env,secrets=gate.load_isolated_backend_cohort(cache,manifest)
        self.assertEqual("57346",env["QF_EXECUTION_MYSQL_TEST_PORT"])
        self.assertIn("P0_CARRY_FORWARD_MYSQL_PASSWORD",env)
        self.assertEqual(8,len([key for key in env if key.endswith("_PASSWORD")]))
        self.assertEqual(16,len(secrets))

    def local_isolated_cohort(self):
        cache,manifest,configs=self.isolated_cohort()
        instances={}
        uuids={"financial":"01f1aea6-3e9e-433f-9acb-173ac8cf1a55", "compatibility":"b46b13cc-3222-48ef-871b-d8f5561558cf"}
        for role, identifier in uuids.items():
            datadir=cache/"release-gate-cohorts"/"instances"/"fixture-123"/role
            datadir.mkdir(parents=True)
            (datadir/"auto.cnf").write_text("[auto]\nserver-uuid="+identifier+"\n",encoding="utf-8")
            instances[role]={"uuid":identifier,"datadir":str(datadir)}
        value=json.loads(manifest.read_text());value.update(version=2,instances=instances)
        manifest.write_text(json.dumps(value),encoding="utf-8")
        for path in configs:
            cfg=json.loads(path.read_text());cfg["uuid"]=uuids["financial"]
            path.write_text(json.dumps(cfg),encoding="utf-8")
        return cache,manifest,instances

    def test_local_mysql_cohort_uses_initialized_instance_identity(self):
        cache,manifest,instances=self.local_isolated_cohort()
        with patch.dict(sys.modules,{"pymysql":self.fake_mysql(instances=instances)}):
            env,secrets=gate.load_isolated_backend_cohort(cache,manifest)
        self.assertEqual(instances["financial"]["uuid"],env["P0_CARRY_FORWARD_MYSQL_UUID"])
        self.assertEqual(instances["financial"]["datadir"],env["QF_ISOLATED_COHORT_DATADIR"])
        self.assertEqual(16,len(secrets))

    def test_local_mysql_cohort_rejects_reserved_uuid_and_external_datadir(self):
        cache,manifest,instances=self.local_isolated_cohort()
        value=json.loads(manifest.read_text())
        value["instances"]["financial"]["uuid"]=gate.PRODUCTION_MYSQL_UUID
        manifest.write_text(json.dumps(value),encoding="utf-8")
        with self.assertRaisesRegex(gate.GateError,"reserved local cohort instance UUID"):
            gate.load_isolated_backend_cohort(cache,manifest)
        value["instances"]=instances
        value["instances"]["financial"]["datadir"]=str(self.root/"outside"/"financial")
        manifest.write_text(json.dumps(value),encoding="utf-8")
        with self.assertRaisesRegex(gate.GateError,"data directory"):
            gate.load_isolated_backend_cohort(cache,manifest)

    def test_local_mysql_cohort_rejects_running_instance_with_other_datadir(self):
        cache,manifest,instances=self.local_isolated_cohort()
        instances["financial"]["datadir"]=str(self.root/"outside"/"financial")
        with patch.dict(sys.modules,{"pymysql":self.fake_mysql(instances=instances)}):
            with self.assertRaisesRegex(gate.GateError,"identity changed"):
                gate.load_isolated_backend_cohort(cache,manifest)

    def test_production_address_and_reused_mysql_cohort_are_rejected(self):
        cache,manifest,configs=self.isolated_cohort()
        cfg=json.loads(configs[0].read_text());cfg["host"]="cheerfulmusic.com";configs[0].write_text(json.dumps(cfg))
        with patch.dict(sys.modules,{"pymysql":self.fake_mysql()}):
            with self.assertRaisesRegex(gate.GateError,"configuration invalid"):
                gate.load_isolated_backend_cohort(cache,manifest)
        cfg["host"]="127.0.0.1";configs[0].write_text(json.dumps(cfg))
        with patch.dict(sys.modules,{"pymysql":self.fake_mysql(table_count=1)}):
            with self.assertRaisesRegex(gate.GateError,"not fresh"):
                gate.load_isolated_backend_cohort(cache,manifest)
        with patch.dict(sys.modules,{"pymysql":self.fake_mysql(server_uuid="production-uuid")}):
            with self.assertRaisesRegex(gate.GateError,"identity changed"):
                gate.load_isolated_backend_cohort(cache,manifest)

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
        tools=self.root/"cache/toolchains";tools.mkdir(parents=True)
        pin_zip=tools/"node-fixture.zip"
        with zipfile.ZipFile(pin_zip,"w") as z:
            z.writestr("node-fixture/node.exe",b"synthetic-node")
            z.writestr("node-fixture/npm.cmd",b"synthetic-npm")
        with zipfile.ZipFile(pin_zip) as z:z.extractall(tools)
        (workspace/".node-toolchain.json").write_text(json.dumps({"version":"24.22.1","archiveName":"node-fixture.zip","archiveRoot":"node-fixture","archiveSha256":gate.sha(pin_zip.read_bytes()),"nodeExeSha256":gate.sha(b"synthetic-node")}))
        fixture_jar=self.jar()
        archive=io.BytesIO()
        with zipfile.ZipFile(archive,"w") as z:
            for migration in sorted((ROOT / "Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration").glob("V*__*.sql")):
                z.writestr("Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration/" + migration.name,migration.read_bytes())
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
            self.assertEqual(subprocess.PIPE,kwargs["stdout"])
            output=b""
            if command[0].endswith("java.exe"):
                output=b'java version "17"\n'
            elif command[0].endswith("node.exe"):
                output=b"v24.22.1\n"
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
                self.assertEqual("synthetic-private-password",env["P0_INTEGRATION_MIGRATION_MYSQL_PASSWORD"])
                self.assertEqual("57346",env["QF_EXECUTION_MYSQL_TEST_PORT"])
                output=b"Maven fixture accidentally logged synthetic-private-password"
                target=Path(kwargs["cwd"])/"qf-server/target";target.mkdir(parents=True)
                (target/"qf-server-0.1.0-SNAPSHOT.jar").write_bytes(fixture_jar.read_bytes())
            else:
                self.assertFalse(any(key.startswith("P0_") for key in env))
            return types.SimpleNamespace(returncode=0,stdout=output)
        backend={"P0_INTEGRATION_MIGRATION_MYSQL_PASSWORD":"synthetic-private-password",
                 "QF_EXECUTION_MYSQL_TEST_PORT":"57346"}
        with patch.object(gate,"check_source",return_value=source),patch.object(gate,"git",side_effect=fake_git),patch.object(gate,"load_isolated_backend_cohort",return_value=(backend,{"synthetic-private-password"})),patch.object(gate.subprocess,"check_output",return_value=archive.getvalue()),patch.object(gate.subprocess,"run",side_effect=fake_process),patch.object(gate.shutil,"which",side_effect=lambda name:"fixture-"+name),patch.dict(os.environ,{"MYSQL_URL":"synthetic-forbidden","PAYPAL_CLIENT_SECRET":"synthetic-forbidden","JAVA_TOOL_OPTIONS":"synthetic-forbidden"}):
            result=gate.build(workspace,self.root/"unused",java,self.root/"cache","private-cohort.json")
        self.assertEqual(6,len(calls))
        self.assertEqual("VERIFIED",result["localBuild"])
        self.assertEqual("BLOCKED",result["productionRelease"])
        self.assertFalse((Path(result["package"]).parent/"source/.secrets").exists())
        self.assertNotIn(b"synthetic-private-password",(Path(result["package"]).parent/"backendVerify.log").read_bytes())
        self.assertNotIn(b"synthetic-private-password",(Path(result["package"])/"release-contract.json").read_bytes())

    def test_modified_pinned_node_runtime_is_rejected_before_execution(self):
        cache=self.root/"cache";tools=cache/"toolchains";tools.mkdir(parents=True)
        archive=tools/"node-fixture.zip"
        with zipfile.ZipFile(archive,"w") as z:
            z.writestr("node-fixture/node.exe",b"original")
            z.writestr("node-fixture/npm.cmd",b"launcher")
        with zipfile.ZipFile(archive) as z:z.extractall(tools)
        pin={"archiveName":"node-fixture.zip","archiveRoot":"node-fixture","archiveSha256":gate.sha(archive.read_bytes()),"nodeExeSha256":gate.sha(b"original"),"version":"24.22.1"}
        (tools/"node-fixture/node.exe").write_bytes(b"altered")
        with self.assertRaisesRegex(gate.GateError,"toolchain differs"):
            gate.check_node_toolchain(cache,pin)

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

    def test_feature_migrations_require_consecutive_pinned_extensions(self):
        migrations = {p.name: gate.normalized_migration_sha(p.read_bytes()) for p in
            (ROOT / "Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration").glob("V*__*.sql")}
        self.assertEqual("49", gate.verify_migration_set(migrations)["lyricsPreparationVersion"])
        self.assertEqual("50", gate.verify_migration_set(migrations)["discoverShelvesVersion"])
        reference_name = "V51__generation_audio_reference.sql"
        self.assertEqual("51", gate.verify_migration_set(migrations)["generationAudioReferenceVersion"])
        with self.assertRaises(gate.GateError): gate.verify_migration_set({**migrations, reference_name: "0" * 64})
        missing = dict(migrations); missing.pop("V47__music_video_projects.sql")
        with self.assertRaises(gate.GateError): gate.verify_migration_set(missing)
        modified = dict(migrations); modified["V49__lyrics_preparation.sql"] = "0" * 64
        with self.assertRaises(gate.GateError): gate.verify_migration_set(modified)
        discover_name = "V50__discover_daily_plays_and_vocal_approval.sql"
        modified = {**migrations, discover_name: "0" * 64}
        with self.assertRaises(gate.GateError): gate.verify_migration_set(modified)
        pre_discover = {k: v for k, v in migrations.items() if k not in {discover_name, reference_name}}
        self.assertEqual("49", gate.verify_migration_set(pre_discover)["lyricsPreparationVersion"])
        pre_lyrics = {k: v for k, v in pre_discover.items() if k != "V49__lyrics_preparation.sql"}
        self.assertEqual("48", gate.verify_migration_set(pre_lyrics)["phoneBindingVersion"])
        rollback = {k: v for k, v in migrations.items() if k not in gate.FEATURE_MIGRATION_SHA256}
        self.assertEqual("46", gate.verify_migration_set(rollback)["agentAllowanceVersion"])

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
