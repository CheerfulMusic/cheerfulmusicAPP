#!/usr/bin/env python3
"""Offline build/artifact/startup gate. Never connects to production or approves releases."""
import argparse
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import zipfile

HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")

class GateError(ValueError):
    pass

def sha(data):
    return hashlib.sha256(data).hexdigest()

def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")

def load_json(path):
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise GateError("Gate JSON must be an object")
        return value
    except (OSError, ValueError):
        raise GateError("Missing or invalid JSON gate material") from None

def git(workspace, *args):
    p = subprocess.run(["git", "-C", str(workspace), *args], stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, check=False)
    if p.returncode:
        raise GateError("Git provenance check failed")
    return p.stdout.decode("utf-8").strip()

def check_source(workspace, baseline):
    workspace = Path(workspace).resolve()
    base = load_json(baseline)
    deployed = base.get("sourceCommit", "")
    if not HEX40.fullmatch(deployed):
        raise GateError("Unknown production source baseline")
    head = git(workspace, "rev-parse", "HEAD")
    if not HEX40.fullmatch(head):
        raise GateError("Unknown candidate commit")
    if git(workspace, "status", "--porcelain=v1", "--untracked-files=all"):
        raise GateError("Dirty source: preserve changes; commit/review separately before a trusted build")
    git(workspace, "merge-base", "--is-ancestor", deployed, head)
    return {"sourceCommit": head, "baselineCommit": deployed,
            "frontendSourceTree": git(workspace, "rev-parse", head + ":Qingfeng_AI_Music_VUE")}

def checked_zip(data):
    """Read CRCs and reject duplicate/unsafe members, including nested project JARs."""
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
        names = z.namelist()
        if len(names) != len(set(names)):
            raise GateError("Duplicate archive member")
        if sum(e.file_size for e in z.infolist()) > 512 * 1024 * 1024:
            raise GateError("Archive exceeds verification budget")
        for name in names:
            if name.startswith(("/", "\\")) or "\\" in name or ".." in name.split("/"):
                raise GateError("Unsafe archive member")
        if z.testzip() is not None:
            raise GateError("Archive CRC failure")
        return z
    except (zipfile.BadZipFile, OSError, RuntimeError, EOFError):
        raise GateError("Unreadable or truncated archive") from None

def inspect_artifact(jar):
    try:
        raw = Path(jar).read_bytes()
    except OSError:
        raise GateError("Artifact missing") from None
    with checked_zip(raw) as z:
        names = z.namelist()
        try:
            source = z.read("BOOT-INF/classes/release-commit.txt").decode("utf-8").strip()
            z.read("BOOT-INF/classes/static/index.html")
            executable = z.read("META-INF/MANIFEST.MF").decode("utf-8")
        except (KeyError, UnicodeError):
            raise GateError("Missing source, frontend or executable manifest") from None
        if not HEX40.fullmatch(source) or "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in executable:
            raise GateError("Invalid embedded source or executable manifest")
        for config_name in ("application.yml", "application-mysql.yml"):
            try:
                config = z.read("BOOT-INF/classes/" + config_name).decode("utf-8")
            except (KeyError, UnicodeError):
                raise GateError("Missing explicit configuration contract") from None
            if any(re.search(r"file\s*:", line.split("#", 1)[0], re.IGNORECASE) for line in config.splitlines()):
                raise GateError("Implicit external configuration import forbidden")
        static = {n.removeprefix("BOOT-INF/classes/static/"): sha(z.read(n))
                  for n in sorted(names) if n.startswith("BOOT-INF/classes/static/") and not n.endswith("/")}
        sql = {}
        def collect(container):
            for n in container.namelist():
                if (n.startswith("db/migration/V") or "/db/migration/V" in n) and n.endswith(".sql"):
                    name = n.rsplit("/", 1)[-1]
                    if not re.fullmatch(r"V[1-9][0-9]*__[A-Za-z0-9_.-]+\.sql", name):
                        raise GateError("Invalid migration name")
                    if name in sql or any(k.split("__")[0] == name.split("__")[0] for k in sql):
                        raise GateError("Duplicate migration version")
                    sql[name] = sha(container.read(n))
        collect(z)
        libraries = [n for n in names if n.startswith("BOOT-INF/lib/qf-infrastructure-") and n.endswith(".jar")]
        if len(libraries) > 1:
            raise GateError("Multiple infrastructure libraries")
        for name in libraries:
            with checked_zip(z.read(name)) as nested:
                collect(nested)
        versions = sorted(int(n.split("__")[0][1:]) for n in sql)
        if not versions or versions != list(range(1, versions[-1] + 1)):
            raise GateError("Missing or noncontiguous migration chain")
        preview = "BOOT-INF/classes/static/preview-media-manifest.json"
        try:
            manifest = json.loads(z.read(preview))
            assets = manifest["assets"]
            if manifest.get("version") != 1 or not assets:
                raise GateError("Invalid preview manifest")
            declared = set()
            for asset in assets:
                path = asset["path"]
                if not path.startswith("/media/") or ".." in path.split("/") or path in declared:
                    raise GateError("Invalid or duplicate preview asset")
                declared.add(path)
                body = z.read("BOOT-INF/classes/static" + path)
                if not body or len(body) != asset["bytes"] or sha(body) != asset["sha256"]:
                    raise GateError("Preview content mismatch")
        except (KeyError, ValueError, TypeError):
            raise GateError("Missing or invalid preview content") from None
    return {"jarSha256": sha(raw), "sourceCommit": source,
            "frontendSha256": sha(json_bytes(static)), "migrations": sql}



