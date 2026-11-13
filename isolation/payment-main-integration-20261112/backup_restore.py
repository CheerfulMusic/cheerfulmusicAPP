"""Isolated application-r6 backup/restore acceptance. Default invocation does nothing.

Never starts MySQL/the application, migrates a schema, or writes the source database.
Run only after the central application test process has exited. Failed cohorts and
both SQL files are retained; this script never drops a database, user, or table.
"""
from pathlib import Path
import argparse
import base64
import datetime as dt
import decimal
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys


HOST, PORT = "127.0.0.1", 23316
UUID = "05c27d3c-dda3-11f1-aba0-84a938421519"
SOURCE_DB = "p0_integration_application_20261112r6"
TARGET_DB = "p0_integration_restore_20261112r2"
TARGET_USER = "integrated_restore_20261112r2"
MYSQL_BIN = Path("R:/mysql-8.0.46-winx64/bin")
SOURCE_PRIVATE = "application-db-private-r6.json"
TARGET_PRIVATE = "restore-db-private-r2.json"
INTEGRATION_CHECKSUM = 789542731


class GuardFailure(RuntimeError):
    """Only fixed, non-secret failure codes may be exposed."""
    def __init__(self, code, details=None):
        super().__init__(code)
        self.details = details


def require(condition, code):
    if not condition:
        raise GuardFailure(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def create_json(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(encoded(value) + b"\n")


def common_root(workspace):
    marker = workspace / ".git"
    if marker.is_dir():
        common = marker.resolve()
    else:
        line = marker.read_text(encoding="utf-8").strip()
        require(line.startswith("gitdir: "), "WORKTREE_GIT_MARKER_INVALID")
        gitdir = (workspace / line[8:]).resolve()
        pointer = gitdir / "commondir"
        require(pointer.is_file(), "WORKTREE_COMMONDIR_MISSING")
        common = (gitdir / pointer.read_text(encoding="utf-8").strip()).resolve()
    root = common.parent
    require(common.name == ".git" and (root / ".cache").is_dir(), "COMMON_ROOT_INVALID")
    return root


def qid(name):
    require(bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name)), "UNEXPECTED_SQL_IDENTIFIER")
    return "`" + name + "`"


def configuration(path, database):
    cfg = json.loads(path.read_text(encoding="utf-8-sig"))
    require(cfg.get("host") == HOST and cfg.get("port") == PORT
            and cfg.get("uuid") == UUID and cfg.get("database") == database,
            "PRIVATE_CONFIGURATION_TARGET_MISMATCH")
    require(isinstance(cfg.get("user"), str)
            and re.fullmatch(r"[A-Za-z0-9_]{1,32}", cfg["user"])
            and cfg["user"] != "root", "SCOPED_ACCOUNT_REQUIRED")
    require(isinstance(cfg.get("password"), str) and bool(cfg["password"])
            and "\0" not in cfg["password"], "PRIVATE_PASSWORD_INVALID")
    return cfg


def connect(pymysql, cfg, admin=False):
    options = dict(host=HOST, port=PORT, user=cfg["user"], password=cfg["password"],
                   charset="utf8mb4", autocommit=False, connect_timeout=3,
                   read_timeout=120, write_timeout=120,
                   cursorclass=pymysql.cursors.DictCursor)
    if not admin:
        options["database"] = cfg["database"]
    return pymysql.connect(**options)


def query(conn, sql, args=()):
    with conn.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def identity(conn, database=None):
    row = query(conn, "SELECT @@server_uuid uuid,@@datadir datadir,@@port port,"
                      "@@bind_address bind_address,VERSION() version,DATABASE() database_name,"
                      "CURRENT_ROLE() role_name,CURRENT_USER() account,CONNECTION_ID() connection_id")[0]
    require(row["uuid"] == UUID and row["datadir"].replace("\\", "/").lower() == "r:/data/"
            and row["port"] == PORT and row["bind_address"] == HOST
            and row["version"] == "8.0.46" and row["role_name"] == "NONE",
            "MYSQL_INSTANCE_IDENTITY_MISMATCH")
    if database is not None:
        require(row["database_name"] == database, "MYSQL_DATABASE_IDENTITY_MISMATCH")
    return row


