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

# Immutable canonical core source; EOL normalization matches cross-platform source exports.
CORE_MIGRATION_SOURCE = '0530c444a38db2363e0a6296c52a416b09a14d5f'
CORE_MIGRATION_SHA256 = {
    "V10__cover_assets.sql": "c3e80ebcb4b79026c7ccc61ce55be02a0695d2251aa62c6327f685d3803ecfaf",
    "V11__lyrics_request_recovery.sql": "114bfd16c036be78a5ae20e771ccc297bf05e254034268011485c473b0f14cde",
    "V12__section_edits_and_audio_selection.sql": "c77eee8f5076afe07a7c310f856f5a7b24c8fb322aae6de99cebdd5644b19d65",
    "V13__music_preferences.sql": "41b181d6aa42fcc69cd56d4fc7cc0017e239d91f4d0cd5bccaed5eeaa2dd67ee",
    "V14__google_identity.sql": "89910320d5abc2372091b562cbdec4430dcde284963277c95129c985fdd6da9d",
    "V15__credits_ledger.sql": "df2c99fc9f8ae3f290080dd8303e3237ce235de50db79a720955dd9e5721b38d",
    "V16__payment_orders.sql": "7f98f984dd6042a64ddd6b6660c2df6bdc96cda279b2d7c4b7a3dfe0d50e001a",
    "V17__authing_identity.sql": "89612dd7c9c88d9b824d932dc40a168474c3141b1f28304ed6b61d2a210fb06f",
    "V18__payment_risk_events.sql": "fc317f0cb74cfce0527f56ceb8ced68741074b8d34d5045298866c3ec7e55df2",
    "V19__payment_provider.sql": "4435d36b7be94eefb3b9097bc16766b2a61190d308068557b52dd2c42fd434eb",
    "V1__creator_and_work.sql": "33f0c5fab59fe616443e971b8adcf1b0f6137f029fa0c4423f645b7ce1cc62a9",
    "V20__oidc_identity.sql": "2914ef9f3aaffefeca9e39accd205a5e643fa14ec449d3294c25bce1a02d8057",
    "V21__referral_pending.sql": "84767bbd69e7b9b71217dc190758402c34b73e35028ad8292a3354719dfb3ef8",
    "V22__welcome_dialog_receipt.sql": "da375956b919fe577347ef71fa1f845751f98fb0922bfa8ac5de75d25f97af83",
    "V23__creative_plans_and_audio_candidates.sql": "ab4eb4529bf1110a7a686d0310d8a9e9c25042b9e8e58e7b8a656412b488135f",
    "V24__site_events.sql": "c32a8c5fb26151ee0d993a8f0512e58805111bda3233e1dbf5d1d8debed9b99f",
    "V25__site_event_identity_duration.sql": "3e0c1c43c39f75a8cb62c157e8d38e1c09f3ac250f931a9979b0e94d976ad0df",
    "V26__hot_song_engagement.sql": "39c1eeb5b3e468c266779f3498db3954f5dc6c43138489ab7bafba6145b1dc3c",
    "V27__work_soft_delete.sql": "561c0cf05a6adeba05c4c75c9255c84e10a254a73383a419f1bf0f5d689cc435",
    "V28__quick_creation.sql": "a148c5b31da60edc20f0e6aa75e5e4d9878bcb5b0e599fded3285ee5f9efe17b",
    "V29__lyric_revision_settings.sql": "bea0dcd5522fbdc20fc162bb253b0e8d29b081fcb45e7bbd45c1cd360fb51f05",
    "V2__lyrics_media_and_sharing.sql": "3eb701d1971842ca02cc2e0340b29ca1455042fa35aadfb02c9aa39276baa3c6",
    "V30__credit_consumption.sql": "b83b7cc543cdbf0a792d4d271f70b6ee6cf71780472de2bd3773d322ba603ce6",
    "V31__rewrite_charges.sql": "e96e5847dd2ee3e5330bf07479e042918bb1ebbd28b2ab7d66bbd3396352440d",
    "V32__agent_conversation.sql": "73284eeb282b80c2516f18dbcfdd80d8eede8af966234057d6e41a0605c58597",
    "V33__site_event_geo.sql": "bc3644cf7efbb931ad2fa8aff135efa9e95462874d292f996b6d0cf9d4c026b8",
    "V34__payment_inbox_and_account_balance.sql": "d99864ffacded9d290beb173445ec7d0af127425e22551305fd93dbee8451e56",
    "V35__agent_admission.sql": "73b119fb6abd8ff2f1b4cc22e931367ec81b439f06c918b03bdb0f325eb177db",
    "V36__agent_history_and_semantic_index.sql": "1af23bb27379ea22dbea0a3e9b8973dc097a61477850836a859bc193486e71ee",
    "V37__durable_task_outbox.sql": "bf2a38f0c9657f1a9c20414b9ba1b41268dedf207b6077fcf2b09e668829a2bb",
    "V38__creator_avatar.sql": "32fa67ea71515c743c7c0bf18e909c54e5d7f4c6bcd42ac77c1b1c8840b6ab93",
    "V39__avatar_generation_task.sql": "9cb8edb9e62742186da6d1b843cdfeb7b6813e572a0027441279313371350559",
    "V40__artist_profiles_and_releases.sql": "6961661d242cb88d9744f38da7fe2c46211e48ffa23c51a30431ed5cde50b6ac",
    "V41__artist_home_customization.sql": "5a518704c89fde820ceb60561b60d3c7df1bc1559b455e9afcdd5767796f29d7",
    "V42__shared_download_entitlement.sql": "cfa29d81a1e69515436e357111ec31fd81f8f7709add6b7b5241b7b2462fffec",
    "V43__lyric_revision_custom_sections.sql": "c6c7032590c2b19bf7f14a5fd08b9a1554a4344da96e3d211c336dcebf9e3d6a",
    "V3__generation_snapshot.sql": "c8690dc83f20884591e985be56954935bcdf01b0f456bfe0f55a22c91f21281e",
    "V4__durable_execution_and_recovery.sql": "7710acedeaa91caa49869bbe3ac4c9e6875dbdbfbec61f01e38764f404699e6c",
    "V5__media_capacity.sql": "78992a6ef3b11b362217d06674ebfb3bd5e67ce778e9ab3d19ef918f18cc1ce3",
    "V6__lyrics_composition_request.sql": "dc21322d0cb1b343876ee61b29ce4aa3f9a99429c9f559798b1da690001ce7b6",
    "V7__model_usage_budget.sql": "47bc70beb40abaa7b82b4b0eea71647872233b2c1a9e571c2c13c7a9414aa29d",
    "V8__playback_lyrics.sql": "bf9293fca2c2219b6b8001e34478d101b67d07a408aa0cc74a0ed5f4d4d7f3da",
    "V9__vocal_gender.sql": "7622d709fcee77c476056977365d69d3e56ba123593219288ac28f1ba198b35b"
}
INTEGRATED_MIGRATION = 'V44__credit_entitlement_integration.sql'
INTEGRATED_MIGRATION_SHA256 = '5f93d318f7583906f424fe0a5e41afac5ba1d147f91ee4527c88f1a1ee51433b'


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

