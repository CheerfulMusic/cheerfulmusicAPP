"""Start only the committed local JAR against the proven synthetic restore schema.

This proves packaged startup, anonymous-session read APIs and closed payment gates.
It does not prove OAuth login, reading a restored nonzero balance over HTTP, or Live payment.
No daily runtime, production database, external provider or Windows service is used.
"""
from pathlib import Path
import datetime, hashlib, http.cookiejar, json, os, socket, subprocess, sys, time
import urllib.request, urllib.error
import pymysql

sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')
WORK=Path(__file__).resolve().parents[2]
ROOT=Path(subprocess.check_output(['git','-C',str(WORK),'rev-parse','--path-format=absolute','--git-common-dir']).decode('utf-8').strip()).parent
BASE=ROOT/'.cache/payment-main-integration-20261112'
CFG=json.loads((BASE/'restore-db-private-r2.json').read_text(encoding='utf-8'))
assert CFG['host']=='127.0.0.1' and CFG['port']==23316 and CFG['database']=='p0_integration_restore_20261112r2'
assert CFG['uuid']=='05c27d3c-dda3-11f1-aba0-84a938421519'
RECEIPTS=[json.loads(p.read_text(encoding='utf-8')) for p in BASE.glob('backup-restore-*/results.json')]
assert any(r.get('status')=='PASS' and r.get('restore_database')==CFG['database'] for r in RECEIPTS),'Complete restore proof required'
COMMIT=subprocess.check_output(['git','-C',str(WORK),'rev-parse','HEAD']).decode().strip()
artifacts=[json.loads(p.read_text(encoding='utf-8')) for p in sorted(BASE.glob('artifact-*/results.json'))]
artifacts=[a for a in artifacts if a.get('status')=='PASS' and a.get('source_commit')==COMMIT]
assert artifacts,'A verified fresh-export artifact for this exact commit is required'
JAR=Path(artifacts[-1]['jar']).resolve()
assert JAR.is_relative_to(BASE.resolve()) and hashlib.sha256(JAR.read_bytes()).hexdigest()==artifacts[-1]['jar_sha256']
assert JAR.is_file()
STAMP=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
OUT=BASE/('jar-restore-'+STAMP);OUT.mkdir()
def connection():return pymysql.connect(**{k:CFG[k] for k in ('host','port','database','user','password')},connect_timeout=3,init_command='SET SESSION TRANSACTION READ ONLY')

FINANCIAL_TABLES=('flyway_schema_history','credit_ledger','credit_account','payment_order','payment_webhook_inbox',
    'payment_inbox_attempt','payment_inbox_review','shared_download_entitlement','p0_credit_writer_policy',
    'p0_credit_cutover_state','p0_credit_batch','p0_credit_allocation','p0_credit_payment_binding','p0_credit_refund',
    'p0_credit_dispute','p0_credit_return','p0_credit_financial_audit','p0_legacy_debit_mapping','p0_credit_recovery',
    'p0_credit_recovery_audit','p0_credit_recovery_approval','p0_credit_refund_terminal','p0_credit_refund_return',
    'p0_stripe_dispute_proof','p0_credit_native_identity')

def financial_snapshot():
    # All table names are fixed code constants. Retain only counts and hashes in evidence.
    facts={}
    with connection() as db,db.cursor() as cur:
        for table in FINANCIAL_TABLES:
            cur.execute('SELECT * FROM `'+table+'`')
            rows=sorted(json.dumps(row,ensure_ascii=True,separators=(',',':'),default=str) for row in cur.fetchall())
            facts[table]={'rows':len(rows),'sha256':hashlib.sha256(json.dumps(rows,separators=(',',':')).encode('utf-8')).hexdigest()}
    return facts