def scoped_proof(conn, cfg):
    found = identity(conn, cfg["database"])
    require(found["account"] == cfg["user"] + "@" + HOST, "ACCOUNT_HOST_BINDING_MISMATCH")
    grants = [next(iter(row.values())) for row in query(conn, "SHOW GRANTS")]
    require(len(grants) == 2 and all("WITH GRANT OPTION" not in grant for grant in grants),
            "UNEXPECTED_ACCOUNT_GRANTS")
    require(sum(grant.startswith("GRANT USAGE ON *.*") for grant in grants) == 1,
            "GLOBAL_ACCOUNT_PRIVILEGES_FORBIDDEN")
    require(sum(" ON " + qid(cfg["database"]) + ".* TO " in grant for grant in grants) == 1,
            "SINGLE_SCHEMA_GRANT_REQUIRED")
    visible = {next(iter(row.values())) for row in query(conn, "SHOW DATABASES")}
    require(visible <= {cfg["database"], "information_schema", "performance_schema"},
            "OTHER_BUSINESS_SCHEMA_VISIBLE")
    conn.rollback()
    return {"uuid": found["uuid"], "port": found["port"], "datadir": "R:/data/",
            "database": cfg["database"], "role_none": True, "single_schema_grants": True,
            "account_host_binding_verified": True, "grant_count": len(grants)}


def no_other_source_clients(conn, cfg):
    own = query(conn, "SELECT CONNECTION_ID() id")[0]["id"]
    rows = query(conn, "SHOW PROCESSLIST")
    require(not any(row.get("User") == cfg["user"] and row.get("Id") != own for row in rows),
            "SOURCE_ACCOUNT_STILL_HAS_OTHER_CLIENTS")
    conn.rollback()


def cell(value):
    if value is None:
        return ["null"]
    if isinstance(value, bool):
        return ["bool", value]
    if isinstance(value, int):
        return ["int", str(value)]
    if isinstance(value, decimal.Decimal):
        return ["decimal", str(value)]
    if isinstance(value, float):
        return ["float", value.hex()]
    if isinstance(value, dt.datetime):
        return ["datetime", value.isoformat(timespec="microseconds")]
    if isinstance(value, (dt.date, dt.time, dt.timedelta)):
        return [type(value).__name__, str(value)]
    if isinstance(value, (bytes, bytearray, memoryview)):
        return ["bytes", base64.b64encode(value).decode("ascii")]
    require(isinstance(value, str), "UNSUPPORTED_DATABASE_VALUE_TYPE")
    return ["str", value]


def financial_invariants(conn):
    rows = query(conn, "SELECT c.id owner_id,COALESCE(l.book,0) book,a.balance account_balance,"
                       "COALESCE(b.rights,0) rights,COALESCE(b.held,0) held FROM creator c "
                       "LEFT JOIN (SELECT owner_id,SUM(amount) book FROM credit_ledger GROUP BY owner_id) l ON l.owner_id=c.id "
                       "LEFT JOIN credit_account a ON a.owner_id=c.id "
                       "LEFT JOIN (SELECT owner_id,SUM(original-consumed-adjusted) rights,SUM(held) held "
                       "FROM p0_credit_batch GROUP BY owner_id) b ON b.owner_id=c.id ORDER BY c.id")
    owners = []
    for row in rows:
        book, rights, held = int(row["book"]), int(row["rights"]), int(row["held"])
        stored = None if row["account_balance"] is None else int(row["account_balance"])
        require(book == rights and (stored == book or stored is None and book == 0),
                "SOURCE_OR_RESTORE_OWNER_CONSERVATION_MISMATCH")
        owners.append({"owner_alias": digest(row["owner_id"].encode("utf-8")),
                       "journal": book, "account": stored, "batch_rights": rights, "held": held,
                       "zero_balance_cache_absent": stored is None})
    invalid = query(conn, "SELECT COUNT(*) n FROM p0_credit_batch WHERE original<0 OR consumed<0 "
                         "OR consumed>original OR adjusted<0 OR adjusted>original OR held<0 "
                         "OR held>GREATEST(0,original-consumed-adjusted)")[0]["n"]
    require(invalid == 0, "BATCH_AMOUNT_CONSTRAINT_VIOLATION")
    return sorted(owners, key=lambda row: row["owner_alias"])


