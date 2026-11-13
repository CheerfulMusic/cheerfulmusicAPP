"""Own a new loopback-only core43 test instance; never repair/clear the old core40 server."""
from pathlib import Path
import datetime, json, socket, subprocess, sys, time
import pymysql

WORK=Path(__file__).resolve().parents[2]
ROOT=Path(subprocess.check_output(['git','-C',str(WORK),'rev-parse','--path-format=absolute','--git-common-dir']).decode().strip()).parent
BASE=ROOT/'.cache/payment-mysql-8046-20261111'
OUT=ROOT/'.cache/stripe-wallets-20261113'
DATA=Path('R:/wallets-compatibility-r2')
INI=Path('R:/wallets-compatibility-r2.ini')
PORT=57336
mode=sys.argv[1] if len(sys.argv)>1 else 'status'
assert mode in ('start','status','stop')
assert Path('R:/download.json').read_bytes()==(BASE/'download.json').read_bytes()
if mode=='start':
    with socket.socket() as probe:probe.bind(('127.0.0.1',PORT))
    assert not DATA.exists() and not INI.exists(),'Never reinitialize an existing test resource'
    DATA.mkdir()
    INI.write_text('[mysqld]\nbasedir=R:/mysql-8.0.46-winx64\ndatadir='+DATA.as_posix()+
        '\nlc-messages-dir=R:/mysql-8.0.46-winx64/share\nbind-address=127.0.0.1\nport='+str(PORT)+
        '\nmysqlx=0\nevent-scheduler=OFF\nlocal-infile=0\nskip-log-bin\ngeneral-log=OFF\ninnodb-buffer-pool-size=128M\nlog-error=R:/wallets-compatibility-r2-error.log\n',encoding='ascii')
    binary='R:/mysql-8.0.46-winx64/bin/mysqld.exe'
    initialized=subprocess.run([binary,'--defaults-file='+INI.as_posix(),'--initialize-insecure'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    assert initialized.returncode==0,'New isolated initialization failed'
    process=subprocess.Popen([binary,'--defaults-file='+INI.as_posix()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
    for _ in range(150):
        try:
            connection=pymysql.connect(host='127.0.0.1',port=PORT,user='root',password='',connect_timeout=1);connection.close();break
        except pymysql.Error:
            assert process.poll() is None,'Owned test process failed';time.sleep(.2)
    else:raise RuntimeError('Owned startup timed out')
    with (OUT/'compatibility-process-r2.json').open('x',encoding='utf-8') as f:json.dump({'pid':process.pid,'port':PORT,'data':DATA.as_posix(),'production':False},f)
expected=(DATA/'auto.cnf').read_text().split('server-uuid=')[1].strip()
assert expected not in ('f71f7437-d6a8-11f1-baa2-00163e1ce234','05c27d3c-dda3-11f1-aba0-84a938421519','d81ecaac-de9e-11f1-9244-84a938421519')
with pymysql.connect(host='127.0.0.1',port=PORT,user='root',password='',connect_timeout=2,autocommit=True) as connection,connection.cursor() as cur:
    cur.execute('SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION()');actual=cur.fetchone()
    assert actual[0]==expected and actual[1].replace('\\','/').lower()==DATA.as_posix().lower()+'/' and actual[2:]==(PORT,'127.0.0.1','8.0.46')
    cur.execute('SHOW DATABASES');schemas={row[0] for row in cur.fetchall()};assert 'qingfeng_music' not in schemas
    if mode=='start':
        assert schemas<={'information_schema','performance_schema','mysql','sys'}
        for schema in ('qf_execution_concurrency_test','qf_quick_creation_test'):cur.execute('CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
    if mode=='stop':cur.execute('SHUTDOWN')
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
record={'status':'PASS','state':'STOPPED' if mode=='stop' else 'RUNNING','identity':actual,'data_preserved':True,'production':False}
path=OUT/('compatibility-'+mode+'-'+stamp+'.json');path.write_text(json.dumps(record,indent=2),encoding='utf-8')
print(json.dumps({'status':'PASS','state':record['state'],'port':PORT,'evidence':str(path)}))