with connection() as db,db.cursor() as cur:
    cur.execute('SELECT @@server_uuid,@@datadir,@@port,@@bind_address,DATABASE()');identity=cur.fetchone()
    assert identity[0]==CFG['uuid'] and identity[1].replace('\\','/').lower()=='r:/data/' and identity[2:]==(23316,'127.0.0.1',CFG['database'])
    cur.execute('SHOW DATABASES');assert set(r[0] for r in cur.fetchall())<={CFG['database'],'information_schema','performance_schema'}
    cur.execute('SHOW GRANTS');grants=[r[0] for r in cur.fetchall()];assert len(grants)==2 and all(g.startswith('GRANT USAGE ON *.*') or 'ON `'+CFG['database']+'`.*' in g for g in grants)
    cur.execute("SELECT status FROM p0_credit_cutover_state WHERE id=1");assert cur.fetchone()==('ACTIVE',)
    cur.execute("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL");assert cur.fetchone()==(44,)
financial_before=financial_snapshot()
assert financial_before['credit_ledger']['rows']>0 and financial_before['credit_account']['rows']>0,'A restored nonempty financial fixture is required'
with socket.socket() as probe:
    probe.bind(('127.0.0.1',0));PORT=probe.getsockname()[1]
blocked=('SECRET','TOKEN','PASSWORD','KEY','CREDENTIAL','CREDIT_','CONFIG','DATABASE','CONNECTION','DSN','INVITE_GATE','PAYPAL','STRIPE','MYSQL','SPRING_','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','OPENAI','ANTHROPIC','KIE_','OSS_','AWS_','VITE_','QF_','P0_')
env={k:v for k,v in os.environ.items() if not any(s in k.upper() for s in blocked)}
url='jdbc:mysql://127.0.0.1:23316/'+CFG['database']+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
env.update({'MYSQL_URL':url,'MYSQL_USERNAME':CFG['user'],'MYSQL_PASSWORD':CFG['password'],
    'SPRING_DATASOURCE_URL':url,'SPRING_DATASOURCE_USERNAME':CFG['user'],'SPRING_DATASOURCE_PASSWORD':CFG['password'],
    'CREDIT_WRITER_FENCE_ENABLED':'true','CREDIT_WRITER_EXPECTED_DATABASE':CFG['database'],'CREDIT_WRITER_EXPECTED_PORT':'23316','CREDIT_SCHEMA_TARGET':'44'})
flags={'spring.config.location':'classpath:/application.yml','spring.profiles.active':'mysql','server.address':'127.0.0.1','server.port':str(PORT),
    'spring.flyway.enabled':'false','spring.sql.init.mode':'never','qingfeng.stripe.enabled':'false','qingfeng.stripe.accepting-new-orders':'false',
    'qingfeng.paypal.enabled':'false','qingfeng.paypal.accepting-new-orders':'false','qingfeng.payments.enabled':'false','qingfeng.payment.mock':'false',
    'qingfeng.payment.inbox.scheduler-enabled':'false','qingfeng.generation.enabled':'false','qingfeng.generation.scheduler-enabled':'false',
    'qingfeng.quick.enabled':'false','qingfeng.quick.scheduler-enabled':'false','qingfeng.lyrics.enabled':'false','qingfeng.cover.ai.enabled':'false',
    'qingfeng.cover.ai.scheduler-enabled':'false','qingfeng.avatar.ai.enabled':'false','qingfeng.avatar.ai.scheduler-enabled':'false',
    'qingfeng.agent.chat.enabled':'false','qingfeng.agent.legacy-pending.enabled':'false','qingfeng.agent.retrieval.enabled':'false',
    'qingfeng.agent.retrieval.scheduler-enabled':'false','qingfeng.events.dispatcher-enabled':'false','qingfeng.events.rabbit.enabled':'false',
    'qingfeng.redis.enabled':'false','qingfeng.analytics.retention.scheduler-enabled':'false','qingfeng.invite-gate.enabled':'false',
    'qingfeng.auth.google.enabled':'false','qingfeng.auth.logto.enabled':'false','qingfeng.inspiration.rewrite.enabled':'false',
    'qingfeng.media.directory':str(OUT/'audio'),'qingfeng.cover.directory':str(OUT/'covers')}
