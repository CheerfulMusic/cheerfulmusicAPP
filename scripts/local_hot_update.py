"""Build committed local main on D:, then ask the tracked localhost services to reload."""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

WEB = "Qingfeng_AI_Music_VUE"
API = "Qingfeng_AI_Music"
QUEUE_MIGRATION = "V59__ai_capacity_queue.sql"
QUEUE_MIGRATION_HASH = "9b26469a2be6556b1065a4f89204870caf9655bf6c1cbcfa6ccb8b2806dc99a1"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path, data):
    tmp = path.with_name(path.name + f".{os.getpid()}.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def sha(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


@contextmanager
def build_lock(cache):
    # The live services and the build cache use separate locks so a failed compile never interrupts playback.
    with (cache / 'build.lock').open('a+b') as handle:
        if not handle.tell():
            handle.write(b'0')
            handle.flush()
        handle.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield  # Closing the handle also releases the lock if the watcher is stopped.


def git(root, *args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", "-C", str(root), *args],
                                  encoding="utf-8", errors="replace").strip()


def tree(root, ref, name):
    return git(root, "rev-parse", ref + ":" + name)


def checkout(root, slot, commit):
    common = Path(git(root, "rev-parse", "--path-format=absolute", "--git-common-dir")).resolve()
    if not slot.exists():
        git(root, "worktree", "add", "--quiet", "--detach", "--no-checkout", str(slot), commit)
        git(slot, "sparse-checkout", "set", "--no-cone", "/*")
        git(slot, "checkout", "--quiet", "--detach", commit)
    if (not (slot / ".git").is_file() or Path(git(slot, "rev-parse", "--show-toplevel")).resolve() != slot
            or Path(git(slot, "rev-parse", "--path-format=absolute", "--git-common-dir")).resolve() != common
            or git(slot, "branch", "--show-current") or git(slot, "status", "--porcelain", "--untracked-files=normal")):
        raise ValueError("Local build worktree is not clean and detached: " + str(slot))
    git(slot, "checkout", "--quiet", "--detach", commit)
    if git(slot, "rev-parse", "HEAD") != commit:
        raise ValueError("Checkout did not reach requested commit")


def compatible_migrations(baseline, candidate):
    deployed = baseline["migrations"]
    built = candidate["migrations"]
    if any(built.get(name) != digest for name, digest in deployed.items()):
        raise ValueError("Installed regional migration changed; keeping the current local services")
    additions = set(built) - set(deployed)
    # The V59 capacity module and its startup check are disabled in the local launch arguments.
    if additions - {QUEUE_MIGRATION}:
        raise ValueError("Local update needs a newer database schema: " + ", ".join(sorted(additions)))
    if QUEUE_MIGRATION in additions and candidate["migrationContract"]["normalizedSha256"].get(QUEUE_MIGRATION) != QUEUE_MIGRATION_HASH:
        raise ValueError("The optional queue migration differs from the verified local compatibility case")


def dependencies(root, ref):
    return tuple(tree(root, ref, p) for p in (".node-toolchain.json", WEB + "/package.json", WEB + "/package-lock.json"))


def build_env(config, cache, slot):
    pin = read(slot / ".node-toolchain.json")
    toolchain = Path(config["nodeToolchains"]) / pin["archiveRoot"]
    node = toolchain / "node.exe"
    if sha(node).lower() != pin["nodeExeSha256"].lower():
        raise ValueError("Node executable does not match this commit's toolchain")
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith(
        ("SPRING_", "QINGFENG_", "MYSQL_", "P0_", "QF_", "VITE_", "KIE_", "SUNO_", "ARK_", "DASHSCOPE_", "ALIYUN_"))
        and k.upper() not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
    temp = cache / "tmp"
    temp.mkdir(exist_ok=True)
    env.update(PATH=str(toolchain) + os.pathsep + str(Path(config["javaHome"]) / "bin") + os.pathsep + env["PATH"],
               JAVA_HOME=config["javaHome"], TMP=str(temp), TEMP=str(temp), SPRING_FLYWAY_TARGET="44",
               JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8")
    return node, env


def run(command, cwd, env):
    with Path(env["QF_LOCAL_BUILD_LOG"]).open("ab") as log:
        subprocess.run([str(v) for v in command], cwd=cwd, env=env, check=True, timeout=1200, stdout=log, stderr=subprocess.STDOUT)


def get_cache(root, config):
    cache = Path(config["cacheRoot"]).resolve()
    if cache.drive.upper() != "D:" or cache == Path(cache.anchor):
        raise ValueError("Local build cache must be a dedicated directory on D:")
    cache.mkdir(parents=True, exist_ok=True)
    marker = cache / "workspace.json"
    owner = str(root.resolve())
    if not marker.exists():
        if any(cache.iterdir()):
            raise ValueError("Local cache belongs to another task")
        write(marker, {"workspace": owner})
    if read(marker).get("workspace") != owner:
        raise ValueError("Local cache workspace mismatch")
    return cache


def prepare(root, config, cache, state, commit):
    manifest = read(root / ".secrets/production-main.manifest.json")
    baseline_jar = root / ".cache/runtime/production-main/app.jar"
    baseline_path = baseline_jar.with_name("release-contract.json")
    if (manifest.get("localDevelopmentFeatures") is not True
            or manifest.get("schemaCompatibility", {}).get("status") != "VERIFIED"
            or manifest["schemaCompatibility"].get("jarSha256") != manifest.get("jarSha256")
            or sha(baseline_path) != manifest.get("artifactContractSha256")):
        raise ValueError("Verified daily runtime contract is required for a local update")
    baseline = read(baseline_path)
    if baseline["jarSha256"] != manifest["jarSha256"] or baseline["sourceCommit"] != manifest["sourceCommit"]:
        raise ValueError("Regional source or artifact pin changed")
    old = state["sourceCommit"]
    active_web = Path(state["frontendRoot"]).resolve()
    active_ref = git(active_web, "rev-parse", "HEAD")
    managed = active_web in [(cache / s / WEB) for s in ("slot-a", "slot-b")]
    new_api = tree(root, old, API) != tree(root, commit, API)
    new_web = tree(root, active_ref, WEB) != tree(root, commit, WEB) or not managed
    same_deps = dependencies(root, active_ref) == dependencies(root, commit)
    if not same_deps:
        new_web = True
    plan = {"commit": commit, "runId": state["runId"], "newBackend": new_api, "newFrontend": new_web,
            "activeFrontend": str(active_web), "previousWebCommit": active_ref, "previousBackend": old,
            "baselineSha256": baseline["jarSha256"]}
    if not new_api and not new_web:
        return plan
    slot = cache / ("slot-b" if active_web == cache / "slot-a" / WEB else "slot-a")
    checkout(root, slot, commit)
    if tree(root, manifest["sourceCommit"], API + "/qf-infrastructure/src/main/resources/db/optional") != tree(root, commit, API + "/qf-infrastructure/src/main/resources/db/optional"):
        raise ValueError("Optional database schema requires compatibility review")
    node, env = build_env(config, cache, slot)
    build_log = cache / "build.log"
    build_log.write_text("Local build for " + commit + "\n", encoding="utf-8")
    env["QF_LOCAL_BUILD_LOG"] = str(build_log)
    web = slot / WEB
    npm = node.with_name("npm.cmd")
    stamp = web / "node_modules/.qf-local-dependencies"
    fingerprint = "\n".join(dependencies(root, commit))
    if not stamp.is_file() or stamp.read_text(encoding="ascii") != fingerprint or not (web / "node_modules/vite/bin/vite.js").is_file():
        run([npm, "ci", "--prefer-offline", "--no-fund", "--no-audit", "--cache", config["npmCache"]], web, env)
        stamp.write_text(fingerprint, encoding="ascii")
    run([npm, "run", "build"], web, env)
    if new_web:
        run([npm, "test", "--", "--maxWorkers=4", "--testTimeout=15000"], web, env)
        plan.update(frontendRoot=str(active_web if managed and same_deps else web),
                    hmr=managed and same_deps, node=str(node))
    if new_api:
        run([shutil.which("mvn.cmd", path=env["PATH"]), "-B", "-ntp", "-Dmaven.repo.local=" + config["mavenRepo"],
             "-Drelease.commit=" + commit, "-Dproject.build.outputTimestamp=" + git(slot, "show", "-s", "--format=%ct", commit),
             "-Dtest=AgentChatFlowIntegrationTest,AgentChatServiceTest,AgentPlanServiceTest,RegionalReadConfigurationTest",
             "-Dsurefire.failIfNoSpecifiedTests=false", "clean", "package"], slot / API, env)
        jars = list((slot / API / "qf-server/target").glob("qf-server-*.jar"))
        if len(jars) != 1:
            raise ValueError("Expected one Spring Boot JAR")
        spec = importlib.util.spec_from_file_location("local_release_gate", slot / "scripts/release_gate.py")
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        actual = gate.check_build_output(slot, {"sourceCommit": commit}, gate.inspect_artifact(jars[0]))
        compatible_migrations(baseline, actual)
        jars_dir = cache / "jars"
        jars_dir.mkdir(exist_ok=True)
        destination = jars_dir / ("b.jar" if state.get("localJar") == str(jars_dir / "a.jar") else "a.jar")
        if destination.resolve() == Path(state.get("localJar") or baseline_jar).resolve():
            raise ValueError("Refusing to overwrite the running JAR")
        temp = destination.with_suffix(".tmp")
        shutil.copyfile(jars[0], temp)
        if sha(temp) != actual["jarSha256"]:
            raise ValueError("Staged JAR differs from build output")
        os.replace(temp, destination)
        plan.update(jar=str(destination), jarSha256=actual["jarSha256"])
    return plan


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("watch", "prepare"))
    parser.add_argument("workspace", type=Path)
    args = parser.parse_args()
    root = args.workspace.resolve()
    config = read(root / ".secrets/local-hot-update.json")
    cache = get_cache(root, config)
    state_path = root / ".cache/runtime/local.json"
    run_id = read(state_path)["runId"]
    status_path = cache / "status.json"
    last_good = None
    last_failed = None
    retry_at = 0
    while state_path.is_file():
        state = read(state_path)
        if state.get("runId") != run_id or state.get("mode") != "production-main":
            return
        commit = git(root, "rev-parse", "--verify", "refs/heads/main^{commit}")
        if args.mode == "watch" and (commit == last_good or (commit == last_failed and time.monotonic() < retry_at)):
            time.sleep(3)
            continue
        try:
            with build_lock(cache):
                state = read(state_path)
                if state.get("runId") != run_id:
                    return
                write(status_path, {"state": "building", "commit": commit})
                plan = prepare(root, config, cache, state, commit)
                if git(root, "rev-parse", "refs/heads/main") != commit:
                    continue
                write(cache / "plan.json", plan)
                if args.mode == "prepare":
                    print("Local package prepared", commit, flush=True)
                    return
                subprocess.run([shutil.which("pwsh"), "-NoProfile", "-NonInteractive", "-File",
                                str(root / "scripts/sync-local-main.ps1"), "-Apply"], check=True, timeout=300)
            last_good = commit
            last_failed = None
            current = read(state_path)
            write(status_path, {"state": "active", "mainCommit": commit, "backendCommit": current["sourceCommit"],
                                "jarSha256": current["jarSha256"], "frontendRoot": current["frontendRoot"]})
            print("Local main active", commit, flush=True)
        except (OSError, subprocess.SubprocessError, ValueError, RuntimeError) as exc:
            write(status_path, {"state": "failed", "commit": commit, "error": str(exc)})
            if args.mode == "prepare":
                raise
            print("Local update failed:", exc, flush=True)
            last_failed, retry_at = commit, time.monotonic() + 60


if __name__ == "__main__":
    main()
