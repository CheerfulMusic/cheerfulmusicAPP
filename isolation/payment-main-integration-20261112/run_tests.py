"""Run the integrated local candidate without inheriting payment/production credentials.

Credentials live only in ignored local files and child-process environment. This runner
never connects to a database itself and never uses the daily 13306 production tunnel.
"""
from pathlib import Path
import argparse, datetime, json, os, shutil, subprocess, sys
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')

SOURCE_ROOT = Path(__file__).resolve().parents[2]
ROOT = Path(subprocess.check_output(['git','-C',str(SOURCE_ROOT),'rev-parse','--path-format=absolute','--git-common-dir']).decode('utf-8').strip()).parent
OUT = ROOT / '.cache/payment-main-integration-20261112'
parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=('compile', 'targeted', 'full', 'package'))
parser.add_argument('--workspace',default=str(ROOT / '.cache/worktrees/payment-integrated-20261112'))
parser.add_argument('--release-source',default='')
parser.add_argument('--tests', default='')
parser.add_argument('--migration-revision', default='r1')
parser.add_argument('--credits-revision', default='r1')
parser.add_argument('--application-revision', default='r1')
parser.add_argument('--legacy40-revision',default='r6')
parser.add_argument('--core40-revision',default='r9')
parser.add_argument('--compatibility-port', type=int, default=57326)
parser.add_argument('--go-live-revision', default='')
args = parser.parse_args()
WORK=Path(args.workspace).resolve()
assert WORK!=ROOT and (WORK/'.git').exists(),'Run in an explicit isolated worktree'
assert args.compatibility_port in (57326,57336)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
folder = OUT / ('maven-' + args.mode + '-' + stamp)
folder.mkdir(parents=True, exist_ok=False)
# Maven copies resources but does not remove deleted migration files. Preserve
# generated reports first, then use a fresh reactor output for every verification.
for module in ('','qf-common','qf-business','qf-infrastructure','qf-server'):
    target=(WORK/'Qingfeng_AI_Music'/module/'target').resolve()
    assert target.is_relative_to(WORK) and target.name=='target'
    reports=target/'surefire-reports'
    if reports.is_dir():shutil.copytree(reports,folder/'previous-reports'/(module or 'parent'))
blocked = ('SECRET','TOKEN','PASSWORD','KEY','CREDENTIAL','CREDIT_','CONFIG','DATABASE','CONNECTION','DSN','INVITE_GATE','PAYPAL','STRIPE','MYSQL','SPRING_',
           'JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','OPENAI',
           'ANTHROPIC','KIE_','OSS_','AWS_','VITE_','QF_','P0_')
env = {k:v for k,v in os.environ.items() if not any(x in k.upper() for x in blocked)}
env.update({'QF_EXECUTION_MYSQL_TEST_PORT':str(args.compatibility_port),
            'QF_QUICK_MYSQL_TEST_PORT':str(args.compatibility_port),
            'QF_DOWNLOAD_MYSQL_TEST_PORT':str(args.compatibility_port),
            'SPRING_FLYWAY_TARGET':'43'})
for label, prefix, revision in (
    ('migration','P0_INTEGRATION_MIGRATION_MYSQL_',args.migration_revision),
    ('credits','P0_INTEGRATED_CREDITS_MYSQL_',args.credits_revision),
    ('application','P0_INTEGRATION_APPLICATION_MYSQL_',args.application_revision),
    ('legacy40','P0_INTEGRATION_LEGACY40_MYSQL_',args.legacy40_revision),
    ('core40','P0_CORE40_APPLICATION_MYSQL_',args.core40_revision)):
    assert revision.startswith('r') and revision[1:].isdigit()
    cfg = json.loads((OUT / (label+'-db-private-'+revision+'.json')).read_text())
    assert cfg['host']=='127.0.0.1' and cfg['port']==23316 and cfg['uuid']=='05c27d3c-dda3-11f1-aba0-84a938421519'
    assert cfg['database'].startswith('p0_core40_application_' if label=='core40' else 'p0_integr') and cfg['database'].endswith(revision)
    env[prefix+'URL']='jdbc:mysql://127.0.0.1:23316/'+cfg['database']+'?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    env[prefix+'DATABASE']=cfg['database']
    env[prefix+'USER']=cfg['user']; env[prefix+'PASSWORD']=cfg['password']; env[prefix+'UUID']=cfg['uuid']