cmd=['java','-jar',str(JAR)]+['--'+k+'='+v for k,v in flags.items()]
result={'status':'FAIL','scope':'PACKAGED_RESTORED_SYNTHETIC_DB_STARTUP_ONLY','jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),
    'source_commit':COMMIT,
    'database':CFG['database'],'uuid':CFG['uuid'],'port':PORT,'production_access':False,'vendor_calls':False,'migrations_enabled':False,
    'live_payment_acceptance':'NOT_TESTED','oauth_acceptance':'NOT_TESTED','restored_nonzero_balance_http_acceptance':'NOT_TESTED',
    'script_database_connections_read_only':True,'actual_command_no_credentials':cmd}
process=None
try:
    with (OUT/'application.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(cmd,cwd=OUT,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        base='http://127.0.0.1:'+str(PORT)
        cookies=http.cookiejar.CookieJar()
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(cookies))
        def request(path,method='GET',data=None,headers=None):
            req=urllib.request.Request(base+path,data=data,headers=headers or {},method=method)
            try:
                with opener.open(req,timeout=4) as response:return response.status,response.read()
            except urllib.error.HTTPError as response:return response.code,response.read()
        deadline=time.monotonic()+60
        while True:
            assert process.poll() is None,'Packaged application exited before healthy'
            try:
                status,body=request('/actuator/health')
                if status==200 and json.loads(body).get('status')=='UP':break
            except (OSError,ValueError):pass
            assert time.monotonic()<deadline,'Packaged startup timed out'
            time.sleep(.25)
        status,index=request('/');assert status==200 and b'<html' in index.lower()
        status,csrf=request('/api/public/csrf');assert status==200;token=json.loads(csrf)['token']
        status,session=request('/api/public/session','POST',b'{}',{'Content-Type':'application/json','X-XSRF-TOKEN':token});assert status==200
        owner=json.loads(session)['creatorId']
        session_tokens=[cookie.value for cookie in cookies if cookie.name=='QF_SESSION']
        assert len(session_tokens)==1 and len(session_tokens[0])==43,'A new application-issued guest cookie is required'
        session_hash=hashlib.sha256(session_tokens[0].encode('utf-8')).hexdigest()
        status,view=request('/api/app/credits');assert status==200;view=json.loads(view)
        assert view['musicCost']==5 and view['lyricsCost']==1,'Preserve the current main pricing policy'
        with connection() as db,db.cursor() as cur:
            cur.execute('SELECT DATABASE(),@@server_uuid,@@port');assert cur.fetchone()==(CFG['database'],CFG['uuid'],23316)
            cur.execute('SELECT COUNT(*) FROM creator c JOIN creator_session s ON s.creator_id=c.id WHERE c.id=%s AND s.token_hash=%s AND s.expires_at>UTC_TIMESTAMP()',(owner,session_hash))
            assert cur.fetchone()==(1,),'The HTTP-issued creator and exact guest session must persist in the verified restore schema'
            cur.execute('SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=%s',(owner,));assert int(cur.fetchone()[0])==view['balance']
        for provider in ('stripe','paypal'):
            status,catalog=request('/api/app/'+provider+'/catalog');assert status==200 and json.loads(catalog)['enabled'] is False
        status,_=request('/api/public/stripe/webhook','POST',b'{}',{'Content-Type':'application/json'});assert status==503
        financial_after=financial_snapshot();assert financial_after==financial_before,'Startup and guest reads must not alter restored financial facts'
        result.update({'status':'PASS','health':'UP','frontend_served':True,'anonymous_session_credit_api_matches_journal':True,
                       'guest_creator_and_exact_session_persisted_in_expected_schema':True,'restored_financial_facts_unchanged':True,
                       'financial_snapshot_before':financial_before,'financial_snapshot_after':financial_after,
                       'payment_catalogs_disabled':True,'unsigned_unconfigured_webhook_status':503,'provider_or_money_actions':0})
finally:
    if process is not None:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        result['owned_process_stopped']=process.poll() is not None
    path=OUT/'results.json';path.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({'status':result['status'],'evidence':str(path),'owned_process_stopped':result.get('owned_process_stopped')}))
