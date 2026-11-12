"""Create empty, single-schema local test accounts; never reuse or clean a cohort."""
from pathlib import Path
import datetime,json,re,secrets,subprocess,sys
import pymysql
SOURCE_ROOT=Path(__file__).resolve().parents[2]
ROOT=Path(subprocess.check_output(['git','-C',str(SOURCE_ROOT),'rev-parse','--path-format=absolute','--git-common-dir']).decode('utf-8').strip()).parent
OUT=ROOT/'.cache/payment-main-integration-20261112'
revision=sys.argv[1];assert re.fullmatch(r'r[0-9]+',revision)
labels=sys.argv[2:] or ['migration','credits','application']
names={'migration':'p0_integration_migration_20261112','credits':'p0_integrated_credits_20261112','application':'p0_integration_application_20261112'}
assert labels and set(labels)<=set(names)
admin=json.loads((ROOT/'.cache/payment-mysql-8046-20261111/private-connection.json').read_text())
assert admin['host']=='127.0.0.1' and admin['port']==23316
proof=[]
with pymysql.connect(host='127.0.0.1',port=23316,user='root',password=admin['admin_password'],autocommit=True,connect_timeout=2) as conn,conn.cursor() as cur:
    cur.execute('SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION()');identity=cur.fetchone()
    assert identity[0]=='05c27d3c-dda3-11f1-aba0-84a938421519' and identity[1].replace('\\','/').lower()=='r:/data/' and identity[2:]==(23316,'127.0.0.1','8.0.46')
    for label in labels:
        path=OUT/(label+'-db-private-'+revision+'.json');assert not path.exists(),'Do not overwrite credentials'
        database=names[label]+revision;user='integrated_'+label+'_'+revision
        cur.execute('SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s',(database,));assert cur.fetchone() is None,'Never clear an existing cohort'
        password=secrets.token_hex(32)
        cur.execute('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
        cur.execute("CREATE USER %s@'127.0.0.1' IDENTIFIED BY %s",(user,password))
        cur.execute('GRANT ALL PRIVILEGES ON `'+database+"`.* TO '"+user+"'@'127.0.0.1'")
        cfg={'host':'127.0.0.1','port':23316,'database':database,'user':user,'password':password,'uuid':identity[0]}
        with path.open('x',encoding='utf-8') as f:json.dump(cfg,f)
        with pymysql.connect(**{k:cfg[k] for k in ('host','port','database','user','password')},connect_timeout=2) as local,local.cursor() as check:
            check.execute('SHOW GRANTS');grants=[row[0] for row in check.fetchall()]
            assert len(grants)==2 and all(g.startswith('GRANT USAGE ON *.*') or 'ON `'+database+'`.*' in g for g in grants)
            check.execute('SHOW DATABASES');visible=[row[0] for row in check.fetchall()]
            assert set(visible)<={database,'information_schema','performance_schema'}
        proof.append({'database':database,'empty':True,'single_schema_privileges':True,'visible_schemas':visible})
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
path=OUT/('resources-'+revision+'-'+stamp+'.json');path.write_text(json.dumps({'status':'PASS','identity':identity,'resources':proof},indent=2),encoding='utf-8')
print(json.dumps({'status':'PASS','evidence':str(path)}))
