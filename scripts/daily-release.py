"""Build a frozen origin/main and optionally deploy it. See daily-release.md."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
SSH_OPTIONS = ["-oBatchMode=yes", "-oStrictHostKeyChecking=yes", "-oConnectTimeout=15"]


def output(*args, **kwargs):
    return subprocess.check_output(args, cwd=ROOT, text=True, **kwargs).strip()


def release(args):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.@-]*", args.host):
        raise ValueError("Expected an SSH config host alias")
    ssh = ["ssh", *SSH_OPTIONS, args.host]
    os.environ["GIT_TERMINAL_PROMPT"] = "0"
    output("git", "fetch", "--all", "--prune")
    candidate = output("git", "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")
    live = output(*ssh, "unzip -p /opt/qingfeng-music/current/app.jar BOOT-INF/classes/release-commit.txt")
    if not all(re.fullmatch("[0-9a-f]{40}", sha) for sha in (candidate, live)):
        raise ValueError("Missing release commit metadata")
    print(f"live={live} candidate={candidate}", flush=True)
    if candidate == live:
        print("Already current")
        return
    output("git", "merge-base", "--is-ancestor", live, candidate)
    migrations = output("git", "diff", "--name-only", live, candidate, "--", ":(glob)**/db/migration/**")
    if migrations:
        raise ValueError("Database migration changed; use the migration release procedure")
    with tempfile.TemporaryDirectory(prefix="qfr-") as scratch:
        work = Path(scratch) / "src"
        baseline = Path(scratch) / "baseline.json"
        baseline.write_text(json.dumps({"sourceCommit": live}), encoding="utf-8")
        output("git", "worktree", "add", "--detach", str(work), candidate)
        try:
            command = [sys.executable, str(work / "scripts/release_gate.py"), "build",
                       "--workspace", str(work), "--baseline", str(baseline),
                       "--cache-root", str(Path(args.cache_root).resolve()), "--java-home", args.java_home]
            if args.cohort:
                command.extend(["--isolated-mysql-cohort", args.cohort])
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(result.stdout.strip() or "Release build failed")
            package = Path(json.loads(result.stdout)["result"]["package"])
            jar = package / "app.jar"
            with jar.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            print(f"Verified {jar} sha256={digest}", flush=True)
        finally:
            output("git", "worktree", "remove", str(work))
    if args.publish:
        stage = output(*ssh, "mktemp -d /tmp/qf-release.XXXXXXXX")
        if not re.fullmatch(r"/tmp/qf-release\.[a-zA-Z0-9]{8}", stage):
            raise ValueError("Unexpected staging directory")
        try:
            subprocess.run(["scp", *SSH_OPTIONS, str(jar), f"{args.host}:{stage}/app.jar"], check=True)
            worker = (ROOT / "scripts/daily-cutover.sh").read_text(encoding="utf-8")
            subprocess.run([*ssh, f"sudo -n bash -s -- {stage} {live} {candidate} {digest}"],
                           input=worker.encode(), check=True)
        finally:
            subprocess.run([*ssh, f"rm -f {stage}/app.jar && rmdir {stage}"], check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("host", "java-home", "cache-root"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--cohort")
    parser.add_argument("--publish", action="store_true")
    options = parser.parse_args()
    logs = Path(os.environ["LOCALAPPDATA"]) / "QingfengMusic" / "release-logs"
    logs.mkdir(parents=True, exist_ok=True)
    log = logs / (datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".log")
    print(log, flush=True)
    with log.open("w", encoding="utf-8") as stream:
        os.dup2(stream.fileno(), 1)
        os.dup2(stream.fileno(), 2)
        release(options)
