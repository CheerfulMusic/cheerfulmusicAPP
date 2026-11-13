#!/usr/bin/env python3.11
"""Explicit Linux production release stages. Import/--help never operate a server.

Explicit operator stages can validate/migrate canonical core44 to financial45,
and apply/activate/verify an approved immutable historical carry-forward plan.
They never start Spring or call a payment supplier. The tool does not approve an
artifact or restore over production. restore-check creates a new private
server-local schema and never starts an application there.
All failures expose fixed codes; subprocess output and private configuration are
never copied to stdout or an evidence file.
"""
import argparse
import datetime as dt
import hashlib
import io
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
import zipfile
import zlib
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
CHANNELS = frozenset(('card', 'apple_pay', 'wechat_pay', 'paypal'))
STRIPE_CHANNELS = CHANNELS - {'paypal'}
USD_QUOTES = (990, 2590, 4990)
CNY_QUOTES = (6880, 17880, 33880)
CORE_VERSION = 44
FINANCIAL_VERSION = 45
FINANCIAL_SQL_SHA256 = '3dd5cebf19dee960ecefb2239668139918d363496fe87710f817c5b3a3241685'
OPERATOR_PACKAGE = 'com.qingfeng.music.infrastructure.persistence.credits.'
MIGRATION_CLASS = 'com.qingfeng.music.server.config.FinancialMigrationCommand'
CARRY_CLASS = OPERATOR_PACKAGE + 'CreditCarryForwardCommand'
CORE_MANIFEST_SHA256 = '736294575e354900170114f8eb9568abaf15223a0bf7c9872d314d65559905e1'
ROOT_CRONTAB = Path('/var/spool/cron/root')
MEDIA_SCRIPT = Path('/opt/qingfeng-music/monitor/media_health_check.py')
MEDIA_ENTRY = '*/10 * * * * /usr/bin/python3 /opt/qingfeng-music/monitor/media_health_check.py'
MEDIA_PAUSE_MARKER = '# QINGFENG_RELEASE_PAUSED '


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


def command(args, timeout=30, env=None, data=None, cwd=None):
    try:
        run = subprocess.run(args, input=data, env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, timeout=timeout, check=False, cwd=cwd)
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


