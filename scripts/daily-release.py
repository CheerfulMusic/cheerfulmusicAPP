"""Daily Shenzhen release. Omit --publish for a production-read-only rehearsal."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
from datetime import datetime

from daily_fixtures import cohort

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
INSTANCE = "i-wz9ackvz6v9qvyuzvsgi"


def output(*args, cwd=ROOT, **kwargs):
    result = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, text=True, **kwargs)
    if result.returncode:
        print(result.stdout[-4000:], flush=True)
        result.check_returncode()
    return result.stdout.strip()


def remote(helper, script):
    return output(sys.executable, helper, "new", input=script)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main(args):
    os.environ["GIT_TERMINAL_PROMPT"] = "0"
    cache = Path(args.cache_root).resolve()
    helper = cache / "cn-move-20261118/exec_checked.py"
    output("git", "fetch", "--all", "--prune")
    candidate = output("git", "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")
    old, live, checksum = remote(helper, """
cd /opt/qingfeng-music/current
pwd -P
cat source-commit.txt
echo
sha256sum app.jar | cut -d' ' -f1
""").split()
    if not (re.fullmatch("/opt/qingfeng-music/releases/[a-zA-Z0-9._-]+", old)
            and all(re.fullmatch("[a-f0-9]{40}", x) for x in (live,candidate))
            and re.fullmatch("[a-f0-9]{64}", checksum)):
        raise ValueError("Invalid production baseline")
    print(f"Shenzhen live={live} origin/main={candidate}", flush=True)
    if candidate == live and not args.verify_current:
        print("Already current")
        return
    output("git", "merge-base", "--is-ancestor", live, candidate)
    if output("git","diff","--name-only",live,candidate,"--",":(glob)**/db/migration/**"):
        raise ValueError("Migration changes require a migration release")
    scratch = Path(args.scratch).resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=scratch) as temp:
        repo = Path(temp) / "src"
        output("git","init","--quiet",str(repo))
        output("git","fetch","--no-tags","--update-shallow",str(ROOT),candidate,cwd=repo)
        output("git","-c","advice.detachedHead=false","checkout","--detach",candidate,cwd=repo)
        deps = repo / ".cache"
        deps.mkdir(exist_ok=True)
        for name in ("npm","maven","toolchains","payment-mysql-8046-20261111"):
            target = (cache / name).resolve(strict=True)
            output("powershell.exe","-NoProfile","-NonInteractive","-Command",
                   "New-Item -ItemType Junction -Path $env:QF_LINK -Target $env:QF_TARGET | Out-Null; if (!$?) {exit 1}",
                   env={**os.environ,"QF_LINK":str(deps/name),"QF_TARGET":str(target)})
        baseline = Path(temp) / "baseline.json"
        baseline.write_text(json.dumps({"sourceCommit":live}))
        mysql = deps / "payment-mysql-8046-20261111/mysql-8.0.46-winx64/bin/mysqld.exe"
        try:
            with cohort(deps, mysql) as manifest:
                result = json.loads(output(sys.executable,repo/"scripts/release_gate.py","build",
                    "--workspace",repo,"--baseline",baseline,"--cache-root",deps,
                    "--java-home",args.java_home,"--isolated-mysql-cohort",manifest))
        except Exception:
            evidence = Path(args.scratch).resolve() / "release-logs" / Path(temp).name
            evidence.mkdir(parents=True, exist_ok=True)
            for entry in deps.glob("b/*/*.log"):
                shutil.copy2(entry, evidence / entry.name)
            print(f"Build logs: {evidence}", flush=True)
            raise
        package = Path(result["result"]["package"])
        contract = package/"release-contract.json"
        jar = package/"app.jar"
        plan = dict(sourceCommit=candidate,baselineRelease=old,baselineCommit=live,baselineJarSha256=checksum,
                    jarSha256=digest(jar),contractSha256=digest(contract))
        if (plan["contractSha256"] != result["result"]["contractSha256"]
                or json.loads(contract.read_text())["jarSha256"] != plan["jarSha256"]):
            raise RuntimeError("Package differs from verified build")
        print("Build verified",flush=True)
        if not args.publish or live == candidate:
            return
        tag = "daily-"+candidate[:12]+"-"+uuid.uuid4().hex[:8]
        stage = "/srv/qingfeng-data/qingfeng-release-staging/"+tag
        dest = "/tmp/"+tag+".tgz"
        planfile = Path(temp)/"plan.json"
        planfile.write_text(json.dumps(plan))
        bundle = Path(temp)/"bundle.tgz"
        with tarfile.open(bundle,"w:gz",compresslevel=1) as tar:
            for path in [jar,contract,package/"source-commit.txt",planfile,SCRIPTS/"daily-server-release.py"]:
                tar.add(path,arcname=path.name)
        workbench = Path(os.environ["LOCALAPPDATA"])/"Programs/workbench/workbench.exe"
        output(workbench,"upload",bundle,dest,"-i",INSTANCE,"-r","cn-shenzhen","-o","json")
        remote(helper, f"""umask 077
echo '{digest(bundle)}  {dest}' | sha256sum -c -
mkdir -m 700 {stage}
tar -xzf {dest} -C {stage}
rm -f {dest}
systemd-run --unit=qf-{tag} --property=UMask=0077 /usr/bin/python3.11 {stage}/daily-server-release.py
""")
        for _ in range(150):
            receipt = remote(helper, f"if test -f {stage}/result.json; then cat {stage}/result.json; else echo '{{}}'; fi")
            state = json.loads(receipt).get("state")
            if state == "COMPLETE":
                print(receipt)
                return
            if state in ("PRECHECK_FAILED","ROLLED_BACK","ROLLBACK_FAILED"):
                raise RuntimeError(receipt)
            time.sleep(8)
        raise RuntimeError(f"Check server receipt {stage}/result.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for key in ("cache-root","java-home","scratch"):
        parser.add_argument("--"+key,required=True)
    parser.add_argument("--verify-current",action="store_true")
    parser.add_argument("--publish",action="store_true")
    options = parser.parse_args()
    runtime = Path(options.scratch).resolve()
    temp_root = runtime / "temp"
    temp_root.mkdir(parents=True, exist_ok=True)
    for key in ("TEMP", "TMP", "TMPDIR"):
        os.environ[key] = str(temp_root)
    tempfile.tempdir = str(temp_root)
    logs = runtime / "release-logs"
    logs.mkdir(parents=True,exist_ok=True)
    log = logs/(datetime.now().strftime("%Y%m%d-%H%M%S-%f")+".log")
    print(log,flush=True)
    with log.open("w",encoding="utf-8") as stream:
        os.dup2(stream.fileno(),1)
        os.dup2(stream.fileno(),2)
        main(options)