def check_node_toolchain(cache_root, pin):
    root = Path(cache_root) / "toolchains"
    archive = root / pin["archiveName"]
    if not archive.is_file() or sha(archive.read_bytes()) != pin["archiveSha256"]:
        raise GateError("Pinned official Node archive missing or altered")
    home = root / pin["archiveRoot"]
    if not home.is_dir():
        raise GateError("Pinned project-local Node runtime missing")
    with checked_zip(archive.read_bytes()) as z:
        for name in z.namelist():
            if name.endswith("/"):
                continue
            target = root / name
            if not target.is_file() or sha(target.read_bytes()) != sha(z.read(name)):
                raise GateError("Node/npm toolchain differs from pinned official archive")
    node = home / "node.exe"
    if not node.is_file() or sha(node.read_bytes()) != pin["nodeExeSha256"]:
        raise GateError("Pinned Node executable checksum mismatch")
    npm = home / "npm.cmd"
    if not npm.is_file():
        raise GateError("Pinned npm launcher missing")
    return node, npm, pin["version"]


def check_build_output(export, source, actual):
    """Bind packaged SQL/frontend bytes to the frozen source and this build's dist."""
    export = Path(export)
    sql_root = export / "Qingfeng_AI_Music/qf-infrastructure/src/main/resources/db/migration"
    expected_sql = {p.name: sha(p.read_bytes()) for p in sorted(sql_root.glob("V*__*.sql"))}
    if not expected_sql or actual["migrations"] != expected_sql:
        raise GateError("Packaged migrations differ from frozen source")
    expected_static = {}
    for directory in (export / "Qingfeng_AI_Music/qf-server/src/main/resources/static",
                      export / "Qingfeng_AI_Music_VUE/dist"):
        if directory.exists():
            for path in directory.rglob("*"):
                if path.is_file():
                    if path.is_symlink():
                        raise GateError("Symlinked frontend output not accepted")
                    expected_static[path.relative_to(directory).as_posix()] = sha(path.read_bytes())
    if "index.html" not in expected_static or actual["frontendSha256"] != sha(json_bytes(expected_static)):
        raise GateError("Packaged frontend differs from this build output")
    if actual["sourceCommit"] != source["sourceCommit"]:
        raise GateError("Packaged source differs from frozen commit")
    return actual


def verify_contract(jar, contract_path, trusted_sha, expected_source):
    if not HEX64.fullmatch(trusted_sha or "") or not HEX40.fullmatch(expected_source or ""):
        raise GateError("Independent artifact approval pin required")
    try:
        raw = Path(contract_path).read_bytes()
    except OSError:
        raise GateError("Artifact contract missing") from None
    if sha(raw) != trusted_sha:
        raise GateError("Artifact contract differs from trusted approval")
    contract = load_json(contract_path)
    actual = inspect_artifact(jar)
    if contract.get("version") != 1 or actual["sourceCommit"] != expected_source:
        raise GateError("Contract or source version mismatch")
    for key in ("jarSha256", "sourceCommit", "frontendSha256", "migrations"):
        if contract.get(key) != actual[key]:
            raise GateError("Artifact does not match contract: " + key)
    checks = contract.get("localChecks", {})
    if set(checks) != {"frontendTest", "frontendBuild", "backendVerify"}:
        raise GateError("Required local checks missing")
    if any(v.get("exitCode") != 0 or not HEX64.fullmatch(v.get("logSha256", "")) for v in checks.values()):
        raise GateError("Required local check failed or lacks evidence")
    if not HEX40.fullmatch(contract.get("frontendSourceTree", "")):
        raise GateError("Frontend source provenance missing")
    return contract

