"""Exercise the actual committed fat-JAR operator entry points on a new local cohort.

Requires --run, a verified artifact receipt and a never-used migration cohort.
No Spring application, real user data, supplier call, service or production resource.
Synthetic approval receipts are marked testOnly and cannot prove a production backup.
"""
from pathlib import Path
import argparse, datetime as dt, hashlib, importlib.util, io, json, os, re, subprocess, sys, zipfile
import pymysql
from java_runtime import resolve_java_binary

WORK = Path(__file__).resolve().parents[2]
ROOT = Path(subprocess.check_output(['git','-C',str(WORK),'rev-parse','--path-format=absolute','--git-common-dir']).decode('utf-8').strip()).parent
BASE = ROOT / '.cache/payment-main-integration-20261112'
UUID = '05c27d3c-dda3-11f1-aba0-84a938421519'
CORE_MANIFEST = '736294575e354900170114f8eb9568abaf15223a0bf7c9872d314d65559905e1'
FINANCE_SHA = '3dd5cebf19dee960ecefb2239668139918d363496fe87710f817c5b3a3241685'

def need(value, code):
    if not value:
        raise RuntimeError(code)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def exclusive_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, default=str)

def properties(path, values):
    def escape(value):
        return str(value).replace('\\','\\\\').replace('\r','\\r').replace('\n','\\n')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd,'w',encoding='utf-8',newline='\n') as stream:
        for key, value in values.items():
            stream.write(key + '=' + escape(value) + '\n')

def connect(cfg):
    return pymysql.connect(**{k:cfg[k] for k in ('host','port','database','user','password')},
                           autocommit=True,connect_timeout=3,read_timeout=15,
                           init_command="SET SESSION time_zone='+00:00'")

def prove(cfg, empty=False):
    with connect(cfg) as connection, connection.cursor() as cursor:
        cursor.execute('SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION(),DATABASE()')
        found=cursor.fetchone()
        need(found[0]==UUID and found[1].replace('\\','/').lower()=='r:/data/'
             and found[2:]==(23316,'127.0.0.1','8.0.46',cfg['database']), 'ISOLATED_DATABASE_IDENTITY_MISMATCH')
        cursor.execute('SHOW DATABASES');visible={row[0] for row in cursor.fetchall()}
        need(visible<={cfg['database'],'information_schema','performance_schema'},'OTHER_SCHEMA_VISIBLE')
        cursor.execute('SHOW GRANTS');grants=[row[0] for row in cursor.fetchall()]
        need(len(grants)==2 and all(row.startswith('GRANT USAGE ON *.*') or 'ON `'+cfg['database']+'`.*' in row for row in grants)
             and not any('WITH GRANT OPTION' in row for row in grants),'SINGLE_SCHEMA_ACCOUNT_REQUIRED')
        cursor.execute('SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER=%s',(cfg['user'],))
        need(cursor.fetchone()==(0,), 'COHORT_STILL_IN_USE')
        if empty:
            cursor.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()')
            need(cursor.fetchone()==(0,), 'NEVER_CLEAR_OR_REUSE_A_PREVIOUS_COHORT')
    return {'uuid':UUID,'port':23316,'database':cfg['database'],'datadir':'R:/data/',
            'single_schema_grants':True,'production_schema_visible':False,'other_account_clients':0}

def financial_snapshot(cfg):
    result={}
    with connect(cfg) as connection, connection.cursor() as cursor:
        for table in ('creator','credit_ledger','credit_account','payment_order','credit_consumption'):
            cursor.execute('SELECT * FROM '+table)
            rows=sorted(json.dumps(row,ensure_ascii=True,separators=(',',':'),default=str) for row in cursor.fetchall())
            result[table]={'rows':len(rows),'sha256':sha(json.dumps(rows,separators=(',',':')).encode())}
    return result

