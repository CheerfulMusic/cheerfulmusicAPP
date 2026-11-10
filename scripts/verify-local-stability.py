"""Run only local gate regressions and negative entry-point checks; never launch the application."""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parent.parent
if "--offline-child" in sys.argv:
    suite = sys.argv[-1]
    def deny(event, args):
        if event.startswith("socket.") or event in {"subprocess.Popen", "os.system"}:
            raise PermissionError("OFFLINE_TEST_BOUNDARY")
        if event == "open" and args and isinstance(args[0], (str, bytes)):
            path = os.fsdecode(args[0]).replace("\\", "/").lower()
            if "/.secrets/" in path or "/etc/qingfeng-music" in path:
                raise PermissionError("PRIVATE_CONFIGURATION_DENIED")
    sys.addaudithook(deny)
    location, pattern = (ROOT/"scripts", "test_stability_gates.py") if suite=="stability" else (ROOT/"Qingfeng_AI_Music/deploy", "test_release_guard.py")
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.discover(str(location),pattern=pattern))
    raise SystemExit(0 if result.wasSuccessful() else 1)

common = Path(subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--path-format=absolute", "--git-common-dir"]).decode().strip()).parent
os.environ["QF_GATE_TEST_TMP"] = str(common / ".cache/stability-test-tmp")

stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime("%Y%m%d-%H%M%S")
evidence = ROOT/".cache/stability-hardening"/stamp
evidence.mkdir(parents=True,exist_ok=False)
results=[]
def run(name, command, expected, description):
    p=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60,encoding="utf-8",errors="replace")
    log=evidence/(name+".log");log.write_text(p.stdout,encoding="utf-8")
    row={"id":name,"command":command,"actualExitCode":p.returncode,"expectedExitCodes":expected,
         "status":"PASS" if p.returncode in expected else "FAIL","scope":description,
         "log":log.name,"logSha256":hashlib.sha256(log.read_bytes()).hexdigest()}
    results.append(row)
    return p
python=sys.executable
run("stability-tests",[python,"-B","-X","utf8",str(__file__),"--offline-child","stability"],[0],"Offline Python regressions only")
run("release-guard-tests",[python,"-B","-X","utf8",str(__file__),"--offline-child","guard"],[0],"Offline synthetic ZIP/approval tests only")
dirty = bool(subprocess.check_output(["git", "-C", str(ROOT), "status", "--porcelain=v1", "--untracked-files=all"]).strip())
source=run("source-gate",[python,"-B","-X","utf8","scripts/release_gate.py","source","--baseline","Qingfeng_AI_Music/deploy/production-baseline.json"],[2 if dirty else 0],"Actual source provenance: dirty rejected, clean candidate must include observed deployed commit")
run("monitor-no-run",[python,"-B","-X","utf8","scripts/media_health_check.py"],[2],"Expected rejection before DB/config/HTTP access")
pwsh=shutil.which("pwsh")
if pwsh:
    parser="$failed=$false; @('scripts/build-local.ps1','scripts/start-main.ps1','scripts/start-local.ps1','Qingfeng_AI_Music/scripts/build.ps1') | ForEach-Object { $t=$null; $e=$null; [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path (Get-Location) $_),[ref]$t,[ref]$e); if($e.Count){$failed=$true} }; if($failed){exit 1}; Write-Output 'POWERSHELL_SYNTAX_PASS'"
    run("powershell-syntax",[pwsh,"-NoProfile","-NonInteractive","-Command",parser],[0],"Parser only")
    # Never run the wrapper if real start materials have appeared. No live startup is a test.
    runtime_manifest=ROOT/".secrets/production-main.manifest.json"
    if not runtime_manifest.exists():
        run("startup-wrapper",[pwsh,"-NoProfile","-NonInteractive","-File","scripts/start-main.ps1","-CheckOnly"],[1],"Expected block on missing local materials before SSH/gateway/process startup")
        run("compat-start-wrapper",[pwsh,"-NoProfile","-NonInteractive","-File","scripts/start-local.ps1","-CheckOnly"],[1],"Compatibility entry also blocks before production connections")
    else:
        results.append({"id":"startup-wrapper","status":"BLOCKED","scope":"Real runtime materials present; wrapper not executed"})
    if source.returncode==2:
        run("build-wrapper",[pwsh,"-NoProfile","-NonInteractive","-File","scripts/build-local.ps1","-JavaHome","D:/jdk17"],[1],"Expected block before dependency install/build or process stop")
    else:
        results.append({"id":"build-wrapper","status":"NOT_RUN","scope":"Clean source; full real build is a separate workflow, not a negative test"})
else:
    results.append({"id":"powershell-entry-tests","status":"BLOCKED","scope":"PowerShell not installed"})
spec=importlib.util.spec_from_file_location("release_gate",ROOT/"scripts/release_gate.py")
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
common=Path(gate.git(ROOT,"rev-parse","--path-format=absolute","--git-common-dir")).parent
yaml_jar=common/".cache/maven/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar"
jdk=Path(os.environ.get("QF_VERIFICATION_JAVA_HOME","D:/jdk17"))
if yaml_jar.is_file() and (jdk/"bin/javac.exe").is_file():
    classes=evidence/"yaml-classes";classes.mkdir()
    compilation=run("yaml-compile",[str(jdk/"bin/javac.exe"),"-encoding","UTF-8","-cp",str(yaml_jar),"-d",str(classes),"scripts/CheckStartupYaml.java"],[0],"Compile parser probe only")
    if compilation.returncode==0:
        run("yaml-config",[str(jdk/"bin/java.exe"),"-cp",str(classes)+os.pathsep+str(yaml_jar),"CheckStartupYaml",str(ROOT)],[0],"Parse repository YAML only; never start Spring or connect to DB")
else:
    results.append({"id":"yaml-config","status":"BLOCKED","scope":"Cached parser/JDK unavailable; no download attempted"})
for alias, relative in [("cached-audit-jar",".cache/production-audit/production.jar"),
                         ("cached-old-runtime",".cache/runtime/production-main/app.jar")]:
    path=common/relative
    if not path.exists():
        results.append({"id":alias,"status":"BLOCKED","scope":"Local cached artifact unavailable"})
        continue
    try:
        gate.inspect_artifact(path)
        results.append({"id":alias,"status":"FAIL","scope":"Previously untrusted artifact was unexpectedly accepted"})
    except gate.GateError as ex:
        results.append({"id":alias,"status":"PASS","scope":"Expected local cached artifact rejection; no server claim","reason":str(ex)})
files=set(gate.git(ROOT,"diff","--name-only").splitlines())|set(gate.git(ROOT,"ls-files","-o","--exclude-standard").splitlines())
fingerprints={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in sorted(files) if (ROOT/name).is_file()}
summary={"timestamp":datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
 "results":results,"sourceFingerprints":fingerprints,"productionConnections":0,"applicationStarted":False,
 "fullNpmMavenBuild":"SEPARATE_WORKFLOW","realMySQLSandboxOssCdnAcceptance":"BLOCKED",
 "productionReleaseAllowed":False}
(evidence/"summary.json").write_text(json.dumps(summary,indent=2,ensure_ascii=False),encoding="utf-8")
print(json.dumps({"evidence":str(evidence),"checks":len(results),"failed":sum(x["status"]=="FAIL" for x in results),
 "blocked":sum(x["status"]=="BLOCKED" for x in results),"productionReleaseAllowed":False},ensure_ascii=True))
raise SystemExit(1 if any(r["status"]=="FAIL" for r in results) else 0)