def check_startup(workspace, manifest_path, jar):
    """Local files only. This does NOT grant permission to connect to production."""
    manifest = load_json(manifest_path)
    jar = Path(jar).resolve()
    contract = verify_contract(jar, jar.with_name("release-contract.json"),
                               manifest.get("artifactContractSha256", ""), manifest.get("sourceCommit", ""))
    if manifest.get("jarSha256") != contract["jarSha256"]:
        raise GateError("Runtime manifest artifact mismatch")
    schema = manifest.get("schemaCompatibility", {})
    if (schema.get("status") != "VERIFIED" or schema.get("jarSha256") != contract["jarSha256"]
            or not HEX64.fullmatch(schema.get("evidenceSha256", ""))):
        raise GateError("Schema compatibility UNKNOWN: startup blocked, do not repair migrations")
    frontend = manifest.get("frontendRoot")
    if not isinstance(frontend, str) or not Path(frontend).is_absolute() or not Path(frontend).is_dir():
        raise GateError("Frontend root missing")
    # Do not print or load runtime secrets.
    if git(frontend, "rev-parse", "HEAD:Qingfeng_AI_Music_VUE") != contract["frontendSourceTree"]:
        raise GateError("Runtime frontend source mismatch")
    prefix = git(frontend, "rev-parse", "--show-prefix").rstrip("/")
    if prefix != "Qingfeng_AI_Music_VUE":
        raise GateError("Runtime frontend directory invalid")
    if git(frontend, "status", "--porcelain=v1", "--untracked-files=all", "--", "."):
        raise GateError("Runtime frontend has unreviewed changes")
    return {"artifact": "VERIFIED", "schemaEvidence": "PINNED", "networkAccess": "NOT_PERFORMED",
            "businessHealth": "UNKNOWN", "productionRelease": "NOT_AUTHORIZED"}