def validate_configuration(original, candidate, accepting, canary=False, channels=None, paypal_merchant_id=None):
    """Preserve all unrelated effective properties; print no values on errors."""
    allowed = ('qingfeng.stripe.', 'qingfeng.credits.writer-fence.', 'qingfeng.payment.acceptance.',
               'qingfeng.payment.legacy-orders.')
    exact = {'spring.datasource.url', 'spring.flyway.enabled', 'spring.flyway.target',
             'spring.sql.init.mode', 'qingfeng.payment.legacy-orders-read-only',
             'qingfeng.paypal.accepting-new-orders'}
    explicit_channels = channels is not None
    if explicit_channels:
        channels = channel_set(channels)
        need(not accepting or not canary, 'PUBLIC_AND_CANARY_STAGES_ARE_EXCLUSIVE')
        need(accepting or canary or not channels, 'CLOSED_STAGE_CANNOT_ENABLE_CHANNELS')
        exact.add('qingfeng.paypal.canary-owner-ids')
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
        'spring.flyway.target': str(FINANCIAL_VERSION),
        'spring.sql.init.mode': 'never',
        'qingfeng.payment.legacy-orders-read-only': 'true',
        'qingfeng.paypal.accepting-new-orders': 'false',
    }
    if explicit_channels:
        required.update({
            'qingfeng.stripe.accepting-new-orders': 'true' if accepting and channels & STRIPE_CHANNELS else 'false',
            'qingfeng.stripe.payment-methods.card.enabled': 'true' if 'card' in channels else 'false',
            'qingfeng.stripe.payment-methods.apple-pay.enabled': 'true' if 'apple_pay' in channels else 'false',
            'qingfeng.stripe.payment-methods.wechat-pay.enabled': 'true' if 'wechat_pay' in channels else 'false',
            'qingfeng.paypal.accepting-new-orders': 'true' if accepting and 'paypal' in channels else 'false',
        })
    need(all(candidate.get(k) == v for k, v in required.items()), 'RUNTIME_MODE_OR_FINANCIAL_GUARD_MISMATCH')
    owner_value = candidate.get('qingfeng.stripe.canary-owner-ids', '')
    owners = [v.strip() for v in owner_value.split(',') if v.strip()]
    if canary:
        need(not accepting and 1 <= len(owners) <= 3 and len(owners) == len(set(owners))
             and all(v.strip() for v in owner_value.split(','))
             and all(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', v) for v in owners),
             'EXPLICIT_MAX_THREE_CANARY_OWNERS_REQUIRED')
    else:
        need(not owners and not owner_value.strip(), 'CANARY_OWNERS_MUST_BE_EMPTY')
    paypal_owner_value = candidate.get('qingfeng.paypal.canary-owner-ids', '')
    paypal_owners = [v.strip() for v in paypal_owner_value.split(',') if v.strip()]
    if explicit_channels and canary and 'paypal' in channels:
        need(paypal_owners == owners and all(v.strip() for v in paypal_owner_value.split(',')),
             'PAYPAL_CANARY_MUST_USE_SAME_EXPLICIT_OWNERS')
    else:
        need(not paypal_owners and not paypal_owner_value.strip(), 'PAYPAL_CANARY_OWNERS_MUST_BE_EMPTY')
    acceptance = candidate.get('qingfeng.payment.acceptance.enabled', 'false')
    need(acceptance in ('true', 'false'), 'EXPLICIT_ACCEPTANCE_OFFER_MODE_REQUIRED')
    if acceptance == 'true':
        need(canary and not accepting and len(owners) == 1, 'PRIVATE_ACCEPTANCE_ONLY_SINGLE_OWNER_CANARY')
        scope = acceptance_configuration(candidate)
        need(scope['ownerId'] == owners[0], 'PRIVATE_ACCEPTANCE_OWNER_MISMATCH')
    if accepting:
        need(acceptance == 'false' and all(not candidate.get('qingfeng.payment.acceptance.' + key, '').strip()
             for key in ('owner-id', 'nonce', 'expires-at')), 'PUBLIC_OPEN_MUST_CLEAR_PRIVATE_ACCEPTANCE')
    if explicit_channels and 'wechat_pay' in channels:
        need(all(candidate.get('qingfeng.stripe.prices.cny.' + name) == price
                 for name, price in (('starter', '68.80'), ('standard', '178.80'), ('plus', '338.80'))),
             'APPROVED_WECHAT_CNY_QUOTES_REQUIRED')
    if explicit_channels and 'paypal' in channels:
        need(isinstance(paypal_merchant_id, str) and bool(re.fullmatch(r'[A-Za-z0-9]{8,64}', paypal_merchant_id)),
             'EXPLICIT_PAYPAL_MERCHANT_REQUIRED')
        need(all(candidate.get(k) == v for k, v in {
            'qingfeng.paypal.enabled': 'true', 'qingfeng.paypal.environment': 'live',
            'qingfeng.paypal.live-enabled': 'true', 'qingfeng.paypal.public-live-enabled': 'true',
            'qingfeng.paypal.return-base-url': 'https://cheerfulmusic.com/account',
            'qingfeng.paypal.merchant-id': paypal_merchant_id,
        }.items()), 'LIVE_PAYPAL_IDENTITY_AND_CALLBACK_GUARDS_REQUIRED')
        need(all(isinstance(candidate.get(k), str) and bool(candidate[k].strip())
                 and '${' not in candidate[k] and not any(c.isspace() for c in candidate[k])
                 for k in ('qingfeng.paypal.client-id', 'qingfeng.paypal.client-secret', 'qingfeng.paypal.webhook-id')),
             'LITERAL_PAYPAL_CREDENTIALS_AND_WEBHOOK_ID_REQUIRED')
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


def channel_set(values):
    need(isinstance(values, (list, tuple, frozenset)) and all(isinstance(v, str) for v in values)
         and len(values) == len(set(values)) and set(values) <= CHANNELS, 'EXPLICIT_UNIQUE_KNOWN_CHANNELS_REQUIRED')
    channels = frozenset(values)
    need('apple_pay' not in channels or 'card' in channels, 'APPLE_PAY_REQUIRES_CARD_CHANNEL')
    return channels


def plan_channels(plan, stage):
    """Version 1 remains Stripe-only; version 2 cannot silently omit a target channel."""
    if plan.get('version') == 1:
        need(plan.get('scope') == 'stripe-card-apple-pay', 'PLAN_SCOPE_MISMATCH')
        return None
    need(plan.get('version') == 2 and plan.get('scope') == 'payment-four-channel', 'PLAN_SCOPE_MISMATCH')
    need(channel_set(plan.get('releaseChannels')) == CHANNELS, 'ALL_FOUR_RELEASE_CHANNELS_REQUIRED')
    stages = plan.get('stageChannels')
    need(isinstance(stages, dict) and set(stages) == {'closed', 'canary', 'open'}, 'EXPLICIT_STAGE_CHANNEL_CONTRACT_REQUIRED')
    closed, canary, opened = (channel_set(stages[name]) for name in ('closed', 'canary', 'open'))
    need(not closed and bool(canary) and opened == CHANNELS, 'CLOSED_CANARY_OPEN_CHANNEL_CONTRACT_MISMATCH')
    need(isinstance(plan.get('paypalMerchantId'), str)
         and bool(re.fullmatch(r'[A-Za-z0-9]{8,64}', plan['paypalMerchantId'])), 'EXPLICIT_PAYPAL_MERCHANT_REQUIRED')
    return {'closed': closed, 'canary': canary, 'open': opened}[stage]


def validate_plan(plan):
    plan_channels(plan, 'closed')
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
    if 'operator' in plan:
        need(plan.get('schemaVersions') == {'core': CORE_VERSION, 'financial': FINANCIAL_VERSION, 'protocol': 3},
             'EXPLICIT_CORE44_FINANCIAL45_PROTOCOL3_REQUIRED')
        need(isinstance(plan['operator'], dict) and set(plan['operator']) == {
            'java', 'databaseProperties', 'migrationProperties', 'carryForwardProperties'},
             'EXPLICIT_PINNED_OPERATOR_INPUTS_REQUIRED')
        need(isinstance(plan.get('orchestratorSha256'), str)
             and bool(HEX64.fullmatch(plan['orchestratorSha256']))
             and sha(secure(Path(__file__).resolve())) == plan['orchestratorSha256'],
             'RUNNING_ORCHESTRATOR_DIFFERS_FROM_APPROVED_SOURCE')


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


def root_observation(plan, application_user=None):
    # Every same-schema connection is visible; an application account without
    # PROCESS cannot substitute for this root observation.
    grants = mysql('SHOW GRANTS FOR CURRENT_USER;').decode()
    need(any(' ON *.* ' in line and ('ALL PRIVILEGES' in line or 'PROCESS' in line)
             for line in grants.splitlines()), 'OBSERVER_LACKS_GLOBAL_PROCESS_PRIVILEGE')
    row = json_rows("SELECT JSON_OBJECT('uuid',@@server_uuid,'schema',DATABASE(),'port',@@port,"
                    "'otherConnections',(SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=DATABASE() AND ID<>CONNECTION_ID()));")[0]
    need({k: row[k] for k in ('uuid', 'schema', 'port')} == plan['database'], 'ROOT_OBSERVER_DATABASE_IDENTITY_MISMATCH')
    need(row['otherConnections'] == 0, 'OTHER_SOURCE_SCHEMA_CONNECTIONS_REMAIN')
    if application_user is not None:
        need(bool(re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', application_user)), 'APPLICATION_ACCOUNT_IDENTITY_REQUIRED')
        accounts = json_rows("SELECT JSON_OBJECT('connections',COUNT(*)) FROM information_schema.PROCESSLIST "
                             "WHERE ID<>CONNECTION_ID() AND USER='" + application_user + "';")[0]
        need(accounts['connections'] == 0, 'OTHER_APPLICATION_ACCOUNT_CONNECTIONS_REMAIN')
        row['otherApplicationAccountConnections'] = accounts['connections']
    return row


def backup(plan, out):
    check_release(plan)
    check_build_receipt(plan)
    current_matches(plan)
    if 'operator' in plan:
        check_closed_configuration(plan)
        verify_media_paused(plan)
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
              'migration45': 'NOT_PERFORMED', 'compatibleApplicationRecovery': 'NOT_TESTED',
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
    # Verify with the reviewed candidate tools before changing the installed
    # guard. An old core44 service remains recoverable before financial DDL.
    tool_directory = directory / 'release-tools' if 'operator' in plan else APP
    guard = secure(tool_directory / 'verify-release.py')
    need(sha(guard) == plan['guardSha256'] and sha(secure(tool_directory / 'release_gate.py')) == plan['gateSha256'],
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
             'migration45Verified', 'compatibleRecoveryVerified')), 'REAL_BACKUP_RESTORE_ACCEPTANCE_REQUIRED')
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


def check_closed_configuration(plan):
    original = property_map(secure(ETC / 'application.properties', private=True))
    candidate = property_map(secure(plan['closedProperties'], private=True))
    validate_configuration(original, candidate, False, channels=plan_channels(plan, 'closed'),
                           paypal_merchant_id=plan.get('paypalMerchantId'))


def operator_file(plan, name):
    entry = plan['operator'][name]
    need(isinstance(entry, dict) and set(entry) == {'path', 'sha256'}
         and isinstance(entry['sha256'], str) and bool(HEX64.fullmatch(entry['sha256'])),
         'PINNED_OPERATOR_INPUT_REQUIRED')
    path = secure(entry['path'], private=name != 'java')
    need(sha(path) == entry['sha256'], 'OPERATOR_INPUT_DIGEST_MISMATCH')
    return path


def operator_inputs(plan, kind):
    need('operator' in plan and plan.get('schemaVersions') == {'core': 44, 'financial': 45, 'protocol': 3},
         'EXPLICIT_CORE44_FINANCIAL45_PROTOCOL3_REQUIRED')
    java = operator_file(plan, 'java')
    need(java.as_posix().startswith('/usr/lib/jvm/') and java.name == 'java', 'PINNED_SERVER_JAVA_BINARY_REQUIRED')
    connection = operator_file(plan, 'databaseProperties')
    contract = operator_file(plan, 'migrationProperties' if kind == 'migration' else 'carryForwardProperties')
    values, db = property_map(contract), property_map(connection)
    need(all(values.get(k) == v for k, v in {
        'expected.uuid': UUID, 'expected.database': DATABASE, 'expected.port': '3306',
        'candidate.jar.path': str(Path(plan['candidate']['directory']) / 'app.jar'),
        'candidate.jar.sha256': plan['candidate']['jarSha256'],
    }.items()), 'OPERATOR_DATABASE_OR_CANDIDATE_BINDING_MISMATCH')
    parsed = urlsplit(db.get('spring.datasource.url', '').removeprefix('jdbc:'))
    params = parse_qs(parsed.query, keep_blank_values=True)
    need((parsed.hostname, parsed.port or 3306, parsed.path) == ('127.0.0.1', 3306, '/' + DATABASE)
         and parsed.username is None and parsed.password is None
         and params.get('connectionTimeZone') == ['UTC']
         and params.get('forceConnectionTimeZoneToSession') == ['true'], 'OPERATOR_LOCAL_UTC_DATABASE_REQUIRED')
    need(not any(key.casefold() in ('user', 'username', 'password', 'password1', 'password2', 'password3')
                 for key in params), 'OPERATOR_URL_CREDENTIAL_OVERRIDE_FORBIDDEN')
    local_admin = values.get('allow-local-root-with-empty-password') == 'true'
    if local_admin:
        need(plan.get('operatorIdentity') == {'mode': 'existing-local-root-empty-password',
              'username': 'root', 'runtimeAccountUnchanged': True}
             and db.get('spring.datasource.username') == 'root'
             and 'spring.datasource.password' in db and db['spring.datasource.password'] == '',
             'EXACT_REVIEWED_LOCAL_ADMINISTRATOR_REQUIRED')
    else:
        need(all(isinstance(db.get(k), str) and db[k] and '${' not in db[k]
                 for k in ('spring.datasource.username', 'spring.datasource.password')), 'LITERAL_OPERATOR_DATABASE_CREDENTIALS_REQUIRED')
    if kind == 'migration':
        need(values.get('migration.version') == '45' and values.get('migration.sha256') == FINANCIAL_SQL_SHA256
             and values.get('core.manifest.sha256') == CORE_MANIFEST_SHA256,
             'CANONICAL_CORE44_FINANCIAL45_INPUT_REQUIRED')
    return java, connection, contract, values, db


def run_operator(plan, kind, mode, contract=None):
    java, connection, approved_contract, values, db = operator_inputs(plan, kind)
    # No inherited JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/CLASSPATH/LOADER_PATH,
    # external loader.properties, Spring application or supplier credentials.
    env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8', 'HOME': '/nonexistent'}
    jar = Path(plan['candidate']['directory']) / 'app.jar'
    args = [str(java), '-Dloader.config.location=file:/dev/null', '-Dloader.path=',
            '-Dloader.main=' + (MIGRATION_CLASS if kind == 'migration' else CARRY_CLASS),
            '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
            mode, str(connection), str(contract or approved_contract)]
    output = command(args, timeout=300, env=env, cwd=str(jar.parent))
    # Flyway can log before the final JSON. Never copy its output or driver
    # errors into a receipt, even when it contains SQL or a connection URL.
    try:
        result = json.loads(output.decode('utf-8').strip().splitlines()[-1])
    except (UnicodeError, ValueError, IndexError):
        raise Blocked('OPERATOR_FINAL_JSON_REQUIRED') from None
    need(isinstance(result, dict) and result.get('status') == 'PASS', 'OPERATOR_DID_NOT_PASS')
    need(result.get('databaseUuid') == UUID and result.get('database') == DATABASE and result.get('port') == 3306,
         'OPERATOR_ACTUAL_DATABASE_IDENTITY_MISMATCH')
    need(isinstance(result.get('sourceSha256'), str) and bool(HEX64.fullmatch(result['sourceSha256'])),
         'OPERATOR_ACTUAL_SOURCE_HASH_REQUIRED')
    need(mode == 'plan' or result.get('candidateJarSha256') == plan['candidate']['jarSha256'],
         'OPERATOR_ACTUAL_CANDIDATE_MISMATCH')
    allowed = {'status', 'mode', 'databaseUuid', 'database', 'port', 'engine', 'sourceSha256', 'planSha256',
               'owners', 'debitMappings', 'preservedOrders', 'issues', 'state', 'protocol', 'journalTotal', 'replay',
               'candidateJarSha256', 'oldWriterStoppedEvidenceSha256', 'backupRestoreEvidenceSha256',
               'rollbackCompatibilityEvidenceSha256', 'legacyOrderIsolationEvidenceSha256',
               'schemaVersion', 'coreVersion', 'migrationsExecuted', 'financialMigrationSha256', 'coreManifestSha256'}
    return {key: value for key, value in result.items() if key in allowed}


def check_operator_backup(plan, out):
    local = json.loads(secure(out / 'backup-receipt.json').read_text())
    need(local.get('status') == 'PASS' and local.get('oldWritersStopped') is True
         and local.get('database') == plan['database']
         and sha(secure(out / 'database.sql', private=True)) == local.get('databaseSha256'), 'FINAL_BACKUP_NOT_VALID')
    actual = json.loads(secure(out / 'actual-data-restore-receipt.json').read_text())
    need(actual.get('status') == 'PASS' and actual.get('backupDatabaseSha256') == local['databaseSha256']
         and all(actual.get(k) is True for k in ('actualRestoreExecuted', 'dataIntegrityVerified',
             'sourceUnchanged', 'creditJournalVerified'))
         and actual.get('applicationStarted') is False and actual.get('supplierCalls') is False,
         'ACTUAL_PRIVATE_BACKUP_RESTORE_REQUIRED')
    restore = receipt(plan, 'restore')
    need(restore.get('backupDatabaseSha256') == local['databaseSha256']
         and restore.get('candidateJarSha256') == plan['candidate']['jarSha256']
         and all(restore.get(k) is True for k in ('actualRestoreExecuted', 'dataIntegrityVerified',
              'migration45Verified', 'compatibleRecoveryVerified')), 'REAL_BACKUP_RESTORE_ACCEPTANCE_REQUIRED')
    return local


def validate_restore_evidence(plan, backup, actual, evidence, references, frozen=None):
    """Compose differently scoped real executions; never relabel synthetic data as production."""
    jar, commit = plan['candidate']['jarSha256'], plan['candidate']['sourceCommit']
    need(backup.get('status') == 'PASS' and backup.get('database') == plan['database']
         and actual.get('status') == 'PASS'
         and actual.get('scope') == 'ACTUAL_SERVER_LOCAL_DATA_RESTORE_ONLY'
         and actual.get('backupDatabaseSha256') == backup.get('databaseSha256')
         and all(actual.get(k) is True for k in ('actualRestoreExecuted', 'dataIntegrityVerified', 'sourceUnchanged', 'creditJournalVerified'))
         and actual.get('applicationStarted') is False and actual.get('supplierCalls') is False
         and actual.get('fullBaseTableCount') == len(backup.get('tableFingerprint', {}))
         and actual.get('fullBaseTableCount', 0) > 0, 'ACTUAL_BACKUP_DATA_RESTORE_EVIDENCE_REQUIRED')
    need(set(evidence) == set(references) == {'artifact', 'packagedFinancial', 'isolatedBackupRestore', 'isolatedJarStartup'},
         'ALL_RAW_RESTORE_COMPATIBILITY_EVIDENCE_REQUIRED')
    for item in references.values():
        need(isinstance(item, dict) and set(item) == {'path', 'sha256'}
             and isinstance(item['path'], str) and bool(item['path'])
             and isinstance(item['sha256'], str) and bool(HEX64.fullmatch(item['sha256'])), 'RAW_RESTORE_EVIDENCE_PIN_REQUIRED')
    a, p, b, s = (evidence[k] for k in ('artifact', 'packagedFinancial', 'isolatedBackupRestore', 'isolatedJarStartup'))
    need(all(v.get('status') == 'PASS' for v in (a, p, b, s)), 'RAW_RESTORE_EVIDENCE_NOT_PASS')
    need(a.get('source_commit') == commit and a.get('jar_sha256') == jar
         and a.get('fresh_export') is True and a.get('runtime_credentials_loaded') is False
         and a.get('all_production_class_bytes_match_tested') is True
         and a.get('all_frontend_bytes_match_acceptance') is True, 'EXACT_ACCEPTED_RESTORE_ARTIFACT_REQUIRED')
    need(p.get('scope') == 'PACKAGED_LOCAL_SYNTHETIC_MYSQL_COMMANDS_ONLY'
         and p.get('source_commit') == commit and p.get('jar_sha256') == jar
         and p.get('production_access') is False and p.get('vendor_calls') is False
         and p.get('spring_application_started') is False and p.get('original_money_unchanged') is True
         and p.get('migrations_executed') == 1 and p.get('replay_migrations_executed') == 0
         and p.get('final_state') == 'ACTIVE' and p.get('historical_allocations_fabricated') is False,
         'ACTUAL_PACKAGED_FINANCIAL45_ACCEPTANCE_REQUIRED')
    isolation = p.get('isolation', {})
    need(isolation.get('uuid') == '05c27d3c-dda3-11f1-aba0-84a938421519'
         and isolation.get('port') == 23316 and isolation.get('single_schema_grants') is True
         and isolation.get('production_schema_visible') is False, 'PACKAGED_COMMAND_ISOLATION_REQUIRED')
    stages = p.get('stages', [])
    expected = {'read-only-plan', 'validate-core44', 'reject-without-authorization', 'migrate-financial45',
                'replay-financial45', 'apply-carry-forward', 'activate-carry-forward', 'verify-carry-forward'}
    need(isinstance(stages, list) and len(stages) == len(expected)
         and {v.get('stage') for v in stages} == expected
         and all(v.get('status') == ('BLOCKED' if v['stage'] == 'reject-without-authorization' else 'PASS')
                 and v.get('exit_code') == (2 if v['stage'] == 'reject-without-authorization' else 0) for v in stages),
         'PACKAGED_OPERATOR_STAGE_RESULTS_REQUIRED')
    need(b.get('scope') == 'LOCAL_23316_ISOLATED_APPLICATION_COHORT_ONLY'
         and b.get('stage') == 'COMPLETE' and b.get('production_access') is False
         and b.get('provider_calls') is False and b.get('original_database_writes') is False
         and b.get('services_started') is False and b.get('source_unchanged_after_restore') is True
         and b.get('restore_comparison', {}).get('semantic_ddl_columns_indexes_foreign_keys_checks_equal') is True,
         'ISOLATED_NONEMPTY_BACKUP_RESTORE_REQUIRED')
    for key in ('source_identity', 'restore_identity'):
        identity = b.get(key, {})
        need(identity.get('uuid') == isolation['uuid'] and identity.get('port') == 23316
             and identity.get('single_schema_grants') is True, 'ISOLATED_BACKUP_ACCOUNT_SCOPE_REQUIRED')
    for key in ('source_snapshot', 'restore_snapshot'):
        snap = b.get(key, {}); history = snap.get('flyway', [])
        need(snap.get('financial_conservation') == 'PASS' and len(snap.get('triggers', [])) == 17
             and len(history) == 45 and {v.get('version') for v in history} == {str(n) for n in range(1, 46)}
             and all(v.get('success') == 1 and v.get('type') == 'SQL' for v in history)
             and any(v.get('version') == '45' and v.get('script') == 'V45__credit_entitlement_integration.sql'
                     and v.get('checksum') == -2125061473 for v in history), 'EXACT_RESTORED_FINANCIAL45_REQUIRED')
    need(s.get('scope') == 'PACKAGED_RESTORED_SYNTHETIC_DB_STARTUP_ONLY'
         and s.get('source_commit') == commit and s.get('jar_sha256') == jar
         and s.get('uuid') == isolation['uuid'] and s.get('database') == b.get('restore_database')
         and s.get('production_access') is False and s.get('vendor_calls') is False
         and s.get('migrations_enabled') is False and s.get('provider_or_money_actions') == 0
         and all(s.get(k) is True for k in ('owned_process_stopped', 'restored_financial_facts_unchanged', 'payment_catalogs_disabled'))
         and s.get('financial_snapshot_before') == s.get('financial_snapshot_after')
         and s.get('financial_snapshot_before', {}).get('credit_ledger', {}).get('rows', 0) > 0,
         'SAME_ARTIFACT_RESTORED_NONEMPTY_STARTUP_REQUIRED')
    result = {'status': 'PASS', 'version': 1, 'scope': 'COMPOSITE_REAL_DATA_RESTORE_AND_ISOLATED_COMPATIBILITY',
        'database': plan['database'], 'candidateJarSha256': jar, 'sourceCommit': commit,
        'backupDatabaseSha256': backup['databaseSha256'], 'actualRestoreExecuted': True,
        'dataIntegrityVerified': True, 'migration45Verified': True, 'compatibleRecoveryVerified': True,
        'productionCloneApplicationStarted': False, 'productionCloneMigratedTo45': False,
        'compatibilityData': 'LOCAL_SYNTHETIC_ONLY', 'realPaymentAcceptance': 'NOT_TESTED',
        'rawEvidence': references, 'sourceBound': False,
        'recordedUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    if frozen is not None:
        need(frozen.get('status') == 'PASS' and frozen.get('state') == 'READ_ONLY_PLAN'
             and frozen.get('databaseUuid') == plan['database']['uuid']
             and frozen.get('database') == plan['database']['schema'] and frozen.get('port') == plan['database']['port']
             and frozen.get('issues') == [] and bool(HEX64.fullmatch(frozen.get('sourceSha256', '')))
             and bool(HEX64.fullmatch(frozen.get('planSha256', ''))), 'ACTUAL_FROZEN_CARRY_PLAN_REQUIRED')
        result.update(sourceBound=True, sourceSha256=frozen['sourceSha256'], planSha256=frozen['planSha256'])
    return result


def compose_restore(plan, out, bind=False):
    current_matches(plan); stopped(plan); verify_media_paused(plan); root_observation(plan)
    backup = json.loads(secure(out / 'backup-receipt.json').read_text())
    actual_path = secure(out / 'actual-data-restore-receipt.json')
    actual = json.loads(actual_path.read_text())
    need(sha(secure(out / 'database.sql', private=True)) == backup.get('databaseSha256')
         and fingerprint(DATABASE) == backup.get('tableFingerprint'), 'FROZEN_BACKUP_SOURCE_CHANGED')
    references = plan.get('restoreEvidence', {})
    evidence = {key: pinned_json(item['path'], item['sha256']) for key, item in references.items()}
    frozen_path = secure(out / 'carry-forward-plan.json') if bind else None
    frozen = json.loads(frozen_path.read_text()) if bind else None
    result = validate_restore_evidence(plan, backup, actual, evidence, references, frozen)
    result['actualDataRestoreEvidence'] = {'path': str(actual_path), 'sha256': sha(actual_path)}
    result['backupEvidence'] = {'path': str(out / 'backup-receipt.json'), 'sha256': sha(out / 'backup-receipt.json')}
    if bind: result['frozenPlanEvidence'] = {'path': str(frozen_path), 'sha256': sha(frozen_path)}
    exclusive_json(out / ('restore-composite-bound.json' if bind else 'restore-composite-preplan.json'), result)
    return result


def approved_operator_scope(plan, name, values, operation):
    entry = plan['receipts'][name]
    approved = pinned_json(entry['path'], entry['sha256'])
    need(approved.get('status') == 'APPROVED' and approved.get('operation') == operation
         and approved.get('database') == plan['database']
         and approved.get('candidateJarSha256') == plan['candidate']['jarSha256']
         and approved.get('sourceSha256') == values.get('source.sha256')
         and isinstance(approved.get('userAuthorizationEvidenceSha256'), str)
         and bool(HEX64.fullmatch(approved['userAuthorizationEvidenceSha256'])), 'EXACT_OPERATION_AUTHORIZATION_REQUIRED')
    if name == 'schemaAuthorization':
        need(approved.get('coreVersion') == 44 and approved.get('financialVersion') == 45
             and approved.get('financialMigrationSha256') == FINANCIAL_SQL_SHA256,
             'EXACT_V45_DDL_AUTHORIZATION_REQUIRED')
    else:
        need(approved.get('planSha256') == values.get('plan.sha256')
             and approved.get('legacyOrdersReadOnly') is True
             and approved.get('balancesPreserved') is True
             and approved.get('historicalFundingProvenance') == 'UNKNOWN_UNCHANGED',
             'EXACT_PRESERVED_BALANCES_AUTHORIZATION_REQUIRED')
    return approved


def fresh_operator_contract(plan, out, kind, phase, values, db):
    stopped(plan)
    for proof in ('backup-restore', 'rollback', 'legacy-boundary'):
        need(pinned_json(values.get('receipt.' + proof + '.path'),
                         values.get('receipt.' + proof + '.sha256')).get('status') == 'PASS',
             'PINNED_OPERATIONAL_PROOF_NOT_PASS')
    original = property_map(out / 'configuration/application.properties')
    if values.get('allow-local-root-with-empty-password') == 'true':
        need(plan.get('operatorIdentity') == {'mode': 'existing-local-root-empty-password',
             'username': 'root', 'runtimeAccountUnchanged': True}
             and db.get('spring.datasource.username') == 'root'
             and 'spring.datasource.password' in db and db['spring.datasource.password'] == '',
             'EXACT_REVIEWED_LOCAL_ADMINISTRATOR_REQUIRED')
    else:
        need(all(original.get(k) == db.get(k) for k in ('spring.datasource.username', 'spring.datasource.password')),
             'OPERATOR_DATABASE_ACCOUNT_CHANGED')
    observed = root_observation(plan, original.get('spring.datasource.username'))
    if db['spring.datasource.username'] != original.get('spring.datasource.username'):
        root_observation(plan, db['spring.datasource.username'])
    stamp = dt.datetime.now(dt.timezone.utc)
    suffix = phase + '-' + stamp.strftime('%Y%m%dT%H%M%S%fZ')
    writers = {'status': 'PASS', 'databaseUuid': UUID, 'database': DATABASE, 'port': 3306,
        'sourceSha256': values['source.sha256'], 'candidateJarSha256': plan['candidate']['jarSha256'],
        'recordedAt': stamp.isoformat(), 'serviceInactive': True, 'otherWritersStopped': True,
        'connectionsDrained': True, 'globalProcesslistVerified': True, 'observerHasProcessPrivilege': True,
        'otherSchemaConnections': observed['otherConnections'],
        'otherApplicationAccountConnections': observed['otherApplicationAccountConnections']}
    writer_path = out / ('writer-observation-' + suffix + '.json')
    exclusive_json(writer_path, writers)
    effective = {**values, 'receipt.writers.path': writer_path.as_posix(), 'receipt.writers.sha256': sha(writer_path)}
    target = out / ('operator-approval-' + suffix + '.properties')
    # This only refreshes actual writer observations. It cannot change the
    # externally approved source/plan/JAR, authorization or other three proofs.
    need(all('\n' not in key + value and '\r' not in key + value and '\\' not in key + value
             for key, value in effective.items()), 'UNSAFE_OPERATOR_PROPERTIES_ENCODING')
    fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        stream.write(''.join(key + '=' + effective[key] + '\n' for key in sorted(effective)))
        stream.flush()
        os.fsync(stream.fileno())
    return target


def operator_stage(plan, out, operation):
    check_release(plan)
    check_build_receipt(plan)
    current_matches(plan)
    kind = 'migration' if operation in ('schema-validate', 'migrate-45') else 'carry'
    java, connection, contract, values, db = operator_inputs(plan, kind)
    if operation == 'schema-validate':
        result = run_operator(plan, 'migration', 'validate')
        need(result.get('coreVersion') == 44 and result.get('schemaVersion') in (44, 45)
             and type(result.get('migrationsExecuted')) is int and result.get('migrationsExecuted') == 0
             and result.get('financialMigrationSha256') == FINANCIAL_SQL_SHA256
             and result.get('coreManifestSha256') == CORE_MANIFEST_SHA256,
             'READ_ONLY_CANONICAL_SCHEMA_VALIDATION_REQUIRED')
        return result
    verify_media_paused(plan)
    stopped(plan)
    local = check_operator_backup(plan, out)
    if operation == 'carry-plan':
        result = run_operator(plan, 'carry', 'plan')
        need(result.get('state') == 'READ_ONLY_PLAN' and bool(HEX64.fullmatch(result.get('planSha256', ''))),
             'FROZEN_CARRY_FORWARD_PLAN_REQUIRED')
        exclusive_json(out / 'carry-forward-plan.json', result)
        return result
    need(bool(HEX64.fullmatch(values.get('source.sha256', ''))), 'APPROVED_FROZEN_SOURCE_REQUIRED')
    if operation == 'migrate-45':
        approved_operator_scope(plan, 'schemaAuthorization', values, 'MIGRATE_CANONICAL_44_TO_FINANCIAL_45')
        need(values.get('migration.authorized') == 'true', 'EXPLICIT_V45_MIGRATION_AUTHORIZATION_REQUIRED')
        need(fingerprint(DATABASE) == local['tableFingerprint'], 'PRODUCTION_SOURCE_CHANGED_SINCE_BACKUP')
        mode, expected_state = 'migrate', None
    else:
        need(operation in ('carry-apply', 'carry-activate', 'carry-verify'), 'EXPLICIT_OPERATOR_STAGE_REQUIRED')
        approved_operator_scope(plan, 'historyAuthorization', values, 'PRESERVE_EXISTING_NET_BALANCES')
        frozen = json.loads(secure(out / 'carry-forward-plan.json').read_text())
        need(frozen.get('sourceSha256') == values.get('source.sha256')
             and frozen.get('planSha256') == values.get('plan.sha256'), 'APPROVED_CARRY_PLAN_CHANGED')
        migrated = json.loads(secure(out / 'migration-45-receipt.json').read_text())
        need(migrated.get('status') == 'PASS' and migrated.get('schemaVersion') == 45
             and migrated.get('candidateJarSha256') == plan['candidate']['jarSha256']
             and migrated.get('sourceSha256') == values['source.sha256'], 'ACTUAL_FINANCIAL45_MIGRATION_REQUIRED')
        mode = operation.removeprefix('carry-')
        previous = 'carry-apply-receipt.json' if mode == 'activate' else 'carry-activate-receipt.json' if mode == 'verify' else None
        if previous:
            prior = json.loads(secure(out / previous).read_text())
            need(prior.get('status') == 'PASS' and prior.get('protocol') == 3
                 and prior.get('state') == ('PREPARING' if mode == 'activate' else 'ACTIVE')
                 and prior.get('candidateJarSha256') == plan['candidate']['jarSha256']
                 and prior.get('sourceSha256') == values['source.sha256']
                 and prior.get('planSha256') == values['plan.sha256'], 'PREVIOUS_EXPLICIT_CARRY_STAGE_REQUIRED')
        expected_state = 'PREPARING' if mode == 'apply' else 'ACTIVE'
    effective = fresh_operator_contract(plan, out, kind, operation, values, db)
    result = run_operator(plan, kind, mode, effective)
    need(result.get('sourceSha256') == values['source.sha256'], 'OPERATOR_FROZEN_SOURCE_DRIFT')
    if kind == 'migration':
        need(result.get('schemaVersion') == 45 and result.get('coreVersion') == 44
             and type(result.get('migrationsExecuted')) is int and result['migrationsExecuted'] in (0, 1)
             and result.get('financialMigrationSha256') == FINANCIAL_SQL_SHA256
             and result.get('coreManifestSha256') == CORE_MANIFEST_SHA256
             and result.get('state') == 'PREPARING',
             'ACTUAL_CANONICAL_FINANCIAL45_REQUIRED')
    else:
        need(result.get('state') == expected_state and result.get('protocol') == 3
             and result.get('planSha256') == values['plan.sha256'], 'EXACT_CARRY_STAGE_RESULT_REQUIRED')
    stopped(plan)
    root_observation(plan, db['spring.datasource.username'])
    exclusive_json(out / ('migration-45-receipt.json' if kind == 'migration' else operation + '-receipt.json'), result)
    return result


def media_process_ids():
    found = []
    for process in Path('/proc').iterdir():
        if not process.name.isdigit():
            continue
        try:
            args = (process / 'cmdline').read_bytes().split(b'\0')
            if str(MEDIA_SCRIPT).encode() in args:
                found.append(int(process.name))
        except FileNotFoundError:
            continue
    return found


def media_cron_directory(plan):
    return BACKUPS / ('stripe-live-' + plan['releaseId'] + '-cron')


def validate_media_cron(plan):
    control = plan.get('mediaCron')
    need(isinstance(control, dict) and control.get('entry') == MEDIA_ENTRY
         and isinstance(control.get('expectedCrontabSha256'), str)
         and bool(HEX64.fullmatch(control['expectedCrontabSha256']))
         and isinstance(control.get('scriptSha256'), str) and bool(HEX64.fullmatch(control['scriptSha256'])),
         'EXPLICIT_PINNED_PROJECT_MEDIA_CRON_REQUIRED')
    need(sha(secure(MEDIA_SCRIPT)) == control['scriptSha256'], 'MEDIA_SCRIPT_CHANGED_SINCE_REVIEW')
    return control


def cron_active_lines(raw):
    return [line for line in raw.decode('utf-8').splitlines() if line.strip() and not line.lstrip().startswith('#')]


def paused_crontab(raw):
    lines = raw.decode('utf-8').splitlines(keepends=True)
    need(sum(line.rstrip('\r\n') == MEDIA_ENTRY for line in lines) == 1, 'EXACTLY_ONE_PROJECT_MEDIA_CRON_REQUIRED')
    return ''.join(MEDIA_PAUSE_MARKER + line if line.rstrip('\r\n') == MEDIA_ENTRY else line for line in lines).encode('utf-8')


def install_crontab_checked(expected_sha, content, temporary):
    # Native crontab installs a complete file atomically and reloads Cronie.
    # Compare immediately before installation; never overwrite an observed
    # intervening edit. The same release lock serializes all project releases.
    need(sha(secure(ROOT_CRONTAB, private=True)) == expected_sha, 'ROOT_CRONTAB_CHANGED_DO_NOT_OVERWRITE')
    fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    need(sha(ROOT_CRONTAB) == expected_sha, 'ROOT_CRONTAB_CHANGED_DO_NOT_OVERWRITE')
    command(['crontab', '-u', 'root', str(temporary)])
    installed = secure(ROOT_CRONTAB, private=True).read_bytes()
    # Cronie may refresh its generated comment header. Every active job and
    # environment line, including unrelated jobs, must remain byte-identical.
    need(cron_active_lines(installed) == cron_active_lines(content), 'CRONTAB_ACTIVE_ENTRIES_DIFFER_FROM_APPROVED_CONTENT')
    return installed


def verify_media_paused(plan):
    validate_media_cron(plan)
    directory = media_cron_directory(plan)
    observed = json.loads(secure(directory / 'paused-receipt.json').read_text())
    need(observed.get('status') == 'PASS' and observed.get('releaseId') == plan['releaseId']
         and sha(secure(ROOT_CRONTAB, private=True)) == observed.get('pausedCrontabSha256')
         and MEDIA_ENTRY not in cron_active_lines(ROOT_CRONTAB.read_bytes())
         and not media_process_ids(), 'PROJECT_MEDIA_CRON_NOT_PAUSED_OR_PROCESS_STILL_RUNNING')


def pre_ddl_controls(plan):
    current_matches(plan)
    files = [ETC / 'application.properties', ETC / 'release-policy.json',
             APP / 'verify-release.py', APP / 'release_gate.py']
    files += [ETC / name for name in ('usage-limits.properties', 'invite-gate.properties') if (ETC / name).exists()]
    return {'expected': plan['expected'], 'files': {str(path): sha(secure(path)) for path in files},
            'unitSha256': hashlib.sha256(command(['systemctl', 'cat', SERVICE])).hexdigest()}


def verify_pre_ddl_schema(plan):
    """No table/row/privilege changes: compare the complete core history and reject financial remnants."""
    expected = {}
    def collect(archive, prefix):
        for name in archive.namelist():
            if name.startswith(prefix) and re.fullmatch(r'V[1-9][0-9]*__[A-Za-z0-9_]+\.sql', name[len(prefix):]):
                script = name[len(prefix):]; version = int(script[1:script.index('__')])
                if version <= CORE_VERSION:
                    need(version not in expected, 'ABORT_DUPLICATE_CORE_MIGRATION')
                    text = archive.read(name).decode('utf-8-sig').replace('\r\n', '\n').replace('\r', '\n')
                    crc = zlib.crc32(''.join(text.splitlines()).encode('utf-8'))
                    expected[version] = {'version': str(version), 'script': script,
                        'checksum': crc if crc < 2 ** 31 else crc - 2 ** 32, 'success': 1, 'type': 'SQL'}
    with zipfile.ZipFile(secure(Path(plan['candidate']['directory']) / 'app.jar')) as jar:
        collect(jar, 'BOOT-INF/classes/db/migration/')
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested: collect(nested, 'db/migration/')
    need(set(expected) == set(range(1, 45)), 'ABORT_EXACT_CORE44_ARTIFACT_REQUIRED')
    actual = json_rows("SELECT JSON_OBJECT('version',version,'script',script,'checksum',checksum,'success',success,'type',type) "
                       "FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY CAST(version AS UNSIGNED);")
    need(actual == [expected[n] for n in range(1, 45)], 'ABORT_REQUIRES_UNCHANGED_SUCCESSFUL_CORE44_HISTORY')
    remaining = json_rows("SELECT JSON_OBJECT('tables',(SELECT COUNT(*) FROM information_schema.TABLES "
        "WHERE TABLE_SCHEMA=DATABASE() AND (LEFT(TABLE_NAME,3)='p0_' OR TABLE_NAME IN ('payment_inbox_review','payment_inbox_attempt'))),"
        "'triggers',(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND LEFT(TRIGGER_NAME,3)='p0_'),"
        "'columns',(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_webhook_inbox' "
        "AND COLUMN_NAME IN ('merchant_scope','env_scope')));")[0]
    need(remaining == {'tables': 0, 'triggers': 0, 'columns': 0}, 'ABORT_FINANCIAL_SCHEMA_OR_PARTIAL_DDL_PRESENT')


def abort_before_ddl(plan, out):
    """Only resume the unchanged, already-approved old baseline. Never restore SQL or configuration."""
    current_matches(plan)
    need(not any(out.glob('operator-approval-migrate-45-*.properties'))
         and not any(out.glob('writer-observation-migrate-45-*.json'))
         and not any((out / name).exists() for name in ('migration-45-receipt.json', 'carry-apply-receipt.json',
             'carry-activate-receipt.json', 'carry-verify-receipt.json')), 'ABORT_FORBIDDEN_AFTER_FINANCIAL_MIGRATION_ATTEMPT')
    directory = media_cron_directory(plan)
    original_controls = json.loads(secure(directory / 'pre-ddl-controls.json').read_text())
    need(original_controls == pre_ddl_controls(plan), 'ABORT_OLD_RUNTIME_CONTROLS_CHANGED')
    check_release(plan)
    verify_pre_ddl_schema(plan)
    if (out / 'backup-receipt.json').exists():
        backup = json.loads(secure(out / 'backup-receipt.json').read_text())
        need(backup.get('status') == 'PASS' and backup.get('database') == plan['database']
             and fingerprint(DATABASE) == backup.get('tableFingerprint'), 'ABORT_SOURCE_CHANGED_SINCE_BACKUP')
    control = validate_media_cron(plan)
    original = secure(directory / 'root-crontab-before.txt', private=True).read_bytes()
    need(hashlib.sha256(original).hexdigest() == control['expectedCrontabSha256'], 'ABORT_ORIGINAL_CRON_CHANGED')
    current_cron = secure(ROOT_CRONTAB, private=True).read_bytes()
    need(cron_active_lines(current_cron) == cron_active_lines(paused_crontab(original))
         and current_cron.decode().splitlines().count(MEDIA_PAUSE_MARKER + MEDIA_ENTRY) == 1
         and not media_process_ids(), 'ABORT_CRON_CHANGED_OR_OLD_MEDIA_PROCESS_ACTIVE')
    cron_sha = hashlib.sha256(current_cron).hexdigest()
    startup()
    current_matches(plan)
    need(pre_ddl_controls(plan) == original_controls, 'ABORT_OLD_RUNTIME_CONTROLS_CHANGED')
    running_artifact_matches({**plan, 'candidate': plan['expected']})
    install_crontab_checked(cron_sha, original, directory / 'abort-resume-install.txt')
    result = {'status': 'PASS', 'scope': 'ABORTED_BEFORE_DDL_OLD_BASELINE_RESUMED',
        'sourceCommit': plan['expected']['sourceCommit'], 'jarSha256': plan['expected']['jarSha256'],
        'databaseRestored': False, 'configurationRestored': False, 'schemaVersion': 44,
        'financialDdlExecuted': False, 'oldArtifactActuallyRunning': True, 'originalProjectCronResumed': True,
        'recordedUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    exclusive_json(directory / 'abort-before-ddl-receipt.json', result)
    return result


def media_cron_stage(plan, operation):
    current_matches(plan, candidate=operation == 'cron-resume')
    control = validate_media_cron(plan)
    directory = media_cron_directory(plan)
    if operation == 'cron-pause':
        if directory.exists():
            if (directory / 'paused-receipt.json').exists():
                verify_media_paused(plan)
                return {**json.loads(secure(directory / 'paused-receipt.json').read_text()), 'replay': True}
            # A previous invocation may have paused the job but timed out
            # waiting for an already-running check. Complete only observation;
            # never reinstall a crontab or overwrite the original backup.
            original = secure(directory / 'root-crontab-before.txt', private=True).read_bytes()
            need(hashlib.sha256(original).hexdigest() == control['expectedCrontabSha256'], 'ORIGINAL_CRON_BACKUP_CHANGED')
            installed = secure(ROOT_CRONTAB, private=True).read_bytes()
            need(cron_active_lines(installed) == cron_active_lines(paused_crontab(original)),
                 'INCOMPLETE_CRON_PAUSE_CHANGED_REQUIRES_REVIEW')
        else:
            original = secure(ROOT_CRONTAB, private=True).read_bytes()
            need(hashlib.sha256(original).hexdigest() == control['expectedCrontabSha256'], 'ROOT_CRONTAB_CHANGED_DO_NOT_OVERWRITE')
            paused = paused_crontab(original)
            directory.mkdir(mode=0o700, parents=True)
            if 'operator' in plan:
                exclusive_json(directory / 'pre-ddl-controls.json', pre_ddl_controls(plan))
            fd = os.open(directory / 'root-crontab-before.txt', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(original)
                stream.flush()
                os.fsync(stream.fileno())
            installed = install_crontab_checked(control['expectedCrontabSha256'], paused, directory / 'pause-install.txt')
        need(installed.decode('utf-8').splitlines().count(MEDIA_PAUSE_MARKER + MEDIA_ENTRY) == 1,
             'PROJECT_MEDIA_CRON_PAUSE_MARKER_MISSING')
        deadline = time.monotonic() + 30
        while media_process_ids() and time.monotonic() < deadline:
            time.sleep(1)
        need(not media_process_ids(), 'PROJECT_MEDIA_PROCESS_NOT_DRAINED_CRON_REMAINS_PAUSED')
        result = {'status': 'PASS', 'scope': 'PROJECT_MEDIA_CRON_PAUSED_ONLY', 'releaseId': plan['releaseId'],
            'originalCrontabSha256': control['expectedCrontabSha256'],
            'pausedCrontabSha256': hashlib.sha256(installed).hexdigest(), 'mediaScriptSha256': control['scriptSha256'],
            'otherCronEntriesPreserved': True, 'mediaProcessCount': 0, 'wholeCronServiceStopped': False,
            'mediaAutomaticRecovery': 'PAUSED_REQUIRES_COMPATIBILITY_REVIEW',
            'recordedUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
        exclusive_json(directory / 'paused-receipt.json', result)
        return result
    need(operation == 'cron-resume', 'EXPLICIT_PROJECT_CRON_STAGE_REQUIRED')
    verify_media_paused(plan)
    proof = receipt(plan, 'mediaRecovery')
    need(proof.get('candidateJarSha256') == plan['candidate']['jarSha256']
         and proof.get('mediaScriptSha256') == sha(MEDIA_SCRIPT)
         and all(proof.get(k) is True for k in ('releaseLockCooperates', 'configurationWritesAtomic',
                     'applicationHealthVerified', 'ledgerWriterCompatible')), 'MEDIA_SELF_HEAL_COMPATIBILITY_REQUIRED')
    # Current reviewed script has no release lock; no honest receipt currently
    # satisfies this resume gate. Do not automatically restore an unsafe writer.
    paused = json.loads(secure(directory / 'paused-receipt.json').read_text())
    original = secure(directory / 'root-crontab-before.txt', private=True).read_bytes()
    need(hashlib.sha256(original).hexdigest() == paused['originalCrontabSha256'], 'ORIGINAL_CRON_BACKUP_CHANGED')
    install_crontab_checked(paused['pausedCrontabSha256'], original, directory / 'resume-install.txt')
    result = {'status': 'PASS', 'scope': 'PROJECT_MEDIA_CRON_RESUMED_WITH_COMPATIBILITY_PROOF',
              'releaseId': plan['releaseId'], 'restoredCrontabSha256': sha(ROOT_CRONTAB)}
    exclusive_json(directory / 'resumed-receipt.json', result)
    return result


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


def temporary_offer_scope(value, future=True):
    need(isinstance(value, dict) and set(value) == {'ownerId', 'nonce', 'expiresAt', 'wechatCnyMinor'}
         and isinstance(value.get('ownerId'), str)
         and bool(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', value['ownerId']))
         and isinstance(value.get('nonce'), str) and bool(re.fullmatch(r'[0-9a-f]{8}', value['nonce']))
         and type(value.get('wechatCnyMinor')) is int and value['wechatCnyMinor'] >= 300,
         'EXACT_PRIVATE_ACCEPTANCE_SCOPE_REQUIRED')
    try:
        raw = value['expiresAt']; need(isinstance(raw, str) and raw.endswith('Z'), 'PRIVATE_ACCEPTANCE_EXPIRY_REQUIRED')
        expires = dt.datetime.fromisoformat(raw.removesuffix('Z') + '+00:00')
        need(not future or expires > dt.datetime.now(dt.timezone.utc), 'PRIVATE_ACCEPTANCE_EXPIRED')
    except (TypeError, ValueError, OverflowError):
        raise Blocked('PRIVATE_ACCEPTANCE_EXPIRY_REQUIRED') from None
    return value


def acceptance_configuration(config):
    import decimal
    try:
        amount = decimal.Decimal(config.get('qingfeng.payment.acceptance.wechat-cny', '3.00')) * 100
        need(amount.is_finite() and amount == int(amount), 'PRIVATE_ACCEPTANCE_WECHAT_QUOTE_REQUIRED')
        minor = int(amount)
    except (decimal.InvalidOperation, ValueError, OverflowError):
        raise Blocked('PRIVATE_ACCEPTANCE_WECHAT_QUOTE_REQUIRED') from None
    return temporary_offer_scope({'ownerId': config.get('qingfeng.payment.acceptance.owner-id'),
        'nonce': config.get('qingfeng.payment.acceptance.nonce'),
        'expiresAt': config.get('qingfeng.payment.acceptance.expires-at'), 'wechatCnyMinor': minor})


def temporary_quote(scope, channel, quote, amount_key, reserved=False):
    currency = 'CNY' if channel == 'wechat_pay' else 'USD'
    need(quote.get('packageId') == 'accept-' + scope['nonce'] + '-' + channel + '-' + currency
         and quote.get('credits') == 1 and type(quote.get('credits')) is int
         and quote.get('maxPurchases') == 1 and type(quote.get('maxPurchases')) is int
         and quote.get('ownerId') == scope['ownerId']
         and quote.get('currency') == currency and quote.get(amount_key) ==
             (scope['wechatCnyMinor'] if channel == 'wechat_pay' else 1 if channel == 'paypal' else 50),
         'EXACT_PRIVATE_ACCEPTANCE_QUOTE_REQUIRED')
    if reserved:
        try:
            created = quote['orderCreatedAt']; need(isinstance(created, str) and created.endswith('Z'), 'PRIVATE_ACCEPTANCE_RESERVATION_TIME_REQUIRED')
            need(dt.datetime.fromisoformat(created.removesuffix('Z') + '+00:00') <
                 dt.datetime.fromisoformat(scope['expiresAt'].removesuffix('Z') + '+00:00'), 'PRIVATE_ACCEPTANCE_RESERVED_AFTER_EXPIRY')
        except (KeyError, TypeError, ValueError, OverflowError):
            raise Blocked('PRIVATE_ACCEPTANCE_RESERVATION_TIME_REQUIRED') from None


def validate_canary_funding(funding, jar_sha, channels=None):
    # Public USD/CNY quotes remain unchanged. A private temporary quote requires
    # the explicit owner/nonce/expiry and one exact approved transaction per SKU.
    if channels is not None:
        temporary = temporary_offer_scope(funding['temporaryOffer']) if 'temporaryOffer' in funding else None
        selected = channel_set(channels)
        quotes = funding.get('channelQuotes')
        need(bool(selected) and funding.get('candidateJarSha256') == jar_sha
             and type(funding.get('approvedTransactionLimit')) is int
             and 1 <= funding.get('approvedTransactionLimit') <= 8
             and isinstance(quotes, dict) and set(quotes) == selected,
             'EXACT_CHANNEL_CANARY_FUNDING_REQUIRED')
        count = 0
        for channel in sorted(selected):
            quote = quotes[channel]
            need(isinstance(quote, dict), 'EXACT_CHANNEL_CANARY_FUNDING_REQUIRED')
            amount = quote.get('selectedPackageAmountMinor')
            limit = quote.get('approvedMaximumMinor')
            currency = 'CNY' if channel == 'wechat_pay' else 'USD'
            need(quote.get('currency') == currency and type(amount) is int
                 and (temporary is not None or amount in (CNY_QUOTES if currency == 'CNY' else USD_QUOTES))
                 and type(limit) is int and amount <= limit
                 and type(quote.get('approvedTransactionLimit')) is int
                 and 1 <= quote['approvedTransactionLimit'] <= 2
                 and isinstance(quote.get('authorizationEvidenceSha256'), str)
                 and bool(HEX64.fullmatch(quote.get('authorizationEvidenceSha256', ''))),
                 'ACTUAL_CANARY_QUOTE_EXCEEDS_APPROVED_FUNDS_SCOPE')
            if temporary is not None:
                temporary_quote(temporary, channel, quote, 'selectedPackageAmountMinor')
                need(quote['approvedTransactionLimit'] == 1, 'PRIVATE_ACCEPTANCE_EXACTLY_ONE_TRANSACTION')
            count += quote['approvedTransactionLimit']
        need(count == funding['approvedTransactionLimit'], 'CHANNEL_FUNDING_TRANSACTION_SCOPE_MISMATCH')
        return
    amount = funding.get('selectedPackageAmountMinor')
    limit = funding.get('approvedMaximumMinor')
    need(funding.get('candidateJarSha256') == jar_sha
         and funding.get('currency') == 'USD' and type(amount) is int
         and amount in (990, 2590, 4990) and type(limit) is int and amount <= limit
         and type(funding.get('approvedTransactionLimit')) is int
         and funding.get('approvedTransactionLimit') == 2,
         'ACTUAL_CANARY_QUOTE_EXCEEDS_APPROVED_FUNDS_SCOPE')


def validate_business_acceptance(plan, business):
    # Preserve every original Stripe-only acceptance requirement. A version-2
    # plan adds independently bound native channel proofs; it cannot substitute
    # a generic Checkout-created receipt or card payment for a wallet payment.
    need(business.get('jarSha256') == plan['candidate']['jarSha256']
         and business.get('accountId') == ACCOUNT and business.get('environment') == 'live'
         and all(business.get(k) == 'PASS' for k in ('signedWebhook', 'creditCardPaid',
                     'applePayDevicePaid', 'creditedExactlyOnce', 'ownershipAndCurrency')),
         'REAL_CARD_AND_APPLE_PAY_BUSINESS_ACCEPTANCE_REQUIRED')
    selected = plan_channels(plan, 'open')
    if selected is None:
        return
    proofs = business.get('channelAcceptance')
    temporary = temporary_offer_scope(business['temporaryOffer'], future=False) if 'temporaryOffer' in business else None
    need(isinstance(proofs, dict) and set(proofs) == selected, 'EVERY_RELEASE_CHANNEL_REQUIRES_REAL_ACCEPTANCE')
    payments, orders, entries, evidence = set(), set(), set(), set()
    for channel in sorted(selected):
        proof = proofs[channel]
        need(isinstance(proof, dict), 'REAL_CHANNEL_PAYMENT_ACCEPTANCE_REQUIRED')
        is_paypal = channel == 'paypal'
        account = plan['paypalMerchantId'] if is_paypal else ACCOUNT
        currency = 'CNY' if channel == 'wechat_pay' else 'USD'
        amount = proof.get('amountMinor')
        need(proof.get('status') == 'PASS' and proof.get('candidateJarSha256') == plan['candidate']['jarSha256']
             and proof.get('provider') == ('PAYPAL' if is_paypal else 'STRIPE')
             and proof.get('accountId') == account and proof.get('environment') == 'live'
             and proof.get('livemode') is True
             and proof.get('providerStatus') == ('COMPLETED' if is_paypal else 'succeeded')
             and proof.get('paymentMethodType') == ('card' if channel == 'apple_pay' else channel)
             and proof.get('walletType', '') == ('apple_pay' if channel == 'apple_pay' else '')
             and proof.get('currency') == currency and type(amount) is int
             and (temporary is not None or amount in (CNY_QUOTES if currency == 'CNY' else USD_QUOTES))
             and all(proof.get(k) == 'PASS' for k in ('providerReadback', 'signedWebhook',
                     'creditedExactlyOnce', 'ownershipAndCurrency', 'ledgerReconciled'))
             and isinstance(proof.get('sourceEvidenceSha256'), str)
             and bool(HEX64.fullmatch(proof.get('sourceEvidenceSha256', ''))),
             'REAL_CHANNEL_PAYMENT_ACCEPTANCE_REQUIRED')
        if temporary is not None:
            temporary_quote(temporary, channel, proof, 'amountMinor', reserved=True)
        references = []
        for field in ('providerPaymentId', 'orderId', 'ledgerEntryId'):
            value = proof.get(field)
            need(isinstance(value, str) and bool(re.fullmatch(r'[A-Za-z0-9_-]{4,128}', value)),
                 'BOUND_CHANNEL_PAYMENT_ORDER_LEDGER_REFERENCES_REQUIRED')
            references.append(value)
        payment = (proof['provider'], account, 'live', references[0])
        need(payment not in payments and references[1] not in orders and references[2] not in entries
             and proof['sourceEvidenceSha256'] not in evidence,
             'SAME_PAYMENT_CANNOT_ACCEPT_MULTIPLE_CHANNELS')
        payments.add(payment)
        orders.add(references[1])
        entries.add(references[2])
        evidence.add(proof['sourceEvidenceSha256'])


def install(plan, out, operation):
    check_release(plan)
    check_install_receipts(plan, out)
    current_matches(plan, candidate=operation in ('canary', 'open', 'recover'))
    if 'operator' in plan:
        verify_media_paused(plan)
    stage = 'open' if operation == 'open' else 'canary' if operation == 'canary' else 'closed'
    channels = plan_channels(plan, stage)
    if operation == 'canary':
        validate_canary_funding(receipt(plan, 'canaryFunding'), plan['candidate']['jarSha256'], channels)
    if operation == 'open':
        business = receipt(plan, 'business')
        validate_business_acceptance(plan, business)
    config_key = 'openProperties' if operation == 'open' else 'canaryProperties' if operation == 'canary' else 'closedProperties'
    configuration = secure(plan[config_key], private=True)
    old = property_map(out / 'configuration/application.properties')
    new = property_map(configuration)
    validate_configuration(old, new, operation == 'open', operation == 'canary', channels, plan.get('paypalMerchantId'))
    if operation == 'canary':
        funding = receipt(plan, 'canaryFunding')
        if 'temporaryOffer' in funding:
            need(new.get('qingfeng.payment.acceptance.enabled') == 'true'
                 and acceptance_configuration(new) == temporary_offer_scope(funding['temporaryOffer']),
                 'CANARY_PRIVATE_QUOTE_CONFIGURATION_MISMATCH')
        else:
            need(new.get('qingfeng.payment.acceptance.enabled', 'false') == 'false',
                 'PRIVATE_ACCEPTANCE_REQUIRES_EXPLICIT_FUNDING')
    if operation == 'open' and 'temporaryOffer' in business:
        funding = receipt(plan, 'canaryFunding')
        need(funding.get('candidateJarSha256') == plan['candidate']['jarSha256']
             and temporary_offer_scope(funding.get('temporaryOffer'), future=False) ==
                 temporary_offer_scope(business['temporaryOffer'], future=False), 'LIVE_ACCEPTANCE_TEMPORARY_FUNDING_MISMATCH')
    stop_units(plan)
    current_matches(plan, candidate=operation in ('canary', 'open', 'recover'))
    atomic_copy(plan['approvedPolicy'], ETC / 'release-policy.json', 0o644)
    if 'operator' in plan:
        reviewed = Path(plan['candidate']['directory']) / 'release-tools'
        atomic_copy(reviewed / 'verify-release.py', APP / 'verify-release.py', 0o755)
        atomic_copy(reviewed / 'release_gate.py', APP / 'release_gate.py', 0o644)
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
            'releaseChannels': sorted(CHANNELS) if channels is not None else ['apple_pay', 'card'],
            'newPurchaseChannels': sorted(channels) if channels is not None else ['apple_pay', 'card'] if operation in ('canary', 'open') else [],
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
    parser.add_argument('operation', choices=('inspect', 'schema-validate', 'cron-pause', 'cron-resume', 'backup', 'restore-check',
        'compose-restore', 'bind-restore', 'abort-before-ddl',
        'carry-plan', 'migrate-45', 'carry-apply', 'carry-activate', 'carry-verify',
        'install', 'canary', 'open', 'recover'))
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
            if args.operation == 'backup':
                value = backup(plan, out)
            elif args.operation == 'restore-check':
                value = restore_check(plan, out)
            elif args.operation in ('compose-restore', 'bind-restore'):
                value = compose_restore(plan, out, bind=args.operation == 'bind-restore')
            elif args.operation == 'abort-before-ddl':
                value = abort_before_ddl(plan, out)
            elif args.operation in ('schema-validate', 'carry-plan', 'migrate-45', 'carry-apply', 'carry-activate', 'carry-verify'):
                value = operator_stage(plan, out, args.operation)
            elif args.operation in ('cron-pause', 'cron-resume'):
                value = media_cron_stage(plan, args.operation)
            else:
                value = install(plan, out, args.operation)
        print(json.dumps(value))
        return 0
    except (Blocked, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'status': 'BLOCKED', 'reason': str(error) if isinstance(error, Blocked)
                          else type(error).__name__, 'automaticOldJarRollback': False,
                          'automaticDatabaseRestore': False}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
