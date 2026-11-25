#!/usr/bin/env python3
"""Offline build/artifact/startup gate. Never connects to production or approves releases."""
import argparse
import configparser
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
CORE_MIGRATION_SOURCE = 'e6c351f21ece6fb096ff425ca4b690232632de21'
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
    "V44__discover_song_publication.sql": "70124e97c6ce679b7f854bff29881d500833a74c39a05a9fccec2c69face1790",
    "V43__lyric_revision_custom_sections.sql": "c6c7032590c2b19bf7f14a5fd08b9a1554a4344da96e3d211c336dcebf9e3d6a",
    "V3__generation_snapshot.sql": "c8690dc83f20884591e985be56954935bcdf01b0f456bfe0f55a22c91f21281e",
    "V4__durable_execution_and_recovery.sql": "7710acedeaa91caa49869bbe3ac4c9e6875dbdbfbec61f01e38764f404699e6c",
    "V5__media_capacity.sql": "78992a6ef3b11b362217d06674ebfb3bd5e67ce778e9ab3d19ef918f18cc1ce3",
    "V6__lyrics_composition_request.sql": "dc21322d0cb1b343876ee61b29ce4aa3f9a99429c9f559798b1da690001ce7b6",
    "V7__model_usage_budget.sql": "47bc70beb40abaa7b82b4b0eea71647872233b2c1a9e571c2c13c7a9414aa29d",
    "V8__playback_lyrics.sql": "bf9293fca2c2219b6b8001e34478d101b67d07a408aa0cc74a0ed5f4d4d7f3da",
    "V9__vocal_gender.sql": "7622d709fcee77c476056977365d69d3e56ba123593219288ac28f1ba198b35b"
}
INTEGRATED_MIGRATION = 'V45__credit_entitlement_integration.sql'
INTEGRATED_MIGRATION_SHA256 = '3dd5cebf19dee960ecefb2239668139918d363496fe87710f817c5b3a3241685'
AGENT_ALLOWANCE_MIGRATION = 'V46__agent_chat_allowance.sql'
AGENT_ALLOWANCE_MIGRATION_SHA256 = '8ce991cea50af971ca6d748c1a37b304a8ee11d7969f06dd455dc16629638f86'