def build(workspace, baseline, java_home, cache_root):
    """Export clean Git source and run offline commands without private files or inherited secrets."""
    workspace = Path(workspace).resolve()
    source = check_source(workspace, baseline)  # Must be first: no build/stop/install before this.
    if not java_home or not (Path(java_home) / "bin/java.exe").is_file():
        raise GateError("Explicit installed Java 17 JDK required")
    cache_root = Path(cache_root).resolve()
    common = Path(git(workspace, "rev-parse", "--path-format=absolute", "--git-common-dir")).parent
    if not cache_root.is_relative_to(common):
        raise GateError("Dependency cache must remain within this repository")
    node, npm, node_version = check_node_toolchain(cache_root, load_json(workspace / ".node-toolchain.json"))
    # Keep exports short on Windows; worktrees may already have deep paths.
    run = cache_root / "b" / uuid.uuid4().hex[:8]
    run.mkdir(parents=True, exist_ok=False)
    export = run / "source"
    export.mkdir()
    archive = subprocess.check_output(["git", "-C", str(workspace), "archive", "--format=zip", source["sourceCommit"]])
    with checked_zip(archive) as z:
        if os.name == "nt" and any(len(str(export / n)) >= 248 for n in z.namelist()):
            raise GateError("Export exceeds Windows path budget; use a shorter repository-local cache")
        z.extractall(export)  # all names validated above; new directory, no user files
    home = run / "home"; home.mkdir()
    empty = run / "empty-settings.xml"
    empty.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>', encoding="utf-8")
    npmrc = run / "empty.npmrc"; npmrc.write_text("", encoding="utf-8")
    global_npmrc = run / "empty-global.npmrc"; global_npmrc.write_text("", encoding="utf-8")
    env = {k: v for k, v in os.environ.items() if k.upper() in {"SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"}}
    env.update({"PATH": str(node.parent) + os.pathsep + os.environ.get("PATH", ""), "JAVA_HOME": str(Path(java_home).resolve()),
                "HOME": str(home), "USERPROFILE": str(home), "APPDATA": str(home), "LOCALAPPDATA": str(home),
                "TEMP": str(run), "TMP": str(run), "NPM_CONFIG_USERCONFIG": str(npmrc),
                "NPM_CONFIG_GLOBALCONFIG": str(global_npmrc), "NPM_CONFIG_ENGINE_STRICT": "true", "CI": "true"})
    logs = {}
    def execute(name, command, cwd):
        log = run / (name + ".log")
        with log.open("wb") as output:
            try:
                p = subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=1800)
            except (OSError, subprocess.TimeoutExpired):
                raise GateError("Build command unavailable or timed out: " + name) from None
        logs[name] = {"exitCode": p.returncode, "logSha256": sha(log.read_bytes())}
        if p.returncode:
            raise GateError("Build check failed: " + name + "; evidence retained in build directory")
    execute("javaVersion", [str(Path(java_home) / "bin/java.exe"), "-version"], export)
    if not re.search(r'version\s+"17(?:[."]|$)', (run / "javaVersion.log").read_text(encoding="utf-8", errors="replace")):
        raise GateError("Java toolchain must be version 17")
    execute("nodeVersion", [str(node), "--version"], export)
    if (run / "nodeVersion.log").read_text(encoding="utf-8").strip() != "v" + node_version:
        raise GateError("Node runtime version differs from source pin")
    frontend = export / "Qingfeng_AI_Music_VUE"
    npm = str(npm)
    mvn = shutil.which("mvn.cmd") or shutil.which("mvn")
    if not mvn:
        raise GateError("Maven required")
    execute("dependencies", [npm, "ci", "--offline", "--ignore-scripts", "--cache", str(cache_root / "npm")], frontend)
    execute("frontendTest", [npm, "test"], frontend)
    execute("frontendBuild", [npm, "run", "build"], frontend)
    metadata = export / "Qingfeng_AI_Music/deploy/release-metadata"
    metadata.mkdir(parents=True, exist_ok=True)
    (metadata / "release-commit.txt").write_text(source["sourceCommit"] + "\n", encoding="utf-8")
    epoch = git(workspace, "show", "-s", "--format=%ct", source["sourceCommit"])
    execute("backendVerify", [mvn, "-o", "-B", "-ntp", "--settings", str(empty),
              "-Dmaven.repo.local=" + str(cache_root / "maven"), "-Duser.home=" + str(home),
              "-Dproject.build.outputTimestamp=" + epoch, "clean", "verify"], export / "Qingfeng_AI_Music")
    artifact = export / "Qingfeng_AI_Music/qf-server/target/qf-server-0.1.0-SNAPSHOT.jar"
    package = run / "package"; package.mkdir()
    shutil.copyfile(artifact, package / "app.jar")
    actual = check_build_output(export, source, inspect_artifact(package / "app.jar"))
    contract = {"version": 1, **actual,
                "frontendSourceTree": source["frontendSourceTree"],
                "localChecks": {k: logs[k] for k in ("frontendTest", "frontendBuild", "backendVerify")},
                "toolchain": {"node": node_version, "nodeExeSha256": sha(node.read_bytes()), "java": "17"},
                "productionReady": False, "mysqlAcceptance": "BLOCKED", "migrationProvenance": "UNKNOWN"}
    (package / "release-contract.json").write_bytes(json_bytes(contract))
    (package / "source-commit.txt").write_text(source["sourceCommit"] + "\n", encoding="utf-8")
    return {"package": str(package), "localBuild": "VERIFIED", "contractSha256": sha(json_bytes(contract)),
            "productionRelease": "BLOCKED", "note": "Approval, migration provenance and real business acceptance remain required"}

def main():
    p = argparse.ArgumentParser()
    p.add_argument("operation", choices=["source", "artifact", "startup", "build"])
    p.add_argument("--workspace", default=str(Path(__file__).resolve().parent.parent))
    p.add_argument("--baseline"); p.add_argument("--jar"); p.add_argument("--contract")
    p.add_argument("--contract-sha"); p.add_argument("--source")
    p.add_argument("--manifest"); p.add_argument("--java-home"); p.add_argument("--cache-root")
    a = p.parse_args()
    try:
        if a.operation in {"source", "build"} and not a.baseline:
            raise GateError("Baseline file required")
        if a.operation == "source": result = check_source(a.workspace, a.baseline)
        elif a.operation == "artifact": result = verify_contract(a.jar, a.contract, a.contract_sha, a.source)
        elif a.operation == "startup": result = check_startup(a.workspace, a.manifest, a.jar)
        else:
            if not a.cache_root: raise GateError("Repository-local dependency cache required")
            result = build(a.workspace, a.baseline, a.java_home, a.cache_root)
        print(json.dumps({"status": "PASS", "scope": a.operation, "result": result}, ensure_ascii=True))
        return 0
    except (GateError, OSError, TypeError, KeyError, zipfile.BadZipFile) as e:
        print(json.dumps({"status": "BLOCKED", "scope": a.operation, "reason": str(e) if isinstance(e, GateError) else type(e).__name__}, ensure_ascii=True))
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