def structure_metadata(conn):
    """Actual schema semantics; exclude only schema identity and estimated statistics."""
    tables = query(conn, "SELECT t.TABLE_NAME table_name,t.ENGINE engine,t.ROW_FORMAT row_format,"
                        "t.TABLE_COLLATION table_collation,c.CHARACTER_SET_NAME table_charset,"
                        "t.CREATE_OPTIONS create_options,t.TABLE_COMMENT table_comment,t.AUTO_INCREMENT auto_increment "
                        "FROM information_schema.TABLES t JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY c "
                        "ON c.COLLATION_NAME=t.TABLE_COLLATION WHERE t.TABLE_SCHEMA=DATABASE() ORDER BY t.TABLE_NAME")
    result = {row.pop("table_name"): {"table_options": row} for row in tables}
    selections = {
        "columns": "SELECT TABLE_NAME table_name,ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,DATA_TYPE,"
                   "IS_NULLABLE,COLUMN_DEFAULT,CHARACTER_SET_NAME,COLLATION_NAME,NUMERIC_PRECISION,NUMERIC_SCALE,"
                   "DATETIME_PRECISION,CHARACTER_MAXIMUM_LENGTH,CHARACTER_OCTET_LENGTH,EXTRA,GENERATION_EXPRESSION,"
                   "COLUMN_COMMENT,SRS_ID FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                   "ORDER BY TABLE_NAME,ORDINAL_POSITION",
        "indexes": "SELECT TABLE_NAME table_name,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,SUB_PART,"
                   "PACKED,NULLABLE,INDEX_TYPE,COMMENT,INDEX_COMMENT,IS_VISIBLE,EXPRESSION "
                   "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",
        "constraints": "SELECT TABLE_NAME table_name,CONSTRAINT_NAME,CONSTRAINT_TYPE,ENFORCED "
                       "FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,CONSTRAINT_NAME",
        "foreign_key_columns": "SELECT TABLE_NAME table_name,CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,"
                               "POSITION_IN_UNIQUE_CONSTRAINT,REFERENCED_TABLE_SCHEMA,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME "
                               "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() "
                               "AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION",
        "foreign_key_rules": "SELECT TABLE_NAME table_name,CONSTRAINT_NAME,UNIQUE_CONSTRAINT_SCHEMA,UNIQUE_CONSTRAINT_NAME,"
                             "MATCH_OPTION,UPDATE_RULE,DELETE_RULE,REFERENCED_TABLE_NAME "
                             "FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() "
                             "ORDER BY TABLE_NAME,CONSTRAINT_NAME",
        "checks": "SELECT t.TABLE_NAME table_name,t.CONSTRAINT_NAME,c.CHECK_CLAUSE,t.ENFORCED "
                  "FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c "
                  "ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME "
                  "WHERE t.TABLE_SCHEMA=DATABASE() AND t.CONSTRAINT_TYPE='CHECK' ORDER BY t.TABLE_NAME,t.CONSTRAINT_NAME",
    }
    for group, sql in selections.items():
        for table in result.values():
            table[group] = []
        for row in query(conn, sql):
            name = row.pop("table_name")
            for key in ("REFERENCED_TABLE_SCHEMA", "UNIQUE_CONSTRAINT_SCHEMA"):
                if key in row:
                    require(row[key] == query(conn, "SELECT DATABASE() n")[0]["n"],
                            "CROSS_SCHEMA_FOREIGN_KEY_FORBIDDEN")
                    row[key] = "SELF_SCHEMA"
            result[name][group].append(row)
    return result


def semantic_ddl(ddl, metadata):
    """Remove only a charset annotation proved redundant by actual column metadata."""
    require(metadata["table_options"]["table_charset"] == "utf8mb4", "UNEXPECTED_TABLE_CHARACTER_SET")
    removed = 0
    for column in metadata["columns"]:
        if column["CHARACTER_SET_NAME"] != "utf8mb4":
            continue
        collation = column["COLLATION_NAME"]
        require(isinstance(collation, str) and re.fullmatch(r"utf8mb4_[A-Za-z0-9_]+", collation),
                "COLUMN_CHARSET_COLLATION_MISMATCH")
        # Anchor the whole column/type prefix. Never rewrite defaults, comments,
        # generated expressions, table options, or any collation declaration.
        prefix = "  " + qid(column["COLUMN_NAME"]) + " " + column["COLUMN_TYPE"]
        pattern = re.compile(r"(?m)^" + re.escape(prefix) + r" CHARACTER SET utf8mb4(?= COLLATE "
                             + re.escape(collation) + r"(?:[ ,]|$))")
        ddl, count = pattern.subn(lambda match: prefix, ddl)
        require(count <= 1, "DUPLICATE_COLUMN_CHARACTER_SET_DECLARATION")
        removed += count
    return ddl, removed


def compare_restored_snapshots(before, restored):
    # Preserve raw DDL hashes and removal counts as evidence. Every other field,
    # including normalized DDL and full columns/index/FK/check metadata, must match.
    def comparable(value):
        return {**value, "tables": [{key: item for key, item in table.items()
                                    if key not in {"create_table_sha256", "redundant_column_charset_declarations"}}
                                   for table in value["tables"]]}
    require(comparable(before) == comparable(restored), "RESTORE_TABLE_HISTORY_TRIGGER_OR_OWNER_MISMATCH")
    return {"tables": len(before["tables"]),
            "raw_ddl_equal": sum(a["create_table_sha256"] == b["create_table_sha256"]
                                 for a, b in zip(before["tables"], restored["tables"])),
            "semantic_ddl_columns_indexes_foreign_keys_checks_equal": True,
            "normalization": "Only column/type-anchored CHARACTER SET utf8mb4 before the unchanged utf8mb4 collation; actual column charset/collation checked independently"}