FEATURE_MIGRATION_SHA256 = {
    "V51__payment_reconciliation_cursor.sql": "7030c4fba4bc5009ca921fb59f931ebef952f6f93d67efd77889c298cd2ee045",
    "V52__gift_daily_budget.sql": "898c2e8a1b47009f68b21554dd8d5e46614eb29ed77475d108e5ea568bf1ad07",
    "V53__agent_admission_version.sql": "83af90babf7a7a299177d08d0b3e1eb79bcabf7cc0454cbd4d6025ae074e5591",
    "V54__song_share_statistics.sql": "9d837e8c53171c274f2367d1ff99ead74eb706659b0a612b6f5aa2aec0b9f633",
    "V47__music_video_projects.sql": "b4a25044d77906e891d56632f037a68a1ba5dad2073a129188b11b8bcba8a08a",
    "V48__phone_binding.sql": "09aeadab869727486bac96ac9200ad47310132763ad49020c0abe7844b359ca3",
    "V49__lyrics_preparation.sql": "c0cde3e30b4e2bac3b3690c70dfd1125abced7045f1163ec9ee33a5c214616b4",
    "V50__discover_daily_plays_and_vocal_approval.sql": "bcedc1392b8623d7d31516f01c101f17aa170ae105ac6866f7a7ef71b77f510a",
    "V51__payment_reconciliation_cursor.sql": "7030c4fba4bc5009ca921fb59f931ebef952f6f93d67efd77889c298cd2ee045",
    "V52__gift_daily_budget.sql": "898c2e8a1b47009f68b21554dd8d5e46614eb29ed77475d108e5ea568bf1ad07",
    "V53__agent_admission_version.sql": "83af90babf7a7a299177d08d0b3e1eb79bcabf7cc0454cbd4d6025ae074e5591",
    "V54__song_share_statistics.sql": "9d837e8c53171c274f2367d1ff99ead74eb706659b0a612b6f5aa2aec0b9f633",
    "V55__generation_audio_reference.sql": "c629d909975214990cd9d02561d364e3cd5274e1ede43c57ed1ef9971450c4d0",
    "V56__discover_song_community.sql": "3f3a64d35e5b57b508f51823ef90f5732e304cbfe080d026845bd41bdc6e056a",
    "V57__extended_lyric_languages.sql": "1ca4736350a6f7d4af512e97858da6cfbe5859f8614bcd7b32626cb3e054c553",
}

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
    rollback_expected = {**CORE_MIGRATION_SHA256,
                         INTEGRATED_MIGRATION: INTEGRATED_MIGRATION_SHA256}
    release_expected = {**rollback_expected,
                        AGENT_ALLOWANCE_MIGRATION: AGENT_ALLOWANCE_MIGRATION_SHA256}
    mv_expected = {**release_expected, "V47__music_video_projects.sql": FEATURE_MIGRATION_SHA256["V47__music_video_projects.sql"]}
    phone_expected = {**mv_expected, "V48__phone_binding.sql": FEATURE_MIGRATION_SHA256["V48__phone_binding.sql"]}
    lyrics_expected = {**phone_expected, "V49__lyrics_preparation.sql": FEATURE_MIGRATION_SHA256["V49__lyrics_preparation.sql"]}
    discover_name = "V50__discover_daily_plays_and_vocal_approval.sql"
    discover_expected = {**lyrics_expected, discover_name: FEATURE_MIGRATION_SHA256[discover_name]}
    lyrics_extensions = {"agentAllowanceVersion": "46", "musicVideoVersion": "47",
                         "phoneBindingVersion": "48", "lyricsPreparationVersion": "49"}
    feature_expected = discover_expected.copy()
    feature_versions = {**lyrics_extensions, "discoverShelvesVersion": "50"}
    for name, key in (
        ("V51__payment_reconciliation_cursor.sql", "paymentReconciliationVersion"),
        ("V52__gift_daily_budget.sql", "giftDailyBudgetVersion"),
        ("V53__agent_admission_version.sql", "agentAdmissionVersion"),
        ("V54__song_share_statistics.sql", "songShareStatisticsVersion"),
        ("V55__generation_audio_reference.sql", "generationAudioReferenceVersion"),
        ("V56__discover_song_community.sql", "discoverSongCommunityVersion"),
        ("V57__extended_lyric_languages.sql", "extendedLyricLanguagesVersion"),
    ):
        if name not in normalized:
            break
        feature_expected[name] = FEATURE_MIGRATION_SHA256[name]
        feature_versions[key] = name[1:3]
    if set(normalized) == set(feature_expected):
        expected = feature_expected
        extension = feature_versions
    elif set(normalized) == set(lyrics_expected):
        expected = lyrics_expected
        extension = lyrics_extensions
    elif set(normalized) == set(phone_expected):
        expected = phone_expected
        extension = {"agentAllowanceVersion": "46", "musicVideoVersion": "47", "phoneBindingVersion": "48"}
    elif set(normalized) == set(mv_expected):
        expected = mv_expected
        extension = {"agentAllowanceVersion": "46", "musicVideoVersion": "47"}
    elif set(normalized) == set(release_expected):
        expected = release_expected
        extension = {"agentAllowanceVersion": "46"}
    elif set(normalized) == set(rollback_expected):
        expected = rollback_expected
        extension = {}
    else:
        raise GateError("Canonical migration chain must contain core V1..V45 followed by consecutive approved extensions through V57")
    for name, digest in expected.items():
        if normalized[name] != digest:
            raise GateError("Canonical migration source changed: " + name)
    return {"coreSourceCommit": CORE_MIGRATION_SOURCE, "coreVersions": "1..44",
            "integrationVersion": "45", **extension,
            "normalization": "UTF-8/no BOM/LF",
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
            remote_assets = manifest.get("remoteAssets", [])
            if (manifest.get("version") != 1 or not isinstance(assets, list)
                    or not isinstance(remote_assets, list) or not (assets or remote_assets)):
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

LEGACY_FINANCIAL_UUID = "05c27d3c-dda3-11f1-aba0-84a938421519"
LEGACY_COMPATIBILITY_UUID = "47427132-df4d-11f1-89e2-84a938421519"
PRODUCTION_MYSQL_UUID = "f71f7437-d6a8-11f1-baa2-00163e1ce234"


def local_cohort_instances(cache_root, instances):
    """Anchor newly initialized fixture identities to the current repository cache."""
    if not isinstance(instances, dict) or set(instances) != {"financial", "compatibility"}:
        raise GateError("Invalid local cohort instances")
    root = cache_root / "release-gate-cohorts" / "instances"
    found = {}
    for role, entry in instances.items():
        if (not isinstance(entry, dict) or set(entry) != {"datadir", "uuid"}
                or not all(isinstance(value, str) for value in entry.values())):
            raise GateError("Invalid local cohort instance record")
        identifier = entry["uuid"]
        if (not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", identifier)
                or identifier in {PRODUCTION_MYSQL_UUID, LEGACY_FINANCIAL_UUID, LEGACY_COMPATIBILITY_UUID}):
            raise GateError("Invalid or reserved local cohort instance UUID")
        data = Path(entry["datadir"])
        try:
            relative = data.relative_to(root)
            if (len(relative.parts) != 2 or relative.name != role
                    or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", relative.parts[0])):
                raise GateError("Local cohort data directory outside its permitted cache")
            identity_file = data / "auto.cnf"
            for path in (root.parent, root, data.parent, data, identity_file):
                if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
                    raise GateError("Local cohort cache must not contain linked instances")
            if data.resolve(strict=True) != data or not data.is_dir() or not identity_file.is_file():
                raise GateError("Local cohort data directory or identity file missing")
            config = configparser.ConfigParser(interpolation=None)
            with identity_file.open(encoding="utf-8-sig") as stream:
                config.read_file(stream)
            if config.get("auto", "server-uuid", fallback="") != identifier:
                raise GateError("Local cohort UUID differs from initialized data directory")
        except (ValueError, OSError, configparser.Error):
            raise GateError("Could not verify local cohort data directory") from None
        found[role] = (identifier, data)
    financial, compatibility = found["financial"], found["compatibility"]
    if financial[0] == compatibility[0] or financial[1].parent != compatibility[1].parent:
        raise GateError("Local cohort requires two distinct instances in one private cohort directory")
    return financial, compatibility


def matches_cohort_instance(identity, expected, port):
    identifier, datadir = expected
    actual_datadir = Path(identity[1]) if isinstance(datadir, Path) else identity[1]
    return (identity[0], actual_datadir, *identity[2:]) == (identifier, datadir, port, "127.0.0.1", "8.0.46")


def load_isolated_backend_cohort(cache_root, manifest_path):
    """Read a fresh, private test cohort; never use inherited database settings."""
    if not manifest_path:
        raise GateError("Explicit fresh isolated backend test cohort required")
    cache_root = Path(cache_root).resolve()
    supplied = Path(manifest_path)
    if supplied.is_symlink() or supplied.resolve(strict=True).parent != cache_root / "release-gate-cohorts":
        raise GateError("Isolated backend cohort manifest outside private cache")
    manifest = load_json(supplied)
    fields = {"version", "revision", "carryForwardRevision", "compatibilityPort"}
    if manifest.get("version") == 1 and set(manifest) == fields:
        financial = (LEGACY_FINANCIAL_UUID, "R:\\data\\")
        compatibility = (LEGACY_COMPATIBILITY_UUID, "R:\\v45-compatibility-r1\\")
    elif manifest.get("version") == 2 and set(manifest) == fields | {"instances"}:
        financial, compatibility = local_cohort_instances(cache_root, manifest["instances"])
    else:
        raise GateError("Invalid isolated backend cohort manifest")
    revision, carry = manifest["revision"], manifest["carryForwardRevision"]
    if (not isinstance(revision, str) or not re.fullmatch(r"r[0-9]+", revision)
            or not isinstance(carry, str) or not re.fullmatch(r"r[0-9]+", carry)
            or carry == revision or manifest["compatibilityPort"] != 57346):
        raise GateError("Invalid isolated backend cohort revision or port")
    try:
        import pymysql
    except ImportError:
        raise GateError("PyMySQL required to verify isolated backend cohort") from None
    expected_uuid = financial[0]
    definitions = (
        ("migration", "payment-main-integration-20261112", "p0_integration_migration_20261112", "P0_INTEGRATION_MIGRATION_MYSQL_", revision),
        ("credits", "payment-main-integration-20261112", "p0_integrated_credits_20261112", "P0_INTEGRATED_CREDITS_MYSQL_", revision),
        ("application", "payment-main-integration-20261112", "p0_integration_application_20261112", "P0_INTEGRATION_APPLICATION_MYSQL_", revision),
        ("legacy40", "payment-main-integration-20261112", "p0_integration_legacy40_20261112", "P0_INTEGRATION_LEGACY40_MYSQL_", revision),
        ("core40", "payment-main-integration-20261112", "p0_core40_application_20261112", "P0_CORE40_APPLICATION_MYSQL_", revision),
        ("overlap", "payment-go-live-execution-20261113", "p0_dispute_overlap_20261113", "P0_DISPUTE_OVERLAP_MYSQL_", revision),
        ("history", "payment-go-live-execution-20261113", "p0_historical_cutover_20261113", "P0_HISTORICAL_CUTOVER_MYSQL_", revision),
        ("history", "payment-go-live-execution-20261113", "p0_historical_cutover_20261113", "P0_CARRY_FORWARD_MYSQL_", carry),
    )
    backend = {key: "57346" for key in ("QF_EXECUTION_MYSQL_TEST_PORT", "QF_QUICK_MYSQL_TEST_PORT", "QF_DOWNLOAD_MYSQL_TEST_PORT")}
    backend["SPRING_FLYWAY_TARGET"] = "44"
    if manifest["version"] == 2:
        backend["QF_ISOLATED_COHORT_DATADIR"] = str(financial[1])
    secrets = set()
    names = set()
    try:
        with pymysql.connect(host="127.0.0.1", port=57346, user="root", password="",
                             connect_timeout=2, read_timeout=5) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION()")
                identity = cursor.fetchone()
                if not matches_cohort_instance(identity, compatibility, 57346):
                    raise GateError("Compatibility MySQL identity changed")
                cursor.execute("SHOW DATABASES")
                visible = {row[0] for row in cursor.fetchall()}
                if not visible <= {"information_schema", "mysql", "performance_schema", "sys", "qf_execution_concurrency_test", "qf_quick_creation_test"} or "qingfeng_music" in visible:
                    raise GateError("Compatibility MySQL has unexpected schema")
        for label, directory, prefix_name, env_prefix, selected_revision in definitions:
            path = cache_root / directory / (label + "-db-private-" + selected_revision + ".json")
            if path.is_symlink() or not path.is_file() or path.resolve().parent != cache_root / directory:
                raise GateError("Missing private isolated backend database configuration")
            cfg = load_json(path)
            monitor = cfg.pop("monitor", None) if label == "application" else None
            name = prefix_name + selected_revision
            if (set(cfg) != {"host", "port", "database", "user", "password", "uuid"}
                    or cfg["host"] != "127.0.0.1" or cfg["port"] != 23316
                    or cfg["uuid"] != expected_uuid or cfg["database"] != name
                    or name in names or not isinstance(cfg["user"], str) or not cfg["user"]
                    or cfg["user"].lower() == "root" or not isinstance(cfg["password"], str)
                    or not cfg["password"]):
                raise GateError("Private isolated backend database configuration invalid")
            names.add(name)
            with pymysql.connect(host="127.0.0.1", port=23316, user=cfg["user"], password=cfg["password"],
                                 database=name, connect_timeout=2, read_timeout=5) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION(),DATABASE()")
                    identity = cursor.fetchone()
                    if not matches_cohort_instance(identity[:-1], financial, 23316) or identity[-1] != name:
                        raise GateError("Isolated backend database identity changed")
                    cursor.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")
                    if cursor.fetchone() != (0,):
                        raise GateError("Isolated backend database cohort is not fresh")
                    cursor.execute("SHOW DATABASES")
                    if {row[0] for row in cursor.fetchall()} != {"information_schema", "performance_schema", name}:
                        raise GateError("Isolated backend database user can see another schema")
                    cursor.execute("SHOW GRANTS")
                    grants = [row[0] for row in cursor.fetchall()]
                    if (len(grants) != 2 or not any(grant.startswith("GRANT USAGE ON *.* TO ") for grant in grants)
                            or not any(grant.startswith("GRANT ALL PRIVILEGES ON `" + name + "`.* TO ") for grant in grants)
                            or any("WITH GRANT OPTION" in grant for grant in grants)):
                        raise GateError("Isolated backend database grants exceed its own schema")
                    if manifest["version"] == 2:
                        cursor.execute("SELECT CURRENT_ROLE()")
                        if cursor.fetchone() != ("NONE",):
                            raise GateError("Isolated backend database user has an active role")
            if monitor is not None:
                if (set(monitor) != {"user", "password"} or not all(isinstance(v, str) and v for v in monitor.values())
                        or monitor["user"].lower() == "root" or monitor["user"] == cfg["user"]):
                    raise GateError("Invalid isolated lock monitor")
                with pymysql.connect(host="127.0.0.1", port=23316, database=name, connect_timeout=2,
                                     read_timeout=5, **monitor) as connection, connection.cursor() as cursor:
                    cursor.execute("SHOW GRANTS")
                    scopes = {row[0].split(" TO ", 1)[0] for row in cursor.fetchall()}
                    if scopes != {"GRANT PROCESS ON *.*", "GRANT SELECT ON `" + name + "`.*"}:
                        raise GateError("Isolated lock monitor must have only PROCESS and fixture SELECT")
                    cursor.execute("SELECT CURRENT_ROLE()")
                    if cursor.fetchone() != ("NONE",):
                        raise GateError("Isolated lock monitor has an active role")
                backend[env_prefix + "MONITOR_USER"] = monitor["user"]
                backend[env_prefix + "MONITOR_PASSWORD"] = monitor["password"]
                secrets.update(monitor.values())
            url = "jdbc:mysql://127.0.0.1:23316/" + name + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
            backend.update({env_prefix + "URL": url, env_prefix + "DATABASE": name,
                            env_prefix + "USER": cfg["user"], env_prefix + "PASSWORD": cfg["password"],
                            env_prefix + "UUID": expected_uuid})
            secrets.update((cfg["user"], cfg["password"]))
    except GateError:
        raise
    except Exception:
        raise GateError("Isolated backend database identity or connectivity check failed") from None
    return backend, secrets

def build(workspace, baseline, java_home, cache_root, isolated_mysql_cohort=None):
    """Export clean Git source and run offline commands without private files or inherited secrets."""
    workspace = Path(workspace).resolve()
    source = check_source(workspace, baseline)  # Must be first: no build/stop/install before this.
    if not java_home or not (Path(java_home) / "bin/java.exe").is_file():
        raise GateError("Explicit installed Java 17 JDK required")
    cache_root = Path(cache_root).resolve()
    common = Path(git(workspace, "rev-parse", "--path-format=absolute", "--git-common-dir")).parent
    if not cache_root.is_relative_to(common):
        raise GateError("Dependency cache must remain within this repository")
    backend_settings, backend_secrets = load_isolated_backend_cohort(cache_root, isolated_mysql_cohort)
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
    def execute(name, command, cwd, child_env=None, redactions=()):
        log = run / (name + ".log")
        try:
            p = subprocess.run(command, cwd=cwd, env=child_env or env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1800)
        except (OSError, subprocess.TimeoutExpired):
            raise GateError("Build command unavailable or timed out: " + name) from None
        output = p.stdout or b""
        for secret in sorted(redactions, key=len, reverse=True):
            output = output.replace(secret.encode("utf-8"), b"[REDACTED]")
        log.write_bytes(output)
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
    execute("frontendTest", [npm, "test", "--", "--maxWorkers=4", "--testTimeout=15000"], frontend)
    execute("frontendBuild", [npm, "run", "build"], frontend)
    metadata = export / "Qingfeng_AI_Music/deploy/release-metadata"
    metadata.mkdir(parents=True, exist_ok=True)
    (metadata / "release-commit.txt").write_text(source["sourceCommit"] + "\n", encoding="utf-8")
    epoch = git(workspace, "show", "-s", "--format=%ct", source["sourceCommit"])
    backend_env = {**env, **backend_settings}
    execute("backendVerify", [mvn, "-o", "-B", "-ntp", "--settings", str(empty),
              "-Dmaven.repo.local=" + str(cache_root / "maven"), "-Duser.home=" + str(home),
              "-Dproject.build.outputTimestamp=" + epoch, "clean", "verify"], export / "Qingfeng_AI_Music",
            child_env=backend_env, redactions=backend_secrets)
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
    p.add_argument("--isolated-mysql-cohort")
    a = p.parse_args()
    try:
        if a.operation in {"source", "build"} and not a.baseline:
            raise GateError("Baseline file required")
        if a.operation == "source": result = check_source(a.workspace, a.baseline)
        elif a.operation == "artifact": result = verify_contract(a.jar, a.contract, a.contract_sha, a.source)
        elif a.operation == "startup": result = check_startup(a.workspace, a.manifest, a.jar)
        else:
            if not a.cache_root: raise GateError("Repository-local dependency cache required")
            result = build(a.workspace, a.baseline, a.java_home, a.cache_root, a.isolated_mysql_cohort)
        print(json.dumps({"status": "PASS", "scope": a.operation, "result": result}, ensure_ascii=True))
        return 0
    except (GateError, OSError, TypeError, KeyError, zipfile.BadZipFile) as e:
        print(json.dumps({"status": "BLOCKED", "scope": a.operation, "reason": str(e) if isinstance(e, GateError) else type(e).__name__}, ensure_ascii=True))
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
