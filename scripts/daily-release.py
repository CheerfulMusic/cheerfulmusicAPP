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
    result = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, text=True, encoding="utf-8", **kwargs)
    if result.returncode:
        print(result.stdout[-4000:], flush=True)
        result.check_returncode()
    return result.stdout.strip()


def remote(helper, script):
    return output(sys.executable, helper, "new", input=script)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def merged_commit(source, local, upstream):
    """Prepare the merge without changing an index or a developer's working files."""
    base = output("git", "merge-base", local, upstream, cwd=source)
    if base == local:
        return upstream
    if base == upstream:
        return local
    tree = output("git", "merge-tree", "--write-tree", local, upstream, cwd=source)
    return output("git", "commit-tree", tree, "-p", local, "-p", upstream,
                  "-m", "Merge origin/main for daily release", cwd=source)


def check_main(source, expected):
    if (output("git", "symbolic-ref", "HEAD", cwd=source) != "refs/heads/main"
            or output("git", "rev-parse", "HEAD", cwd=source) != expected):
        raise RuntimeError("Local main moved; retry with the new commits")


def remote_head(source):
    line = output("git", "ls-remote", "--exit-code", "origin", "refs/heads/main", cwd=source)
    return line.split()[0]


def sync_and_push(source, local, upstream, candidate):
    check_main(source, local)
    if remote_head(source) != upstream:
        raise RuntimeError("Remote main moved during verification; retry the merge and tests")
    if local != candidate:
        output("git", "-c", "merge.autoStash=false", "merge", "--ff-only", "--no-autostash",
               candidate, cwd=source)
    check_main(source, candidate)
    if upstream != candidate:
        # Ordinary non-force push also rejects a concurrent divergent update.
        output("git", "push", "origin", candidate + ":refs/heads/main", cwd=source)
    if remote_head(source) != candidate:
        raise RuntimeError("Remote main does not match the verified candidate")
    print("Synchronized local and remote main:", candidate, flush=True)


def checkout_candidate(source, repo, candidate):
    # A separate index and build cache on D; keep ancestry from the shallow partial clone.
    output("git", "init", "--quiet", str(repo))
    objects = output("git", "rev-parse", "--path-format=absolute", "--git-path", "objects", cwd=source)
    (repo / ".git/objects/info/alternates").write_text(Path(objects).as_posix() + "\n", encoding="utf-8")
    shallow = Path(output("git", "rev-parse", "--path-format=absolute", "--git-path", "shallow", cwd=source))
    if shallow.is_file():
        shutil.copyfile(shallow, repo / ".git/shallow")
    url = output("git", "remote", "get-url", "origin", cwd=source)
    output("git", "remote", "add", "origin", url, cwd=repo)
    output("git", "config", "remote.origin.promisor", "true", cwd=repo)
    output("git", "config", "remote.origin.partialclonefilter", "blob:none", cwd=repo)
    output("git", "-c", "advice.detachedHead=false", "checkout", "--detach", candidate, cwd=repo)


def main(args):
    os.environ["GIT_TERMINAL_PROMPT"] = "0"
    cache = Path(args.cache_root).resolve()
    helper = cache / "cn-move-20261118/exec_checked.py"
    source = Path(args.source_main).resolve(strict=True)
    local = output("git", "rev-parse", "refs/heads/main", cwd=source)
    check_main(source, local)
    if source == ROOT:
        raise ValueError("Configure the developer main worktree, distinct from the release runner")
    if output("git", "remote", "get-url", "origin", cwd=source) != output("git", "remote", "get-url", "origin"):
        raise ValueError("Developer and release repositories must share origin")
    proxy = subprocess.run(["git", "config", "--get", "http.https://github.com.proxy"],
                           cwd=ROOT, capture_output=True, text=True)
    if proxy.returncode == 0:
        count = int(os.environ.get("GIT_CONFIG_COUNT", "0"))
        os.environ.update({f"GIT_CONFIG_KEY_{count}": "http.https://github.com.proxy",
                           f"GIT_CONFIG_VALUE_{count}": proxy.stdout.strip(), "GIT_CONFIG_COUNT": str(count+1)})
    output("git", "fetch", "--all", "--prune", cwd=source)
    upstream = output("git", "rev-parse", "refs/remotes/origin/main^{commit}", cwd=source)
    candidate = merged_commit(source, local, upstream)
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
    print(f"Shenzhen live={live} local={local} origin/main={upstream} candidate={candidate}", flush=True)
    if candidate == live == upstream and not args.verify_current:
        if args.publish and local != candidate:
            sync_and_push(source, local, upstream, candidate)
        print("Already current")
        return
    output("git", "merge-base", "--is-ancestor", live, candidate, cwd=source)
    if output("git","diff","--name-only",live,candidate,"--",":(glob)**/db/migration/**",cwd=source):
        raise ValueError("Migration changes require a migration release")
    scratch = Path(args.scratch).resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=scratch) as temp:
        repo = Path(temp) / "src"
        checkout_candidate(source, repo, candidate)
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
        if not args.publish:
            return
        sync_and_push(source, local, upstream, candidate)
        if live == candidate:
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
        if remote_head(source) != candidate:
            raise RuntimeError("Remote main changed after upload; leaving production untouched")
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
    for key in ("cache-root","java-home","scratch","source-main"):
        parser.add_argument("--"+key,required=True)
    parser.add_argument("--verify-current",action="store_true")
    parser.add_argument("--publish",action="store_true")
    parser.add_argument("--log-attached", action="store_true", help=argparse.SUPPRESS)
    options = parser.parse_args()
    runtime = Path(options.scratch).resolve()
    temp_root = runtime / "temp"
    temp_root.mkdir(parents=True, exist_ok=True)
    for key in ("TEMP", "TMP", "TMPDIR"):
        os.environ[key] = str(temp_root)
    tempfile.tempdir = str(temp_root)
    if options.log_attached:
        main(options)
    else:
        logs = runtime / "release-logs"
        logs.mkdir(parents=True, exist_ok=True)
        log = logs / (datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".log")
        with log.open("wb") as stream:
            result = subprocess.run(
                [sys.executable, "-u", __file__, *sys.argv[1:], "--log-attached"],
                stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            )
        sys.exit(result.returncode)