def snapshot(conn, cfg, references):
    conn.rollback()
    with conn.cursor() as cur:
        cur.execute("SET SESSION time_zone='+00:00'")
        cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
    require(query(conn, "SELECT @@session.transaction_read_only value")[0]["value"] == 1,
            "READ_ONLY_SNAPSHOT_NOT_ACTIVE")
    tables = query(conn, "SELECT TABLE_NAME name,ENGINE engine,TABLE_TYPE kind FROM information_schema.TABLES "
                         "WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME")
    require(tables and all(row["kind"] == "BASE TABLE" and row["engine"] == "InnoDB" for row in tables),
            "ALL_APPLICATION_TABLES_MUST_BE_INNODB_BASE_TABLES")
    for kind, table in (("routine", "ROUTINES"), ("event", "EVENTS")):
        column = "ROUTINE_SCHEMA" if kind == "routine" else "EVENT_SCHEMA"
        require(query(conn, "SELECT COUNT(*) n FROM information_schema." + table
                           + " WHERE " + column + "=DATABASE()")[0]["n"] == 0,
                "UNSUPPORTED_STORED_OBJECTS_PRESENT")
    manifests = []
    raw_ddls = {}
    for table in tables:
        name = table["name"]
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM " + qid(name))
            columns = [entry[0] for entry in cur.description]
            # Sort full typed row encodings, not truncated MySQL TEXT sort prefixes.
            values = sorted(encoded([cell(row[column]) for column in columns]) for row in cur.fetchall())
        hasher = hashlib.sha256(encoded(columns) + b"\n")
        for value in values:
            hasher.update(value + b"\n")
        create = query(conn, "SHOW CREATE TABLE " + qid(name))[0]
        ddl = next(value for key, value in create.items() if key.lower() == "create table")
        ddl = ddl.replace("\r\n", "\n")
        raw_ddls[name] = ddl
        manifests.append({"table": name, "rows": len(values), "columns": columns,
                          "row_values_sha256": hasher.hexdigest(),
                          "create_table_sha256": digest(ddl.encode("utf-8"))})
    # Opening an empty InnoDB AUTO_INCREMENT table can initialize its previously
    # NULL information_schema counter to 1 without a row/DDL change. Sample the
    # actual counters only after the full read has opened every table. Keep the
    # counters exact: never coerce NULL/1 or omit them from stability comparisons.
    metadata = structure_metadata(conn)
    for manifest in manifests:
        name = manifest["table"]
        normalized, removed = semantic_ddl(raw_ddls[name], metadata[name])
        manifest.update({"semantic_create_table_sha256": digest(normalized.encode("utf-8")),
                         "redundant_column_charset_declarations": removed,
                         "structure_metadata": metadata[name]})
    history = query(conn, "SELECT version,script,checksum,success,type FROM flyway_schema_history ORDER BY installed_rank")
    require(len(history) == 44 and {row["version"] for row in history} == {str(n) for n in range(1, 45)}
            and all(row["success"] == 1 and row["type"] == "SQL" for row in history),
            "EXACT_44_SUCCESSFUL_SQL_MIGRATIONS_REQUIRED")
    expected = {row["version"]: row for row in references["core"] + [references["integration"]]}
    for row in history:
        require(row["script"] == expected[row["version"]]["script"]
                and row["checksum"] == expected[row["version"]]["checksum"],
                "CANONICAL_FLYWAY_CHECKSUM_MISMATCH")
    triggers = query(conn, "SELECT TRIGGER_NAME name,EVENT_MANIPULATION event,EVENT_OBJECT_TABLE table_name,"
                          "ACTION_TIMING timing,ACTION_ORIENTATION orientation,ACTION_STATEMENT statement,"
                          "SQL_MODE sql_mode,CHARACTER_SET_CLIENT charset_client,COLLATION_CONNECTION collation_connection,"
                          "DATABASE_COLLATION database_collation,DEFINER definer FROM information_schema.TRIGGERS "
                          "WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME")
    require(len(triggers) == 17 and {row["name"] for row in triggers} == set(references["triggers"]),
            "EXACT_17_EXPECTED_TRIGGERS_REQUIRED")
    for row in triggers:
        require(row.pop("definer") == cfg["user"] + "@" + HOST,
                "TRIGGER_DEFINER_NOT_OWNED_BY_SCOPED_ACCOUNT")
        row["statement"] = row["statement"].replace("\r\n", "\n").strip()
    finance = financial_invariants(conn)
    conn.rollback()
    return {"tables": manifests, "flyway": history, "triggers": triggers,
            "per_owner": finance, "trigger_definers_match_current_scoped_user": True,
            "financial_conservation": "PASS"}