if args.go_live_revision:
    assert args.go_live_revision.startswith('r') and args.go_live_revision[1:].isdigit()
    for label,prefix,database_prefix in (
        ('overlap','P0_DISPUTE_OVERLAP_MYSQL_','p0_dispute_overlap_20261113'),
        ('history','P0_HISTORICAL_CUTOVER_MYSQL_','p0_historical_cutover_20261113')):
        cfg=json.loads((ROOT/'.cache/payment-go-live-execution-20261113'/(label+'-db-private-'+args.go_live_revision+'.json')).read_text())
        assert cfg['host']=='127.0.0.1' and cfg['port']==23316 and cfg['uuid']=='05c27d3c-dda3-11f1-aba0-84a938421519'
        assert cfg['database']==database_prefix+args.go_live_revision
        env[prefix+'URL']='jdbc:mysql://127.0.0.1:23316/'+cfg['database']+'?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
        env[prefix+'DATABASE']=cfg['database'];env[prefix+'USER']=cfg['user'];env[prefix+'PASSWORD']=cfg['password'];env[prefix+'UUID']=cfg['uuid']
cmd = ['mvn.cmd','-o','-Dmaven.repo.local='+str(ROOT/'.cache/maven'),'clean']
if args.mode=='compile': cmd += ['-DskipTests','compile']
elif args.mode=='package':
    assert len(args.release_source)==40 and all(c in '0123456789abcdef' for c in args.release_source)
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=WORK).decode().strip()==args.release_source
    cmd += ['-Drelease.commit='+args.release_source,'-DskipTests','package']
else:
    if args.tests: cmd += ['-Dtest='+args.tests,'-Dsurefire.failIfNoSpecifiedTests=false']
    cmd += ['verify']
(folder/'invocation.json').write_text(json.dumps({'command':cmd,'cwd':str(WORK/'Qingfeng_AI_Music'),
    'production_credentials_inherited':False,'mysql_ports':[23316,args.compatibility_port],
    'legacy_mysql_target':'43','new_contract_target':'44 explicit in tests'},indent=2),encoding='utf-8')
print(json.dumps({'log':str(folder/'maven.log'),'mode':args.mode}),flush=True)
with (folder/'maven.log').open('w',encoding='utf-8') as log:
    result=subprocess.run(cmd,cwd=WORK/'Qingfeng_AI_Music',env=env,stdout=log,stderr=subprocess.STDOUT)
suites=[]
for path in (WORK/'Qingfeng_AI_Music').glob('*/target/surefire-reports/TEST-*.xml'):
    if path.stat().st_mtime < datetime.datetime.strptime(stamp,'%Y%m%dT%H%M%S%fZ').replace(tzinfo=datetime.timezone.utc).timestamp()-2: continue
    element=ET.parse(path).getroot()
    suites.append({'name':element.get('name'),**{key:int(element.get(key,'0')) for key in ('tests','failures','errors','skipped')}})
summary={'exit_code':result.returncode,'status':'PASS' if result.returncode==0 else 'FAIL',
    'totals':{key:sum(s[key] for s in suites) for key in ('tests','failures','errors','skipped')},'suites':suites}
(folder/'results.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
print(json.dumps({'evidence':str(folder/'results.json'),**{k:v for k,v in summary.items() if k!='suites'}}),flush=True)
if result.returncode:
    text=(folder/'maven.log').read_text(encoding='utf-8',errors='replace')
    print('\n'.join(text.splitlines()[-65:]))
sys.exit(result.returncode)