def execute(args):
    need(re.fullmatch(r'r[0-9]+', args.migration_revision),'REVISION_REQUIRED')
    need(re.fullmatch(r'artifact-[0-9TZ]+/results.json',args.artifact_evidence),'VERIFIED_ARTIFACT_RECEIPT_REQUIRED')
    artifact_path=(BASE/args.artifact_evidence).resolve();need(artifact_path.is_relative_to(BASE.resolve()),'ARTIFACT_RECEIPT_PATH_OUTSIDE_CACHE')
    artifact=json.loads(artifact_path.read_text('utf-8'))
    commit=subprocess.check_output(['git','-C',str(WORK),'rev-parse','HEAD']).decode().strip()
    need(artifact.get('status')=='PASS' and artifact.get('source_commit')==commit,'EXACT_COMMIT_ARTIFACT_REQUIRED')
    jar=Path(artifact['jar']).resolve();need(jar.is_relative_to(BASE.resolve()) and jar.is_file(),'CACHED_ARTIFACT_REQUIRED')
    jar_sha=sha(jar.read_bytes());need(jar_sha==artifact['jar_sha256'],'CANDIDATE_JAR_CHANGED')
    cfg=json.loads((BASE/('migration-db-private-'+args.migration_revision+'.json')).read_text('utf-8'))
    need(cfg['host']=='127.0.0.1' and cfg['port']==23316 and cfg['uuid']==UUID
         and cfg['database']=='p0_integration_migration_20261112'+args.migration_revision
         and cfg['user']!='root','DEDICATED_LOCAL_MIGRATION_COHORT_REQUIRED')
    isolation=prove(cfg,empty=True)
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    out=BASE/('packaged-financial-commands-'+stamp);out.mkdir(exist_ok=False)
    result={'status':'FAIL','scope':'PACKAGED_LOCAL_SYNTHETIC_MYSQL_COMMANDS_ONLY','source_commit':commit,
            'jar_sha256':jar_sha,'isolation':isolation,'production_access':False,'vendor_calls':False,
            'spring_application_started':False,'real_payment_acceptance':'NOT_TESTED',
            'production_backup_acceptance':'NOT_TESTED','approval_receipts':'SYNTHETIC_TEST_ONLY','stages':[]}
    # Explicit platform-only environment: no inherited loader, Java-agent,
    # datasource, supplier, profile or secret variables can affect these CLIs.
    platform={'PATH','SYSTEMROOT','WINDIR','TEMP','TMP','COMSPEC','PATHEXT','USERPROFILE',
              'APPDATA','LOCALAPPDATA','JAVA_HOME','LANG','LC_ALL'}
    env={k:v for k,v in os.environ.items() if k.upper() in platform}
    java=str(resolve_java_binary())
    loader_config=out/'empty-loader.properties'
    with loader_config.open('x',encoding='utf-8') as stream:stream.write('')
    dbfile=out/'database.private.properties'
    url='jdbc:mysql://127.0.0.1:23316/'+cfg['database']+'?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    properties(dbfile,{'spring.datasource.url':url,'spring.datasource.username':cfg['user'],'spring.datasource.password':cfg['password']})
    try:
        libs=out/'libraries';libs.mkdir();sql=out/'canonical-migrations';sql.mkdir()
        migrations={}
        with zipfile.ZipFile(jar) as archive:
            need('org/springframework/boot/loader/launch/PropertiesLauncher.class' in archive.namelist(),'PROPERTIES_LAUNCHER_REQUIRED')
            for entry in archive.infolist():
                if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
                    name=entry.filename.removeprefix('BOOT-INF/lib/')
                    need('/' not in name and '\\' not in name,'SAFE_LIBRARY_NAME_REQUIRED')
                    data=archive.read(entry);(libs/name).write_bytes(data)
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        for member in nested.namelist():
                            if re.fullmatch(r'db/migration/V[1-9][0-9]*__[A-Za-z0-9_]+\.sql',member):
                                script=member.rsplit('/',1)[1];need(script not in migrations,'DUPLICATE_PACKAGED_SQL')
                                migrations[script]=nested.read(member)
                elif re.fullmatch(r'BOOT-INF/classes/db/migration/V[1-9][0-9]*__[A-Za-z0-9_]+\.sql',entry.filename):
                    name=entry.filename.rsplit('/',1)[1];need(name not in migrations,'DUPLICATE_PACKAGED_SQL');migrations[name]=archive.read(entry)
        need(len(migrations)==45,'EXACT_45_PACKAGED_MIGRATIONS_REQUIRED')
        spec=importlib.util.spec_from_file_location('packaged_migration_gate',WORK/'scripts/release_gate.py')
        gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
        gate.verify_migration_set({name:gate.normalized_migration_sha(data) for name,data in migrations.items()})
        for name,data in migrations.items():
            with (sql/name).open('xb') as stream:stream.write(data)
        initializer=out/'Core44Setup.java'
        initializer.write_text('''import java.nio.file.*;import java.util.*;import org.flywaydb.core.Flyway;
class Core44Setup { public static void main(String[] a) { try {
var p=new Properties();try(var r=Files.newBufferedReader(Path.of(a[0]))){p.load(r);}
var f=Flyway.configure().dataSource(p.getProperty("spring.datasource.url"),p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"))
.locations("filesystem:"+a[1]).target("44").cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).load();
f.migrate();f.validate();System.out.println("CORE44_INIT_PASS");
}catch(Throwable t){System.out.println("CORE44_INIT_FAILED");System.exit(2);}}}
''',encoding='utf-8')
        initialized=subprocess.run([java,'-cp',str(libs/'*'),str(initializer),str(dbfile),str(sql)],cwd=out,env=env,capture_output=True,timeout=90)
        need(initialized.returncode==0 and b'CORE44_INIT_PASS' in initialized.stdout,'CORE44_INITIALIZATION_FAILED')
        with connect(cfg) as connection,connection.cursor() as cursor:
            cursor.execute('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL');need(cursor.fetchone()==(44,),'CORE44_HISTORY_REQUIRED')
            owner='10000000-0000-0000-0000-000000000001'
            cursor.execute("INSERT INTO creator(id,created_at) VALUES(%s,'2020-01-01 00:00:00')",(owner,))
            cursor.execute("INSERT INTO credit_ledger VALUES('CREDIT','packaged-fixture-credit',%s,100,'2020-01-01 00:00:00'),('DEBIT','packaged-fixture-returned',%s,-20,'2020-01-01 00:00:01'),('REFUND','packaged-fixture-returned',%s,20,'2020-01-01 00:00:02')",(owner,owner,owner))
            cursor.execute("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(%s,100,'2020-01-01 00:00:02')",(owner,))
        before=financial_snapshot(cfg);result['original_financial_snapshot']=before
        approval={'expected.uuid':UUID,'expected.database':cfg['database'],'expected.port':'23316',
                  'candidate.jar.path':str(jar),'candidate.jar.sha256':jar_sha,
                  'approval.evidence':'SYNTHETIC_PACKAGED_ISOLATION_ONLY'}
        def cli(class_name,mode,values,label,expected='PASS'):
            private=out/(label+'.private.properties');properties(private,values)
            cmd=[java,'-Dloader.config.location='+loader_config.as_uri(),'-Dloader.path=',
                 '-Dloader.main='+class_name,'-cp',str(jar),
                 'org.springframework.boot.loader.launch.PropertiesLauncher',mode,str(dbfile),str(private)]
            ran=subprocess.run(cmd,cwd=out,env=env,capture_output=True,timeout=90)
            parsed=[]
            for line in ran.stdout.decode('utf-8',errors='replace').splitlines():
                try:
                    value=json.loads(line)
                    if isinstance(value,dict) and value.get('status') in ('PASS','BLOCKED'):parsed.append(value)
                except ValueError:pass
            need(len(parsed)==1,'SINGLE_REDACTED_COMMAND_RECEIPT_REQUIRED')
            value=parsed[0];exclusive_json(out/(label+'.json'),value)
            result['stages'].append({'stage':label,'exit_code':ran.returncode,'status':value.get('status'),'stderr_sha256':sha(ran.stderr)})
            need(value.get('status')==expected and ran.returncode==(0 if expected=='PASS' else 2),'PACKAGED_COMMAND_FAILED_'+label.upper())
            return value
        carry='com.qingfeng.music.infrastructure.persistence.credits.CreditCarryForwardCommand'
        financial='com.qingfeng.music.server.config.FinancialMigrationCommand'
        plan=cli(carry,'plan',approval,'read-only-plan')
        need(plan['owners']==1 and plan['debitMappings']==1 and not plan['issues'],'NONEMPTY_CARRY_PLAN_REQUIRED')
        approval.update({'source.sha256':plan['sourceSha256'],'plan.sha256':plan['planSha256'],
                         'core.manifest.sha256':CORE_MANIFEST,'migration.version':'45','migration.sha256':FINANCE_SHA})
        validated=cli(financial,'validate',approval,'validate-core44')
        need(validated.get('schemaVersion')==44 and validated.get('state')=='NOT_INSTALLED','READ_ONLY_CORE44_VALIDATION_REQUIRED')
        need(financial_snapshot(cfg)==before,'READ_ONLY_VALIDATION_CHANGED_MONEY')
        cli(financial,'migrate',approval,'reject-without-authorization',expected='BLOCKED')
        for kind in ('writers','backup-restore','rollback','legacy-boundary'):
            receipt={'status':'PASS','testOnly':True,'productionApproval':False,'databaseUuid':UUID,'database':cfg['database'],'port':23316,
                     'sourceSha256':plan['sourceSha256'],'candidateJarSha256':jar_sha,'recordedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
                     'contractVersion':2,'protocol':3,'schemaVersion':45,'rollbackJarSha256':jar_sha,
                     'otherSchemaConnections':0,'otherApplicationAccountConnections':0,'paymentEnvironment':'LIVE',
                     'allowedNewPaymentProviders':['STRIPE_LIVE','PAYPAL_LIVE']}
            receipt.update({key:True for key in ('serviceInactive','otherWritersStopped','connectionsDrained','globalProcesslistVerified','observerHasProcessPrivilege',
                           'restoreVerified','originalJournalUnchanged','newPurchasesDisabled','restoredStartupVerified','legacyOrdersReadOnly','balancesPreserved','allNewOrdersUseFencedLedger')})
            path=out/(kind+'-synthetic-test-only.json');exclusive_json(path,receipt)
            approval['receipt.'+kind+'.path']=str(path);approval['receipt.'+kind+'.sha256']=sha(path.read_bytes())
        approval['migration.authorized']='true'
        migrated=cli(financial,'migrate',approval,'migrate-financial45')
        need(migrated.get('schemaVersion')==45 and migrated.get('migrationsExecuted')==1 and migrated.get('state')=='PREPARING','EXACT_ONE_NONACTIVATING_MIGRATION_REQUIRED')
        replay=cli(financial,'migrate',approval,'replay-financial45')
        need(replay.get('migrationsExecuted')==0 and replay.get('state')=='PREPARING','IDEMPOTENT_NONACTIVATING_REPLAY_REQUIRED')
        need(financial_snapshot(cfg)==before,'FINANCIAL_DDL_CHANGED_ORIGINAL_MONEY')
        imported=cli(carry,'apply',approval,'apply-carry-forward');need(imported.get('state')=='PREPARING','MAPPING_MUST_NOT_ACTIVATE')
        activated=cli(carry,'activate',approval,'activate-carry-forward');need(activated.get('state')=='ACTIVE','EXPLICIT_ACTIVATION_REQUIRED')
        verified=cli(carry,'verify',approval,'verify-carry-forward');need(verified.get('state')=='ACTIVE','ACTIVE_RECONCILIATION_REQUIRED')
        need(financial_snapshot(cfg)==before,'CARRY_FORWARD_CHANGED_ORIGINAL_MONEY')
        with connect(cfg) as connection,connection.cursor() as cursor:
            cursor.execute('SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL');need(cursor.fetchone()==(45,),'FINAL_HISTORY_NOT_EXACT45')
            cursor.execute('SELECT SUM(original-consumed-adjusted) FROM p0_credit_batch');need(int(cursor.fetchone()[0])==100,'OPENING_BALANCE_MISMATCH')
            cursor.execute('SELECT COUNT(*) FROM p0_credit_allocation');need(cursor.fetchone()==(0,),'NO_FABRICATED_HISTORICAL_FIFO_ALLOWED')
        need(sha(jar.read_bytes())==jar_sha,'ARTIFACT_CHANGED_DURING_ACCEPTANCE')
        result.update({'status':'PASS','final_financial_snapshot':before,'original_money_unchanged':True,
                       'migrations_executed':1,'replay_migrations_executed':0,'final_state':'ACTIVE',
                       'synthetic_opening':100,'historical_allocations_fabricated':False})
    except Exception as failure:
        # Never print raw driver/subprocess messages or private configuration.
        code=str(failure)
        result['failure']=code if re.fullmatch(r'[A-Z0-9_]+',code) else 'PACKAGED_ISOLATED_ACCEPTANCE_FAILED'
    finally:
        exclusive_json(out/'results.json',result)
    print(json.dumps({'status':result['status'],'evidence':str(out/'results.json'),'production_access':False,'real_payment_acceptance':'NOT_TESTED'}))
    return 0 if result['status']=='PASS' else 2

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',action='store_true')
    parser.add_argument('--artifact-evidence')
    parser.add_argument('--migration-revision')
    args=parser.parse_args()
    if not args.run:
        print('BLOCKED: --run and a verified artifact/fresh local cohort are required; no connection opened')
        sys.exit(0)
    need(bool(args.artifact_evidence) and bool(args.migration_revision),'EXPLICIT_ARTIFACT_AND_COHORT_REQUIRED')
    sys.exit(execute(args))