def rebind_definers(data, source_user, expected=17):
    text = data.decode("utf-8")
    fixed = "`" + source_user + "`@`127.0.0.1`"
    pattern = re.compile(r"(?m)^(?P<head>/\*!\d{5} CREATE\*/[ \t]+/\*!\d{5} DEFINER=)"
                         + re.escape(fixed) + r"(?P<tail>\*/[ \t]+/\*!\d{5} TRIGGER\b)")
    changed, replacements = pattern.subn(lambda match: match["head"] + "CURRENT_USER" + match["tail"], text)
    require(replacements == expected, "UNEXPECTED_MYSQLDUMP_DEFINER_LAYOUT_OR_OWNER")
    reverse = re.compile(r"(?m)^(?P<head>/\*!\d{5} CREATE\*/[ \t]+/\*!\d{5} DEFINER=)CURRENT_USER"
                         r"(?P<tail>\*/[ \t]+/\*!\d{5} TRIGGER\b)")
    restored, count = reverse.subn(lambda match: match["head"] + fixed + match["tail"], changed)
    require(count == replacements and restored == text, "DEFINER_REWRITE_CHANGED_OTHER_BYTES")
    require(not re.search(r"(?mi)^\s*(?:CREATE|DROP)\s+DATABASE\b|^\s*USE\s+", changed),
            "DUMP_MUST_NOT_SELECT_OR_CREATE_A_DATABASE")
    require(not re.search(r"(?i)REFERENCES\s+`" + re.escape(SOURCE_DB) + r"`\s*\.", changed),
            "DUMP_HAS_SOURCE_DATABASE_QUALIFIED_FOREIGN_KEY")
    return changed.encode("utf-8"), replacements


