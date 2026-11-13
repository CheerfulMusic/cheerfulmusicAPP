"""Provision new single-schema test cohorts on the verified local MySQL only."""
from pathlib import Path
import datetime, json, re, secrets, subprocess, sys
import pymysql

WORK=Path(__file__).resolve().parents[2]
ROOT=Path(subprocess.check_output(['git','-C',str(WORK),'rev-parse','--path-format=absolute','--git-common-dir']).decode().strip()).parent
OUT=ROOT/'.cache/payment-go-live-execution-20261113'
revision=sys.argv[1]
assert re.fullmatch(r'r[0-9]+',revision)
admin=json.loads((ROOT/'.cache/payment-mysql-8046-20261111/private-connection.json').read_text())
assert admin['host']=='127.0.0.1' and admin['port']==23316
proof=[]
with pymysql.connect(host='127.0.0.1',port=23316,user='root',password=admin['admin_password'],autocommit=True,connect_timeout=2) as conn,conn.cursor() as cur:
    cur.execute('SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION()');identity=cur.fetchone()
    assert identity[0]=='05c27d3c-dda3-11f1-aba0-84a938421519' and identity[1].replace('\\','/').lower()=='r:/data/' and identity[2:]==(23316,'127.0.0.1','8.0.46')
    for label,prefix in [('overlap','p0_dispute_overlap_20261113'),('history','p0_historical_cutover_20261113')]:
        database=prefix+revision;user='go_live_'+label+'_'+revision
        path=OUT/(label+'-db-private-'+revision+'.json')
        assert not path.exists(),'Never overwrite an existing test credential or cohort'
        cur.execute('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s',(database,))
        assert cur.fetchone()[0]==0,'Never clear/reinitialize a prior cohort'
        password=secrets.token_hex(32)
        cur.execute('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
        cur.execute("CREATE USER %s@'127.0.0.1' IDENTIFIED BY %s",(user,password))
        cur.execute('GRANT ALL PRIVILEGES ON `'+database+"`.* TO '"+user+"'@'127.0.0.1'")
        cfg={'host':'127.0.0.1','port':23316,'database':database,'user':user,'password':password,'uuid':identity[0]}
        with path.open('x',encoding='utf-8') as output:json.dump(cfg,output)
        with pymysql.connect(**{k:cfg[k] for k in ('host','port','database','user','password')},connect_timeout=2) as single,single.cursor() as check:
            check.execute('SHOW DATABASES');visible={row[0] for row in check.fetchall()}
            assert visible<={database,'information_schema','performance_schema'}
            check.execute('SHOW GRANTS');grants=[row[0] for row in check.fetchall()]
            assert len(grants)==2 and all(row.startswith('GRANT USAGE ON *.*') or 'ON `'+database+'`.*' in row for row in grants)
        proof.append({'database':database,'single_schema_grants':True,'production_schema_not_visible':True,'new_empty_schema':True})
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
receipt=OUT/('resources-'+revision+'-'+stamp+'.json')
receipt.write_text(json.dumps({'status':'PASS','identity':identity,'resources':proof},indent=2),encoding='utf-8')
print(json.dumps({'status':'PASS','receipt':str(receipt),'cohort':revision}))