def normalized_migration_sha(data):
    try:
        text = data.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    except UnicodeError:
        raise GateError("Migration is not UTF-8") from None
    return sha(text.encode("utf-8"))


def verify_migration_set(normalized):
    expected = {**CORE_MIGRATION_SHA256, INTEGRATED_MIGRATION: INTEGRATED_MIGRATION_SHA256}
    if set(normalized) != set(expected):
        raise GateError("Canonical migration chain must contain exactly core V1..V43 and integrated V44")
    for name, digest in expected.items():
        if normalized[name] != digest:
            raise GateError("Canonical migration source changed: " + name)
    return {"coreSourceCommit": CORE_MIGRATION_SOURCE, "coreVersions": "1..43",
            "integrationVersion": "44", "normalization": "UTF-8/no BOM/LF",
            "normalizedSha256": dict(sorted(normalized.items()))}


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
        normalized_sql = {}
        def collect(container):
            for n in container.namelist():
                is_sql_migration = re.fullmatch(r"V[1-9][0-9]*__[A-Za-z0-9_.-]+\.sql", n.rsplit("/", 1)[-1])
                production_location = n.startswith("db/migration/V") or "/db/migration/V" in n
                if is_sql_migration and not production_location:
                    raise GateError("Test-only or alternate migration location packaged in production artifact")
                if (n.startswith("db/migration/V") or "/db/migration/V" in n) and n.endswith(".sql"):
                    name = n.rsplit("/", 1)[-1]
                    if not re.fullmatch(r"V[1-9][0-9]*__[A-Za-z0-9_.-]+\.sql", name):
                        raise GateError("Invalid migration name")
                    if name in sql or any(k.split("__")[0] == name.split("__")[0] for k in sql):
                        raise GateError("Duplicate migration version")
                    data = container.read(n)
                    sql[name] = sha(data)
                    normalized_sql[name] = normalized_migration_sha(data)
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
        migration_contract = verify_migration_set(normalized_sql)
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
            "frontendSha256": sha(json_bytes(static)), "migrations": sql,
            "migrationContract": migration_contract}



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
    for key in ("jarSha256", "sourceCommit", "frontendSha256", "migrations", "migrationContract"):
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