def child_environment(password, disabled_login_path):
    # Do not inherit provider, JVM, production database, or alternate MySQL config variables.
    result = {key: value for key, value in os.environ.items()
              if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP"}}
    result["PATH"] = str(MYSQL_BIN) + os.pathsep + str(Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32")
    result["MYSQL_PWD"] = password
    # These installed 8.0.46 clients lack --no-login-paths. The client library
    # supports this explicit test path; never read the user's login file.
    require(not disabled_login_path.exists(), "DISABLED_LOGIN_PATH_MUST_NOT_EXIST")
    result["MYSQL_TEST_LOGIN_FILE"] = str(disabled_login_path)
    return result


def client(cfg, program):
    binary = MYSQL_BIN / program
    require(binary.is_file(), "VERIFIED_MYSQL_TOOL_NOT_FOUND")
    options = ["--no-defaults"]
    return [str(binary), *options, "--protocol=TCP",
            "--host=" + HOST, "--port=" + str(PORT), "--user=" + cfg["user"],
            "--default-character-set=utf8mb4"]


def run_client(command, cfg, disabled_login_path, stdin=None, stdout=None):
    result = subprocess.run(command, stdin=stdin, stdout=stdout or subprocess.PIPE,
                            stderr=subprocess.PIPE, env=child_environment(cfg["password"], disabled_login_path),
                            cwd=MYSQL_BIN, shell=False, check=False, timeout=300,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    info = {"program": Path(command[0]).name, "exit_code": result.returncode,
            "stderr_bytes": len(result.stderr), "stderr_sha256": digest(result.stderr),
            "password_transport": "CHILD_MYSQL_PWD_ONLY", "defaults_disabled": True,
            "login_paths_disabled_by_flag": "--no-login-paths" in command,
            "login_path_redirected_to_verified_nonexistent_run_file": True}
    if result.returncode != 0:
        raise GuardFailure("MYSQL_CLIENT_FAILED_" + Path(command[0]).stem.upper(), info)
    return info


def dump_command(cfg):
    return client(cfg, "mysqldump.exe") + [
        "--single-transaction", "--no-tablespaces", "--set-gtid-purged=OFF",
        "--skip-lock-tables", "--skip-add-locks", "--skip-add-drop-table",
        "--skip-routines", "--skip-events", "--triggers", "--hex-blob",
        "--column-statistics=0", "--tz-utc", SOURCE_DB]


def dump_without_completion_clock(data):
    lines = data.splitlines(keepends=True)
    clocks = [line for line in lines if line.startswith(b"-- Dump completed on ")]
    require(len(clocks) == 1, "UNEXPECTED_MYSQLDUMP_COMPLETION_CLOCK_LAYOUT")
    return b"".join(line for line in lines if not line.startswith(b"-- Dump completed on "))


def execute(workspace, root, out, proof):
    import pymysql
    private_base = root / ".cache/payment-main-integration-20261112"
    source_cfg = configuration(private_base / SOURCE_PRIVATE, SOURCE_DB)
    target_private = private_base / TARGET_PRIVATE
    require(not target_private.exists(), "RESTORE_PRIVATE_FILE_ALREADY_EXISTS")
    references_path = workspace / "Qingfeng_AI_Music/qf-server/src/test/resources/credit-migration-reference.json"
    references = json.loads(references_path.read_text(encoding="utf-8"))
    require(references["integration"]["version"] == "44"
            and references["integration"]["checksum"] == INTEGRATION_CHECKSUM,
            "FINAL_V44_REFERENCE_REQUIRED")
    proof["reference_manifest_sha256"] = digest(references_path.read_bytes())
    with connect(pymysql, source_cfg) as source:
        proof["source_identity"] = scoped_proof(source, source_cfg)
        no_other_source_clients(source, source_cfg)
        before = snapshot(source, source_cfg, references)
        proof["source_snapshot_before_backup"] = before
        proof["stage"] = "DUMP_SOURCE"
        original = out / "application-r6-original.sql"
        fd = os.open(original, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            command = dump_command(source_cfg)
            proof["dump_client"] = run_client(command, source_cfg, out / "disabled-login-path.cnf", stdout=stream)
        require(original.stat().st_size > 0, "EMPTY_SOURCE_DUMP")
        proof["backup"] = {"path": str(original), "bytes": original.stat().st_size,
                           "sha256": digest(original.read_bytes()), "original_unchanged": True}
        no_other_source_clients(source, source_cfg)
        require(before == snapshot(source, source_cfg, references), "SOURCE_CHANGED_DURING_DUMP")
        rewritten, replacements = rebind_definers(original.read_bytes(), source_cfg["user"])
        restore_sql = out / "application-r6-restore-current-user.sql"
        fd = os.open(restore_sql, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(rewritten)
        proof["restore_copy"] = {"path": str(restore_sql), "bytes": len(rewritten),
                                 "sha256": digest(rewritten), "definer_replacements": replacements,
                                 "only_change": "17 fixed mysqldump trigger DEFINER clauses -> CURRENT_USER",
                                 "reverse_rewrite_equals_original": True}
        proof["stage"] = "CREATE_NEW_RESTORE_RESOURCES"
        admin_path = root / ".cache/payment-mysql-8046-20261111/private-connection.json"
        admin = json.loads(admin_path.read_text(encoding="utf-8-sig"))
        require(admin.get("host") == HOST and admin.get("port") == PORT,
                "ADMIN_CONFIGURATION_INSTANCE_MISMATCH")
        admin_cfg = {"user": "root", "password": admin["admin_password"]}
        target_cfg = {"host": HOST, "port": PORT, "database": TARGET_DB,
                      "user": TARGET_USER, "password": secrets.token_hex(32), "uuid": UUID}
        with connect(pymysql, admin_cfg, admin=True) as administrator:
            identity(administrator)
            require(not query(administrator, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (TARGET_DB,)),
                    "RESTORE_DATABASE_ALREADY_EXISTS")
            require(query(administrator, "SELECT COUNT(*) n FROM mysql.user WHERE User=%s AND Host=%s", (TARGET_USER, HOST))[0]["n"] == 0,
                    "RESTORE_ACCOUNT_ALREADY_EXISTS")
            create_json(target_private, target_cfg)
            proof["restore_private_path"] = str(target_private)
            with administrator.cursor() as cur:
                cur.execute("CREATE DATABASE " + qid(TARGET_DB) + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
                cur.execute("CREATE USER %s@%s IDENTIFIED BY %s", (TARGET_USER, HOST, target_cfg["password"]))
                cur.execute("GRANT ALL PRIVILEGES ON " + qid(TARGET_DB) + ".* TO %s@%s", (TARGET_USER, HOST))
            administrator.commit()
        with connect(pymysql, target_cfg) as target:
            proof["restore_identity"] = scoped_proof(target, target_cfg)
            require(query(target, "SELECT COUNT(*) n FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")[0]["n"] == 0,
                    "RESTORE_SCHEMA_NOT_EMPTY")
            target.rollback()
            proof["restore_empty_verified"] = True
            proof["stage"] = "IMPORT_ONLY_NEW_RESTORE_SCHEMA"
            with restore_sql.open("rb") as stream:
                command = client(target_cfg, "mysql.exe") + ["--skip-reconnect", "--local-infile=0",
                    "--binary-mode", "--database=" + TARGET_DB,
                    "--init-command=SET SESSION time_zone='+00:00'"]
                proof["restore_client"] = run_client(command, target_cfg, out / "disabled-login-path.cnf", stdin=stream)
            identity(target, TARGET_DB)
            restored = snapshot(target, target_cfg, references)
            proof["stage"] = "COMPARE_COMPLETE_RESTORE"
            proof["restore_comparison"] = compare_restored_snapshots(before, restored)
        no_other_source_clients(source, source_cfg)
        require(before == snapshot(source, source_cfg, references), "ORIGINAL_SOURCE_CHANGED_AFTER_RESTORE")
        proof["source_unchanged_after_restore"] = True
    proof["source_snapshot"] = before
    proof["restore_snapshot"] = restored
    proof["status"] = "PASS"
    proof["stage"] = "COMPLETE"


def resume_empty(workspace, root, out, proof, previous):
    """Resume only this script's failed pre-import, still-empty restore cohort."""
    import pymysql
    base = root / ".cache/payment-main-integration-20261112"
    previous = previous.resolve()
    require(previous.parent == base.resolve()
            and re.fullmatch(r"backup-restore-[0-9]{8}T[0-9]{12}Z", previous.name),
            "RESUME_DIRECTORY_OUTSIDE_FIXED_ACCEPTANCE_ROOT")
    prior_path = previous / "results.json"
    prior = json.loads(prior_path.read_text(encoding="utf-8"))
    require(prior.get("status") == "FAIL" and prior.get("stage") == "IMPORT_ONLY_NEW_RESTORE_SCHEMA"
            and prior.get("error", {}).get("code") == "MYSQL_CLIENT_FAILED_MYSQL"
            and prior.get("source_database") == SOURCE_DB and prior.get("restore_database") == TARGET_DB
            and prior.get("restore_empty_verified") is True,
            "RESUME_REQUIRES_THIS_FAILED_EMPTY_IMPORT_COHORT")
    original, restore_sql = previous / "application-r6-original.sql", previous / "application-r6-restore-current-user.sql"
    require(prior["backup"]["path"] == str(original)
            and prior["restore_copy"]["path"] == str(restore_sql)
            and digest(original.read_bytes()) == prior["backup"]["sha256"]
            and digest(restore_sql.read_bytes()) == prior["restore_copy"]["sha256"]
            and prior["restore_copy"]["definer_replacements"] == 17,
            "RESUME_BACKUP_OR_RESTORE_COPY_HASH_MISMATCH")
    source_cfg = configuration(base / SOURCE_PRIVATE, SOURCE_DB)
    target_private = base / TARGET_PRIVATE
    require(prior.get("restore_private_path") == str(target_private), "RESUME_PRIVATE_RESOURCE_PATH_MISMATCH")
    target_cfg = configuration(target_private, TARGET_DB)
    require(target_cfg["user"] == TARGET_USER, "RESUME_ACCOUNT_NOT_CREATED_BY_THIS_COHORT")
    transformed, replacements = rebind_definers(original.read_bytes(), source_cfg["user"])
    require(transformed == restore_sql.read_bytes() and replacements == 17, "RESUME_DEFINER_TRANSFORM_MISMATCH")
    reference_path = workspace / "Qingfeng_AI_Music/qf-server/src/test/resources/credit-migration-reference.json"
    require(digest(reference_path.read_bytes()) == prior["reference_manifest_sha256"], "RESUME_REFERENCE_CHANGED")
    references = json.loads(reference_path.read_text(encoding="utf-8"))
    proof.update({"resume_previous_failure": {"path": str(prior_path), "sha256": digest(prior_path.read_bytes())},
                  "backup": prior["backup"], "restore_copy": prior["restore_copy"],
                  "restore_private_path": str(target_private), "reference_manifest_sha256": prior["reference_manifest_sha256"],
                  "resumed_same_new_empty_resource": True, "stage": "VERIFY_EMPTY_RESUME_RESOURCE"})
    with connect(pymysql, source_cfg) as source, connect(pymysql, target_cfg) as target:
        proof["source_identity"] = scoped_proof(source, source_cfg)
        proof["restore_identity"] = scoped_proof(target, target_cfg)
        require(query(target, "SELECT COUNT(*) n FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")[0]["n"] == 0
                and query(target, "SELECT COUNT(*) n FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()")[0]["n"] == 0,
                "FAILED_RESTORE_HAS_DATA_DO_NOT_REUSE")
        target.rollback()
        proof["restore_empty_verified"] = True
        no_other_source_clients(source, source_cfg)
        before = snapshot(source, source_cfg, references)
        proof["source_snapshot_before_backup"] = before
        # The earlier failed run predates snapshot persistence. A second read-only
        # dump must equal every original byte except mysqldump's completion clock.
        recheck = out / "source-unchanged-recheck.sql"
        fd = os.open(recheck, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            proof["source_recheck_client"] = run_client(dump_command(source_cfg), source_cfg,
                                                        out / "disabled-login-path.cnf", stdout=stream)
        require(dump_without_completion_clock(original.read_bytes()) == dump_without_completion_clock(recheck.read_bytes()),
                "SOURCE_SQL_CHANGED_SINCE_ORIGINAL_BACKUP")
        proof["source_unchanged_dump_proof"] = {"path": str(recheck), "sha256": digest(recheck.read_bytes()),
            "equal_except_dump_completion_clock": True,
            "canonical_sha256": digest(dump_without_completion_clock(recheck.read_bytes()))}
        no_other_source_clients(source, source_cfg)
        require(before == snapshot(source, source_cfg, references), "SOURCE_CHANGED_BEFORE_RESUMED_IMPORT")
        proof["stage"] = "IMPORT_ONLY_SAME_VERIFIED_EMPTY_RESTORE_SCHEMA"
        with restore_sql.open("rb") as stream:
            command = client(target_cfg, "mysql.exe") + ["--skip-reconnect", "--local-infile=0", "--binary-mode",
                "--database=" + TARGET_DB, "--init-command=SET SESSION time_zone='+00:00'"]
            proof["restore_client"] = run_client(command, target_cfg, out / "disabled-login-path.cnf", stdin=stream)
        identity(target, TARGET_DB)
        restored = snapshot(target, target_cfg, references)
        proof["stage"] = "COMPARE_COMPLETE_RESTORE"
        proof["restore_comparison"] = compare_restored_snapshots(before, restored)
        no_other_source_clients(source, source_cfg)
        require(before == snapshot(source, source_cfg, references), "ORIGINAL_SOURCE_CHANGED_AFTER_RESTORE")
    proof.update({"source_snapshot": before, "restore_snapshot": restored,
                  "source_unchanged_after_restore": True, "status": "PASS", "stage": "COMPLETE"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--source-finalized", action="store_true",
                        help="The central source-r6 test process has exited; no source writer will restart")
    parser.add_argument("--resume-empty-run", type=Path,
                        help="Only this script's failed IMPORT cohort, still empty and hash-verified")
    args = parser.parse_args()
    if not args.run or not args.source_finalized:
        print(json.dumps({"status": "NOT_RUN", "required": "--run --source-finalized",
                          "source": SOURCE_DB, "restore": TARGET_DB, "connections_opened": False}))
        return 0
    workspace = Path(__file__).resolve().parents[2]
    root = common_root(workspace)
    base = root / ".cache/payment-main-integration-20261112"
    require(base.is_dir(), "APPROVED_PRIVATE_DIRECTORY_MISSING")
    now = dt.datetime.now(dt.timezone.utc)
    out = base / ("backup-restore-" + now.strftime("%Y%m%dT%H%M%S%fZ"))
    out.mkdir(exist_ok=False)
    proof = {"utc": now.isoformat(), "status": "FAIL", "stage": "PRECHECK",
             "scope": "LOCAL_23316_ISOLATED_APPLICATION_R6_ONLY",
             "source_database": SOURCE_DB, "restore_database": TARGET_DB,
             "source_finalized_explicit": True, "production_access": False,
             "provider_calls": False, "original_database_writes": False,
             "services_started": False, "migration_run": False,
             "resource_cleanup_or_overwrite": False,
             "limitations": ["Synthetic isolated application data, not production cutover acceptance",
                             "Only the new restore schema/user are created; original source facts are retained",
                             "This script does not start a JAR or enable a payment channel",
                             "No old snapshot may overwrite subsequent production financial writes"]}
    code = 1
    try:
        if args.resume_empty_run is None:
            execute(workspace, root, out, proof)
        else:
            resume_empty(workspace, root, out, proof, args.resume_empty_run)
        code = 0
    except Exception as failure:
        proof["error"] = {"class": type(failure).__name__,
                          "code": str(failure) if isinstance(failure, GuardFailure) else "REDACTED_OPERATION_FAILURE"}
        if isinstance(failure, GuardFailure) and failure.details is not None:
            proof["error"]["client_diagnostics"] = failure.details
    path = out / "results.json"
    create_json(path, proof)
    result = {"status": proof["status"], "evidence": str(path)}
    if code == 0:
        result["restore_private"] = proof["restore_private_path"]
    else:
        result["stage"] = proof["stage"]
        result["error_code"] = proof["error"]["code"]
    print(json.dumps(result, ensure_ascii=True))
    return code


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
        raise SystemExit(main())
    except GuardFailure as failure:
        print(json.dumps({"status": "FAIL", "error_code": str(failure)}))
        raise SystemExit(1)
