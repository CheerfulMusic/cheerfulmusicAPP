"""Manage only a fresh loopback instance for existing main MySQL tests. Preserve data."""
import datetime, json, pathlib, socket, subprocess, sys, time
import pymysql
SOURCE_ROOT=pathlib.Path(__file__).resolve().parents[2]
ROOT=pathlib.Path(subprocess.check_output(['git','-C',str(SOURCE_ROOT),'rev-parse','--path-format=absolute','--git-common-dir']).decode('utf-8').strip()).parent
BASE=ROOT/'.cache/payment-mysql-8046-20261111'
OUT=ROOT/'.cache/payment-main-integration-20261112'
DATA=pathlib.Path('R:/integration-compatibility-r1')
PORT=57326
mode=sys.argv[1] if len(sys.argv)>1 else 'status'
assert mode in ('start','status','stop')
assert pathlib.Path('R:/download.json').read_bytes()==(BASE/'download.json').read_bytes()
ini=pathlib.Path('R:/integration-compatibility-r1.ini')
binary=pathlib.Path('R:/mysql-8.0.46-winx64/bin/mysqld.exe')
if mode=='start':
    with socket.socket() as probe:probe.bind(('127.0.0.1',PORT))
    assert not DATA.exists() and not ini.exists(),'Existing test data is never reinitialized'
    DATA.mkdir()
    ini.write_text('[mysqld]\nbasedir=R:/mysql-8.0.46-winx64\ndatadir='+DATA.as_posix()+
        '\nlc-messages-dir=R:/mysql-8.0.46-winx64/share\nbind-address=127.0.0.1\nport='+str(PORT)+
        '\nmysqlx=0\nevent-scheduler=OFF\nlocal-infile=0\nskip-log-bin\ngeneral-log=OFF\ninnodb-buffer-pool-size=128M\nlog-error=R:/integration-compatibility-r1-error.log\n',encoding='ascii')
    result=subprocess.run([str(binary),'--defaults-file='+ini.as_posix(),'--initialize-insecure'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    assert result.returncode==0,'New isolated data initialization failed'
    process=subprocess.Popen([str(binary),'--defaults-file='+ini.as_posix()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
    for _ in range(100):
        try:
            conn=pymysql.connect(host='127.0.0.1',port=PORT,user='root',password='',connect_timeout=1);conn.close();break
        except pymysql.Error:
            assert process.poll() is None,'Local process failed';time.sleep(.2)
    else:raise RuntimeError('Local startup timeout')
    (OUT/'compatibility-process-r1.json').write_text(json.dumps({'pid':process.pid,'port':PORT,'data':DATA.as_posix(),'purpose':'ISOLATED_AUTOMATION_ONLY'}),encoding='utf-8')
conn=pymysql.connect(host='127.0.0.1',port=PORT,user='root',password='',connect_timeout=2,autocommit=True)
with conn.cursor() as cur:
    cur.execute('SELECT @@server_uuid,@@datadir,@@port,@@bind_address,VERSION()');identity=cur.fetchone()
    assert identity[0] not in ('05c27d3c-dda3-11f1-aba0-84a938421519','f71f7437-d6a8-11f1-baa2-00163e1ce234')
    assert identity[1].replace('\\','/').lower()==DATA.as_posix().lower()+'/' and identity[2:]==(PORT,'127.0.0.1','8.0.46')
    cur.execute('SHOW DATABASES');schemas=[r[0] for r in cur.fetchall()];assert 'qingfeng_music' not in schemas
    if mode=='start':
        assert set(schemas)<=set(('information_schema','performance_schema','mysql','sys'))
        for database in ('qf_execution_concurrency_test','qf_quick_creation_test'):
            cur.execute('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
    if mode=='stop':cur.execute('SHUTDOWN')
conn.close()
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
record={'status':'PASS','state':'STOPPED' if mode=='stop' else 'RUNNING','identity':list(identity),'daily_database_untouched':True,'data_preserved':True}
path=OUT/('compatibility-'+mode+'-'+stamp+'.json');path.write_text(json.dumps(record,indent=2),encoding='utf-8')
print(json.dumps({'evidence':str(path),'state':record['state'],'port':PORT}))
