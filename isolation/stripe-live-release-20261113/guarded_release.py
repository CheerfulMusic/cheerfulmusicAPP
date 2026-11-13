#!/usr/bin/env python3.11
"""Explicit Linux production release stages. Import/--help never operate a server.

This tool does not migrate, activate a ledger, approve an artifact, make a payment,
or restore over the production database. A separate restore-check creates a new
private server-local schema and never starts an application there.
All failures expose fixed codes; subprocess output and private configuration are
never copied to stdout or an evidence file.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
import urllib.request
from urllib.parse import parse_qs, urlsplit

APP = Path('/opt/qingfeng-music')
ETC = Path('/etc/qingfeng-music')
BACKUPS = Path('/var/backups/qingfeng-music')
SERVICE = 'qingfeng-music'
UUID = 'f71f7437-d6a8-11f1-baa2-00163e1ce234'
DATABASE = 'qingfeng_music'
ACCOUNT = 'acct_1UYNvy00Y9YXh9G3'
HEX40 = re.compile(r'[0-9a-f]{40}')
HEX64 = re.compile(r'[0-9a-f]{64}')


class Blocked(RuntimeError):
    pass


def need(value, code):
    if not value:
        raise Blocked(code)


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def exclusive_json(path, value):
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(encoded(value))
        stream.flush()
        os.fsync(stream.fileno())


def secure(path, private=False):
    path = Path(path)
    need(path.is_absolute() and path.is_file() and not path.is_symlink(), 'REGULAR_ABSOLUTE_FILE_REQUIRED')
    info = path.stat()
    need(info.st_uid == 0 and not info.st_mode & 0o022, 'ROOT_OWNED_NONWRITABLE_INPUT_REQUIRED')
    if private:
        need(not info.st_mode & 0o007, 'PRIVATE_FILE_WORLD_ACCESS_FORBIDDEN')
    for parent in path.parents:
        need(not parent.is_symlink() and parent.stat().st_uid == 0
             and not parent.stat().st_mode & 0o022, 'UNTRUSTED_INPUT_PARENT')
    return path


def pinned_json(path, digest):
    need(bool(HEX64.fullmatch(digest or '')), 'EVIDENCE_PIN_REQUIRED')
    path = secure(path)
    need(sha(path) == digest, 'EVIDENCE_DIGEST_MISMATCH')
    value = json.loads(path.read_text(encoding='utf-8'))
    need(isinstance(value, dict), 'EVIDENCE_OBJECT_REQUIRED')
    return value


def receipt(plan, name):
    item = plan['receipts'][name]
    value = pinned_json(item['path'], item['sha256'])
    need(value.get('status') == 'PASS', 'REQUIRED_RECEIPT_NOT_PASS_' + name.upper())
    return value


def command(args, timeout=30, env=None, data=None):
    try:
        run = subprocess.run(args, input=data, env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise Blocked('COMMAND_UNAVAILABLE_OR_TIMEOUT') from None
    need(run.returncode == 0, 'COMMAND_FAILED')
    return run.stdout


def property_map(path):
    result = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')):
            continue
        need(not line.endswith('\\') and '=' in line, 'UNSUPPORTED_PROPERTIES_SYNTAX')
        key, value = line.split('=', 1)
        key, value = key.strip(), value.strip()
        need(key not in result, 'DUPLICATE_PRIVATE_PROPERTY')
        result[key] = value
    return result


def validate_configuration(original, candidate, accepting, canary=False):
    """Preserve all unrelated effective properties; print no values on errors."""
    allowed = ('qingfeng.stripe.', 'qingfeng.credits.writer-fence.',
               'qingfeng.payment.legacy-orders.')
    exact = {'spring.datasource.url', 'spring.flyway.enabled', 'spring.flyway.target',
             'spring.sql.init.mode', 'qingfeng.payment.legacy-orders-read-only',
             'qingfeng.paypal.accepting-new-orders'}
    for key in original.keys() | candidate.keys():
        if original.get(key) != candidate.get(key):
            need(key in exact or key.startswith(allowed), 'UNRELATED_CONFIGURATION_CHANGE')
    required = {
        'qingfeng.stripe.enabled': 'true',
        'qingfeng.stripe.accepting-new-orders': 'true' if accepting else 'false',
        'qingfeng.stripe.environment': 'live',
        'qingfeng.stripe.account-id': ACCOUNT,
        'qingfeng.stripe.business-country': 'GB',
        'qingfeng.stripe.payment-methods.card.enabled': 'true',
        'qingfeng.stripe.payment-methods.apple-pay.enabled': 'true',
        'qingfeng.stripe.payment-methods.wechat-pay.enabled': 'false',
        'qingfeng.credits.writer-fence.enabled': 'true',
        'qingfeng.credits.writer-fence.expected-database': DATABASE,
        'qingfeng.credits.writer-fence.expected-port': '3306',
        'spring.flyway.enabled': 'false',
        'spring.flyway.target': '44',
        'spring.sql.init.mode': 'never',
        'qingfeng.payment.legacy-orders-read-only': 'true',
        'qingfeng.paypal.accepting-new-orders': 'false',
    }
    need(all(candidate.get(k) == v for k, v in required.items()), 'RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH')
    owners = [v.strip() for v in candidate.get('qingfeng.stripe.canary-owner-ids', '').split(',') if v.strip()]
    if canary:
        need(not accepting and 1 <= len(owners) <= 3 and len(owners) == len(set(owners))
             and all(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', v) for v in owners),
             'EXPLICIT_MAX_THREE_CANARY_OWNERS_REQUIRED')
    else:
        need(not owners, 'CANARY_OWNERS_MUST_BE_EMPTY')
    need(bool(re.fullmatch(r'(?:sk|rk)_live_[A-Za-z0-9]+', candidate.get('qingfeng.stripe.api-key', ''))),
         'LITERAL_LIVE_API_CREDENTIAL_REQUIRED')
    need(bool(re.fullmatch(r'whsec_[A-Za-z0-9]+', candidate.get('qingfeng.stripe.webhook-secret', ''))),
         'LITERAL_SIGNING_CREDENTIAL_REQUIRED')
    url = candidate.get('spring.datasource.url', '')
    need(url.startswith('jdbc:mysql://'), 'JDBC_URL_REQUIRED')
    parsed = urlsplit(url.removeprefix('jdbc:'))
    old = urlsplit(original.get('spring.datasource.url', '').removeprefix('jdbc:'))
    need((parsed.hostname, parsed.port or 3306, parsed.path) == ('127.0.0.1', 3306, '/' + DATABASE)
         and (parsed.hostname, parsed.port or 3306, parsed.path) == (old.hostname, old.port or 3306, old.path),
         'DATABASE_ENDPOINT_CHANGED')
    params = parse_qs(parsed.query)
    need(params.get('connectionTimeZone') == ['UTC']
         and params.get('forceConnectionTimeZoneToSession') == ['true'], 'ACTUAL_UTC_SESSION_PARAMETERS_REQUIRED')
    old_params = parse_qs(old.query)
    mutable = {'serverTimezone', 'connectionTimeZone', 'forceConnectionTimeZoneToSession'}
    need({k: v for k, v in params.items() if k not in mutable}
         == {k: v for k, v in old_params.items() if k not in mutable}, 'UNRELATED_JDBC_PARAMETER_CHANGE')


def validate_plan(plan):
    need(plan.get('version') == 1 and plan.get('scope') == 'stripe-card-apple-pay', 'PLAN_SCOPE_MISMATCH')
    need(bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,70}', plan.get('releaseId', ''))), 'RELEASE_ID_INVALID')
    need(plan.get('database') == {'uuid': UUID, 'schema': DATABASE, 'port': 3306}, 'PRODUCTION_IDENTITY_MISMATCH')
    for section in ('expected', 'candidate'):
        need(bool(HEX40.fullmatch(plan[section].get('sourceCommit', '')))
             and bool(HEX64.fullmatch(plan[section].get('jarSha256', ''))), 'ARTIFACT_IDENTITY_INVALID')
    need(plan['candidate']['sourceCommit'] != plan['expected']['sourceCommit'], 'UNCHANGED_RELEASE_NOT_A_CUTOVER')
    directory = Path(plan['candidate']['directory'])
    need(directory == APP / 'releases' / plan['releaseId'], 'CANDIDATE_DIRECTORY_MISMATCH')
    old = Path(plan['expected']['directory'])
    need(old.parent == APP / 'releases' and old != directory, 'EXPECTED_RELEASE_DIRECTORY_INVALID')
    units = plan.get('writerUnits', [])
    need(plan.get('writerInventoryVerified') is True and SERVICE + '.service' in units
         and len(units) == len(set(units))
         and all(re.fullmatch(r'[A-Za-z0-9_.@-]+\.(?:service|timer)', v) for v in units),
         'EXACT_WRITER_AND_SELF_HEAL_INVENTORY_REQUIRED')


def current_matches(plan, candidate=False):
    target = plan['candidate' if candidate else 'expected']
    current = APP / 'current'
    need(current.is_symlink() and current.resolve() == Path(target['directory']), 'CURRENT_CHANGED')
    need((current / 'source-commit.txt').read_text().strip() == target['sourceCommit']
         and sha(current / 'app.jar') == target['jarSha256'], 'RUNNING_RELEASE_FILES_CHANGED')


def stopped(plan):
    for unit in plan['writerUnits']:
        state = command(['systemctl', 'show', unit, '--property=ActiveState', '--value']).decode().strip()
        need(state in ('inactive', 'failed'), 'OLD_WRITER_OR_SELF_HEAL_STILL_ACTIVE')
        pid = command(['systemctl', 'show', unit, '--property=MainPID', '--value']).decode().strip()
        need(pid in ('', '0'), 'OLD_WRITER_PROCESS_STILL_PRESENT')


def stop_units(plan):
    ancestors = {os.getpid()}
    pid = os.getpid()
    while pid > 1:
        fields = (Path('/proc') / str(pid) / 'status').read_text().splitlines()
        parent = next((int(line.split(':', 1)[1]) for line in fields if line.startswith('PPid:')), 0)
        if parent in ancestors or parent < 1:
            break
        ancestors.add(parent)
        pid = parent
    for unit in plan['writerUnits']:
        pid = command(['systemctl', 'show', unit, '--property=MainPID', '--value']).decode().strip()
        need(not pid or not pid.isdigit() or int(pid) not in ancestors, 'REFUSE_TO_STOP_RELEASE_EXECUTOR_OR_ANCESTOR')
    for unit in plan['writerUnits']:
        command(['systemctl', 'stop', unit], timeout=90)
    stopped(plan)


def mysql(sql, database=DATABASE, timeout=90):
    # The already-approved root socket account is used only on this host. No
    # password appears in command arguments, output, or a copied developer file.
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8',
           'MYSQL_HISTFILE': '/dev/null', 'MYSQL_TEST_LOGIN_FILE': '/dev/null'}
    return command(['mysql', '--no-defaults', '--protocol=SOCKET', '--user=root',
                    '--skip-reconnect', '--batch', '--raw', '--skip-column-names',
                    '--default-character-set=utf8mb4', '--database=' + database],
                   env=env, data=("SET SESSION time_zone='+00:00';\n" + sql + '\n').encode(), timeout=timeout)


def json_rows(sql, database=DATABASE):
    return [json.loads(row) for row in mysql(sql, database).decode().splitlines() if row.strip()]


def qid(value):
    need(bool(re.fullmatch(r'[A-Za-z0-9_]{1,64}', value)), 'SQL_IDENTIFIER_OUTSIDE_APPROVED_SCHEMA_CONTRACT')
    return '`' + value + '`'


def source_objects():
    return json_rows("SELECT JSON_OBJECT('views',(SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE()),"
                     "'triggers',(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()),"
                     "'routines',(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()),"
                     "'events',(SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE()));")[0]


def fingerprint(database):
    tables = json_rows("SELECT JSON_OBJECT('table',TABLE_NAME) FROM information_schema.TABLES "
                       "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;", database)
    result = {}
    for table in tables:
        name = table['table']
        qid(name)  # Reject unsafe metadata identifiers before any SQL interpolation.
        columns = json_rows("SELECT JSON_OBJECT('column',COLUMN_NAME) FROM information_schema.COLUMNS "
                            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + name + "' ORDER BY ORDINAL_POSITION;", database)
        parts = ["IF(" + qid(c['column']) + " IS NULL,'N',CONCAT('V',HEX(CAST(" + qid(c['column']) + " AS BINARY))))" for c in columns]
        hashes = mysql("SELECT SHA2(CONCAT_WS('|'," + ','.join(parts) + "),256) FROM " + qid(name) + " ORDER BY 1;", database)
        need(len(hashes) <= 128 * 1024 * 1024 and all(re.fullmatch(rb'[0-9a-f]{64}', r) for r in hashes.splitlines()),
             'TABLE_FINGERPRINT_BUDGET_OR_ENCODING_FAILURE')
        ddl = mysql('SHOW CREATE TABLE ' + qid(name) + ';', database)
        result[name] = {'rows': len(hashes.splitlines()), 'rowsSha256': hashlib.sha256(hashes).hexdigest(),
                        'ddlSha256': hashlib.sha256(ddl).hexdigest()}
    return result


def root_observation(plan):
    # Every same-schema connection is visible; an application account without
    # PROCESS cannot substitute for this root observation.
    grants = mysql('SHOW GRANTS FOR CURRENT_USER;').decode()
    need(any(' ON *.* ' in line and ('ALL PRIVILEGES' in line or 'PROCESS' in line)
             for line in grants.splitlines()), 'OBSERVER_LACKS_GLOBAL_PROCESS_PRIVILEGE')
    row = json_rows("SELECT JSON_OBJECT('uuid',@@server_uuid,'schema',DATABASE(),'port',@@port,"
                    "'otherConnections',(SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=DATABASE() AND ID<>CONNECTION_ID()));")[0]
    need({k: row[k] for k in ('uuid', 'schema', 'port')} == plan['database'], 'ROOT_OBSERVER_DATABASE_IDENTITY_MISMATCH')
    need(row['otherConnections'] == 0, 'OTHER_SOURCE_SCHEMA_CONNECTIONS_REMAIN')
    return row


def backup(plan, out):
    check_release(plan)
    check_build_receipt(plan)
    current_matches(plan)
    need(not out.exists(), 'BACKUP_EXISTS_NEVER_OVERWRITE')
    out.mkdir(mode=0o700, parents=True)
    stop_units(plan)
    current_matches(plan)
    cfg = property_map(ETC / 'application.properties')
    parsed = urlsplit(cfg.get('spring.datasource.url', '').removeprefix('jdbc:'))
    need((parsed.hostname, parsed.port or 3306, parsed.path) == ('127.0.0.1', 3306, '/' + DATABASE),
         'BACKUP_DATASOURCE_MISMATCH')
    observation = root_observation(plan)
    objects = source_objects()
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8',
           'MYSQL_HISTFILE': '/dev/null', 'MYSQL_TEST_LOGIN_FILE': '/dev/null'}
    base = ['--no-defaults', '--protocol=SOCKET', '--user=root', '--connect-timeout=5']
    identity = {k: observation[k] for k in ('uuid', 'schema', 'port')}
    # Dump stays on the approved production host in a private directory. The tool
    # does not export real user data to an ordinary developer/test database.
    dump = out / 'database.sql'
    with dump.open('xb') as target:
        os.chmod(dump, 0o600)
        process = subprocess.run(['mysqldump', *base, '--single-transaction', '--quick',
                                  '--no-tablespaces', '--hex-blob', '--triggers', '--routines', '--events',
                                  '--set-gtid-purged=OFF', DATABASE], env=env,
                                 stdout=target, stderr=subprocess.PIPE, timeout=300)
    need(process.returncode == 0 and dump.stat().st_size > 1000, 'DATABASE_DUMP_FAILED')
    observed = fingerprint(DATABASE)
    need('credit_ledger' in observed and 'flyway_schema_history' in observed, 'REQUIRED_BACKUP_TABLES_MISSING')
    root_observation(plan)
    shutil.copytree(Path(plan['expected']['directory']), out / 'release', symlinks=False)
    shutil.copytree(ETC, out / 'configuration', symlinks=False)
    command(['tar', '--acls', '--xattrs', '-czf', str(out / 'application-data.tar.gz'),
             '-C', '/', 'var/lib/qingfeng-music'], timeout=300)
    # No configuration values or SQL data enter the receipt.
    unit = command(['systemctl', 'cat', SERVICE])
    with (out / 'systemd.unit').open('xb') as target:
        target.write(unit)
    for file in out.rglob('*'):
        if file.is_file():
            os.chmod(file, 0o600)
        elif file.is_dir():
            os.chmod(file, 0o700)
    value = {'status': 'PASS', 'scope': 'BACKUP_CREATED_NOT_RESTORE_ACCEPTED',
             'database': identity, 'databaseSha256': sha(dump),
             'applicationDataSha256': sha(out / 'application-data.tar.gz'),
             'tableFingerprint': observed, 'programmableObjects': objects,
             'oldJarSha256': sha(out / 'release/app.jar'), 'oldWritersStopped': True,
             'sourceCommit': plan['expected']['sourceCommit'],
             'recordedUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    exclusive_json(out / 'backup-receipt.json', value)
    return {'status': 'BACKUP_CREATED_APPLICATION_STOPPED', 'backupDirectory': str(out),
            'receiptSha256': sha(out / 'backup-receipt.json'),
            'restoreAcceptance': 'NOT_TESTED', 'databaseMigration': 'NOT_PERFORMED'}


def restore_check(plan, out):
    """Restore to a new private schema on the same host. Never start an app."""
    stopped(plan)
    current_matches(plan)
    root_observation(plan)
    backup_receipt = json.loads(secure(out / 'backup-receipt.json').read_text())
    dump = secure(out / 'database.sql', private=True)
    need(backup_receipt.get('status') == 'PASS' and sha(dump) == backup_receipt['databaseSha256'], 'FINAL_BACKUP_DIGEST_MISMATCH')
    need(backup_receipt.get('programmableObjects') == {'views': 0, 'triggers': 0, 'routines': 0, 'events': 0},
         'PROGRAMMABLE_OBJECT_RESTORE_NEEDS_SEPARATE_SIDE_EFFECT_REVIEW')
    target = plan.get('restoreDatabase', '')
    need(bool(re.fullmatch(r'qf_stripe_restore_[a-z0-9_]{1,40}', target)) and target != DATABASE,
         'EXPLICIT_PRIVATE_RESTORE_SCHEMA_REQUIRED')
    # Never drop, truncate, overwrite or reuse a schema, including failed runs.
    existing = mysql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + target + "';")
    need(not existing.strip(), 'RESTORE_SCHEMA_EXISTS_NEVER_OVERWRITE')
    source = fingerprint(DATABASE)
    need(source == backup_receipt['tableFingerprint'], 'PRODUCTION_SOURCE_CHANGED_SINCE_BACKUP')
    raw = dump.read_bytes()
    need(not re.search(rb'^\s*(?:USE\s|CREATE\s+DATABASE\b)', raw, re.MULTILINE | re.IGNORECASE),
         'DUMP_CAN_SELECT_OR_CREATE_ANOTHER_DATABASE')
    # A root-only server schema is not granted to the application/developer
    # account. No application is started and no data leaves this host.
    mysql('CREATE DATABASE ' + qid(target) + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
    mysql(raw.decode('utf-8'), target, timeout=300)
    restored = fingerprint(target)
    need(restored == source, 'RESTORED_SCHEMA_OR_ROW_FINGERPRINT_MISMATCH')
    root_observation(plan)
    need(fingerprint(DATABASE) == source, 'RESTORE_MODIFIED_SOURCE_DATABASE')
    result = {'status': 'PASS', 'scope': 'ACTUAL_SERVER_LOCAL_DATA_RESTORE_ONLY',
              'restoreDatabase': target, 'backupDatabaseSha256': backup_receipt['databaseSha256'],
              'actualRestoreExecuted': True, 'dataIntegrityVerified': True,
              'sourceUnchanged': True, 'fullBaseTableCount': len(source),
              'creditJournalVerified': restored['credit_ledger'] == source['credit_ledger'],
              'applicationStarted': False, 'supplierCalls': False,
              'migration44': 'NOT_PERFORMED', 'compatibleApplicationRecovery': 'NOT_TESTED',
              'recordedUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    exclusive_json(out / 'actual-data-restore-receipt.json', result)
    return result


def check_release(plan):
    candidate = plan['candidate']
    directory = Path(candidate['directory'])
    need(sha(secure(directory / 'app.jar')) == candidate['jarSha256'], 'CANDIDATE_ARTIFACT_MISMATCH')
    need((secure(directory / 'source-commit.txt')).read_text().strip() == candidate['sourceCommit'],
         'CANDIDATE_SOURCE_MISMATCH')
    need(sha(secure(directory / 'release-contract.json')) == candidate['contractSha256'], 'CONTRACT_PIN_MISMATCH')
    guard = secure(APP / 'verify-release.py')
    need(sha(guard) == plan['guardSha256'] and sha(secure(APP / 'release_gate.py')) == plan['gateSha256'],
         'INSTALLED_GUARD_DIFFERS_FROM_REVIEWED_SOURCE')
    policy = secure(plan['approvedPolicy'])
    need(sha(policy) == plan['approvedPolicySha256'], 'APPROVED_POLICY_PIN_MISMATCH')
    # The host's default python3 can be 3.6. Keep the explicitly selected,
    # version-checked interpreter for the release gate and its 3.9+ APIs.
    command([sys.executable, str(guard), '--release', str(directory), '--policy', str(policy)], timeout=90)


def check_install_receipts(plan, out):
    local = json.loads(secure(out / 'backup-receipt.json').read_text())
    need(local.get('status') == 'PASS' and local.get('oldWritersStopped') is True
         and local.get('database') == plan['database'] and sha(out / 'database.sql') == local['databaseSha256'],
         'FINAL_BACKUP_NOT_VALID')
    restore = receipt(plan, 'restore')
    need(restore.get('backupDatabaseSha256') == local['databaseSha256']
         and restore.get('candidateJarSha256') == plan['candidate']['jarSha256']
         and all(restore.get(k) is True for k in ('actualRestoreExecuted', 'dataIntegrityVerified',
             'migration44Verified', 'compatibleRecoveryVerified')), 'REAL_BACKUP_RESTORE_ACCEPTANCE_REQUIRED')
    cutover = receipt(plan, 'cutover')
    need(cutover.get('state') == 'ACTIVE' and cutover.get('protocol') == 3
         and cutover.get('databaseUuid') == UUID and cutover.get('database') == DATABASE
         and cutover.get('port') == 3306
         and cutover.get('candidateJarSha256') == plan['candidate']['jarSha256'], 'ACTIVE_CUTOVER_RECEIPT_MISMATCH')
    need(cutover.get('sourceSha256') == restore.get('sourceSha256')
         and bool(HEX64.fullmatch(cutover.get('sourceSha256', ''))), 'RESTORED_AND_CUTOVER_SOURCE_MISMATCH')
    for name in ('oldWriterStopped', 'backupRestore', 'rollbackCompatibility', 'legacyOrderIsolation'):
        need(bool(HEX64.fullmatch(cutover.get(name + 'EvidenceSha256', ''))), 'CUTOVER_PROOF_PIN_MISSING')
    check_build_receipt(plan)


def check_build_receipt(plan):
    build = receipt(plan, 'build')
    need(build.get('sourceCommit') == plan['candidate']['sourceCommit']
         and build.get('jarSha256') == plan['candidate']['jarSha256']
         and all(build.get(k) == 'PASS' for k in ('fetchAll', 'mergedMainAndDev', 'frontendTest',
                     'frontendBuild', 'backendVerify', 'realMySql', 'negativeAndFrozenAgentRegression')),
         'SAME_CANDIDATE_RELEASE_CHECKS_REQUIRED')


def atomic_copy(source, target, mode, group=None):
    temp = target.with_name(target.name + '.stripe-release-pending')
    need(not temp.exists(), 'PENDING_FILE_ALREADY_EXISTS')
    with Path(source).open('rb') as origin, temp.open('xb') as destination:
        os.chmod(temp, mode)
        shutil.copyfileobj(origin, destination)
        destination.flush()
        os.fsync(destination.fileno())
    if group is not None:
        shutil.chown(temp, user='root', group=group)
    os.replace(temp, target)
    descriptor = os.open(target.parent, os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def startup():
    command(['systemctl', 'start', SERVICE], timeout=90)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        state = command(['systemctl', 'show', SERVICE, '--property=ActiveState', '--value']).decode().strip()
        if state == 'active':
            try:
                with urllib.request.urlopen('http://127.0.0.1:8080/actuator/health', timeout=3) as response:
                    payload = json.loads(response.read(128 * 1024))
                if payload.get('status') == 'UP':
                    return
            except (OSError, ValueError):
                pass
        time.sleep(1)
    command(['systemctl', 'stop', SERVICE], timeout=90)
    raise Blocked('NEW_APPLICATION_STARTUP_FAILED_CURRENT_KEPT_COMPATIBLE')


def running_artifact_matches(plan):
    pid = command(['systemctl', 'show', SERVICE, '--property=MainPID', '--value']).decode().strip()
    need(pid.isdigit() and int(pid) > 0, 'NEW_SERVICE_PID_MISSING')
    matches = False
    for descriptor in (Path('/proc') / pid / 'fd').iterdir():
        try:
            target = os.readlink(descriptor)
            if target.startswith(str(APP) + '/') and target.endswith('/app.jar'):
                matches = matches or sha(descriptor) == plan['candidate']['jarSha256']
        except OSError:
            continue
    need(matches, 'ACTUAL_PROCESS_ARTIFACT_NOT_VERIFIED')


def validate_canary_funding(funding, jar_sha):
    # No temporary price is invented here. Current USD catalog snapshots
    # remain 9.90/25.90/49.90. An authorization for USD0.01 cannot enable a
    # test Checkout whose actual catalog price is higher.
    amount = funding.get('selectedPackageAmountMinor')
    limit = funding.get('approvedMaximumMinor')
    need(funding.get('candidateJarSha256') == jar_sha
         and funding.get('currency') == 'USD' and type(amount) is int
         and amount in (990, 2590, 4990) and type(limit) is int and amount <= limit
         and type(funding.get('approvedTransactionLimit')) is int
         and funding.get('approvedTransactionLimit') == 2,
         'ACTUAL_CANARY_QUOTE_EXCEEDS_APPROVED_FUNDS_SCOPE')


def install(plan, out, operation):
    check_release(plan)
    check_install_receipts(plan, out)
    current_matches(plan, candidate=operation in ('canary', 'open', 'recover'))
    if operation == 'canary':
        validate_canary_funding(receipt(plan, 'canaryFunding'), plan['candidate']['jarSha256'])
    if operation == 'open':
        business = receipt(plan, 'business')
        need(business.get('jarSha256') == plan['candidate']['jarSha256']
             and business.get('accountId') == ACCOUNT and business.get('environment') == 'live'
             and all(business.get(k) == 'PASS' for k in ('signedWebhook', 'creditCardPaid',
                         'applePayDevicePaid', 'creditedExactlyOnce', 'ownershipAndCurrency')),
             'REAL_CARD_AND_APPLE_PAY_BUSINESS_ACCEPTANCE_REQUIRED')
    config_key = 'openProperties' if operation == 'open' else 'canaryProperties' if operation == 'canary' else 'closedProperties'
    configuration = secure(plan[config_key], private=True)
    old = property_map(out / 'configuration/application.properties')
    new = property_map(configuration)
    validate_configuration(old, new, operation == 'open', operation == 'canary')
    stop_units(plan)
    current_matches(plan, candidate=operation in ('canary', 'open', 'recover'))
    atomic_copy(plan['approvedPolicy'], ETC / 'release-policy.json', 0o644)
    atomic_copy(configuration, ETC / 'application.properties', 0o640, group='qfmusic')
    if operation == 'install':
        link = APP / ('.current-' + plan['releaseId'])
        need(not link.exists() and not link.is_symlink(), 'PENDING_CURRENT_LINK_EXISTS')
        link.symlink_to(plan['candidate']['directory'], target_is_directory=True)
        os.replace(link, APP / 'current')
        descriptor = os.open(APP, os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    startup()
    try:
        current_matches(plan, candidate=True)
        running_artifact_matches(plan)
    except (Blocked, OSError):
        command(['systemctl', 'stop', SERVICE], timeout=90)
        raise
    return {'status': 'OPEN_WITH_PRIOR_BUSINESS_ACCEPTANCE' if operation == 'open' else 'CANARY_ONLY_AWAITING_USER_PAYMENT' if operation == 'canary' else 'STARTED_NEW_PURCHASES_CLOSED',
            'sourceCommit': plan['candidate']['sourceCommit'],
            'jarSha256': plan['candidate']['jarSha256'],
            'serviceHealth': 'UP', 'businessAcceptance': 'PREVIOUSLY_PINNED' if operation == 'open' else 'NOT_TESTED',
            'otherWriterUnits': 'REMAIN_STOPPED_PENDING_SEPARATE_REVIEW'}


def main():
    # This check precedes argument/file parsing, locks and every server action.
    # The module itself is parseable on 3.6 so a wrong launcher fails with this
    # fixed diagnostic rather than partially executing a release phase.
    if sys.version_info < (3, 9):
        print(json.dumps({'status': 'BLOCKED', 'reason': 'PYTHON_3_9_OR_NEWER_REQUIRED',
                          'productionMutation': False}))
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('inspect', 'backup', 'restore-check', 'install', 'canary', 'open', 'recover'))
    parser.add_argument('--plan', required=True)
    parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--execute-approved', action='store_true')
    args = parser.parse_args()
    try:
        need(sys.platform == 'linux' and os.geteuid() == 0, 'APPROVED_LINUX_ROOT_EXECUTION_REQUIRED')
        plan = pinned_json(args.plan, args.plan_sha256)
        validate_plan(plan)
        import fcntl
        locks = []
        # Same release lock as daily publication, plus the existing backup lock.
        for name in ('qingfeng-release.lock', 'qingfeng-music-backup.lock'):
            handle = open('/run/lock/' + name, 'a')
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            locks.append(handle)
        out = BACKUPS / ('stripe-live-' + plan['releaseId'])
        if args.operation == 'inspect':
            current_matches(plan)
            check_release(plan)
            value = {'status': 'READ_ONLY_INSPECTION_PASSED', 'productionWrites': False,
                     'sourceCommit': plan['candidate']['sourceCommit']}
        else:
            need(args.execute_approved, 'EXPLICIT_EXECUTION_FLAG_REQUIRED')
            value = backup(plan, out) if args.operation == 'backup' else restore_check(plan, out) if args.operation == 'restore-check' else install(plan, out, args.operation)
        print(json.dumps(value))
        return 0
    except (Blocked, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'status': 'BLOCKED', 'reason': str(error) if isinstance(error, Blocked)
                          else type(error).__name__, 'automaticOldJarRollback': False,
                          'automaticDatabaseRestore': False}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
