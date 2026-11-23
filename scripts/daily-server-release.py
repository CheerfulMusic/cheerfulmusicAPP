from __future__ import annotations
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from zipfile import ZipFile

APP = Path('/opt/qingfeng-music')
POLICY = Path('/etc/qingfeng-music/release-policy.json')
SERVICE = 'qingfeng-music'
DOMAIN = 'cheerfulmusic.com'
DB = 'qingfeng_music'
STAGE = Path(__file__).resolve().parent
LOCK = '/run/lock/qingfeng-release.lock'
HISTORY = ('SELECT installed_rank,version,description,type,script,checksum,success '
           'FROM qingfeng_music.flyway_schema_history ORDER BY installed_rank')
TABLES = ("SELECT table_name FROM information_schema.tables "
          "WHERE table_schema='qingfeng_music' ORDER BY table_name")

def run(*cmd, **kwargs):
    return subprocess.run(cmd, check=True, **kwargs)

def sql(query, socket=None):
    args = ['mysql','--no-defaults','-uroot','-NB']
    if socket:
        args.extend(('--protocol=SOCKET','--socket=' + str(socket)))
    return run(*args,'-e',query,capture_output=True,timeout=20).stdout

def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

def atomic_write(path, data):
    tmp = path.with_name(path.name + '.daily-pending')
    stat = path.stat()
    try:
        with tmp.open('xb') as out:
            out.write(data)
        os.chmod(tmp, stat.st_mode & 0o777)
        os.chown(tmp, stat.st_uid, stat.st_gid)
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()

def switch(release):
    temp = APP/'current.daily-pending'
    temp.symlink_to(release)
    os.replace(temp, APP/'current')

def verify_release(release):
    run('runuser','-u','qfmusic','--','python3.11',str(APP/'verify-release.py'),
        '--release',str(release),timeout=60)

def status(state, **extra):
    result = {'state':state, **extra}
    dest = STAGE/'result.json'
    tmp = dest.with_suffix('.tmp')
    tmp.write_text(json.dumps(result,sort_keys=True))
    os.replace(tmp,dest)
    print(state,flush=True)

def restore(backup):
    if Path('/proc/self/ns/net').readlink() == Path('/proc/1/ns/net').readlink():
        raise RuntimeError('Restore needs an isolated network namespace')
    mysql_user = pwd.getpwnam('mysql')
    with tempfile.TemporaryDirectory(prefix='qf-analytics-restore-',dir='/var/tmp') as scratch:
        root = Path(scratch)
        os.chown(root,mysql_user.pw_uid,mysql_user.pw_gid)
        sock = root/'mysql.sock'
        base = ['mysqld','--no-defaults','--user=mysql','--datadir='+str(root)]
        if os.listdir(root):
            raise RuntimeError('Restore directory is not empty')
        with (backup/'restore.log').open('wb') as log:
            run(*base,'--initialize-insecure',stdout=log,stderr=log,timeout=75)
            proc = subprocess.Popen(base+[
                '--socket='+str(sock),'--pid-file='+str(root/'mysql.pid'),
                '--skip-networking','--mysqlx=OFF','--skip-log-bin','--event-scheduler=OFF',
                '--innodb-buffer-pool-size=96M',
            ],stdout=log,stderr=log)
            try:
                for _ in range(45):
                    if proc.poll() is not None:
                        raise RuntimeError('Isolated restore server exited')
                    if sock.exists():
                        try:
                            identity=sql('SELECT @@datadir,@@skip_networking,@@server_uuid',sock).decode().strip().split('\t')
                        except subprocess.CalledProcessError:
                            time.sleep(1)
                            continue
                        assert Path(identity[0]).resolve() == root and identity[1] == '1'
                        assert hashlib.sha256(identity[2].encode()).hexdigest() != (backup/'database-uuid.sha256').read_text().strip()
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError('Isolated restore socket did not start')
                charset, collation = (backup/'database-defaults.tsv').read_text().strip().split('\t')
                assert re.fullmatch('[a-z0-9_]+',charset) and re.fullmatch('[a-z0-9_]+',collation)
                sql(f'CREATE DATABASE qingfeng_music CHARACTER SET {charset} COLLATE {collation}',sock)
                with (backup/'database.sql').open('rb') as dump:
                    run('mysql','--no-defaults','-uroot','--protocol=SOCKET','--socket='+str(sock),DB,
                        stdin=dump,stdout=log,stderr=log,timeout=150)
                assert sql(HISTORY,sock) == (backup/'flyway-history.tsv').read_bytes()
                assert sql(TABLES,sock) == (backup/'tables.tsv').read_bytes()
                checked = sql('SELECT COUNT(*) FROM qingfeng_music.site_event',sock).decode().strip()
                (backup/'restore-verification.json').write_text(json.dumps({
                    'isolatedSocket':True,'tables':len(sql(TABLES,sock).splitlines()),
                    'migrationHistoryMatches':True,'restoredSiteEvents':int(checked),
                }))
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    proc.wait(timeout=45)

def wait_health():
    for _ in range(45):
        p = subprocess.run(['curl','-fsS','--max-time','2','http://127.0.0.1:8080/actuator/health'],
                           capture_output=True)
        if p.returncode == 0 and json.loads(p.stdout).get('status') == 'UP':
            return
        time.sleep(2)
    raise RuntimeError('Application did not become healthy')

def smoke(target):
    edge = ['curl','-fsS','--max-time','10','--resolve',DOMAIN+':443:127.0.0.1']
    base = 'https://'+DOMAIN
    for path in ('/','/analytics','/discover','/create','/works','/help'):
        run(*edge,'-o','/dev/null',base+path,timeout=14)
    with ZipFile(target/'app.jar') as jar:
        prefix='BOOT-INF/classes/static/'
        paths=[p for p in jar.namelist() if re.fullmatch(re.escape(prefix)+r'assets/AnalyticsPage-[A-Za-z0-9_.-]+\.(js|css)',p)]
        assert any(p.endswith('.js') for p in paths)
        for path in paths:
            response=run(*edge,base+'/'+path.removeprefix(prefix),capture_output=True,timeout=14)
            assert hashlib.sha256(response.stdout).digest()==hashlib.sha256(jar.read(path)).digest()
    codes={}
    for endpoint in ('access','summary?days=7&fullLists=true','phone-users?suffix=0000','country-users?country=US'):
        p=subprocess.run([*edge[:1],'-sS','--max-time','10','--resolve',DOMAIN+':443:127.0.0.1',
                          '-w','\n%{http_code}',base+'/api/app/analytics/'+endpoint],capture_output=True,check=True)
        body,code=p.stdout.rsplit(b'\n',1)
        assert code in (b'401',b'403') or (code==b'200' and json.loads(body).get('allowed') is False)
        codes[endpoint.split('?')[0]]=int(code)
    return {'analyticsAssets':len(paths),'anonymousApiResponses':codes}

def deploy():
    plan=json.loads((STAGE/'plan.json').read_text())
    source=plan['sourceCommit']
    assert re.fullmatch('[a-f0-9]{40}',source)
    name=STAGE.name
    target=APP/'releases'/name
    backup=Path('/var/backups/qingfeng-music')/name
    lock=open(LOCK,'a')
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    current=APP/'current'
    old=current.resolve(strict=True)
    policy_bytes=POLICY.read_bytes()
    policy_changed=restart_attempted=switched=False
    try:
        status('PREFLIGHT')
        assert str(old)==plan['baselineRelease'] and not target.exists() and not backup.exists()
        assert digest(old/'app.jar')==plan['baselineJarSha256']
        assert digest(STAGE/'app.jar')==plan['jarSha256']
        assert digest(STAGE/'release-contract.json')==plan['contractSha256']
        before_contract=json.loads((old/'release-contract.json').read_text())
        contract=json.loads((STAGE/'release-contract.json').read_text())
        assert before_contract['sourceCommit']==plan['baselineCommit']
        assert contract['sourceCommit']==source and contract['migrations']==before_contract['migrations']
        assert sql("SELECT COUNT(*) FROM qingfeng_music.flyway_schema_history WHERE success=0").strip()==b'0'
        assert len(sql(HISTORY).splitlines())==len(contract['migrations'])
        verify_release(old)
        status('BACKING_UP',previousRelease=str(old))
        assert shutil.disk_usage(backup.parent).free>3*1024**3
        backup.mkdir(mode=0o700)
        (backup/'release-policy.json').write_bytes(policy_bytes)
        (backup/'flyway-history.tsv').write_bytes(sql(HISTORY))
        (backup/'tables.tsv').write_bytes(sql(TABLES))
        (backup/'database-defaults.tsv').write_bytes(sql(
            "SELECT default_character_set_name,default_collation_name FROM information_schema.schemata "
            "WHERE schema_name='qingfeng_music'"))
        (backup/'database-uuid.sha256').write_text(hashlib.sha256(sql('SELECT @@server_uuid').strip()).hexdigest())
        with (backup/'database.sql').open('wb') as out:
            run('mysqldump','--no-defaults','-uroot','--single-transaction','--quick',
                '--no-tablespaces','--hex-blob','--set-gtid-purged=OFF',
                '--triggers','--routines','--events',DB,stdout=out,timeout=180)
        with (backup/'database.sql').open('rb') as dump:
            assert dump.seek(0,os.SEEK_END)>100000
            dump.seek(-500,os.SEEK_END)
            assert b'Dump completed' in dump.read()
        old_files=[old,Path('/etc/qingfeng-music'),Path('/etc/nginx'),
                   Path('/etc/systemd/system/qingfeng-music.service'),
                   Path('/etc/systemd/system/qingfeng-music.service.d')]
        archive=backup/'release-config.tar'
        run('tar','-cf',str(archive),'-C','/',*[str(p).lstrip('/') for p in old_files],timeout=90)
        with tarfile.open(archive) as tar:
            for entry in tar:
                if entry.isfile():
                    with tar.extractfile(entry) as f:
                        assert hashlib.file_digest(f,'sha256').hexdigest()==digest(Path('/')/entry.name)
        status('VERIFYING_BACKUP',backup=str(backup))
        run('unshare','--net','--','python3.11',str(STAGE/'daily-server-release.py'),'restore',str(backup),timeout=270)
        evidence={'previousRelease':str(old),'sourceCommit':source,'migrationsUnchanged':True,
                  'databaseBackupSha256':digest(backup/'database.sql'),'archiveSha256':digest(archive),
                  'restoreVerificationSha256':digest(backup/'restore-verification.json')}
        evidence_path=backup/'schema-evidence.json'
        evidence_path.write_text(json.dumps(evidence,sort_keys=True))
        target.mkdir(mode=0o755)
        target.chmod(0o755)
        for file in ('app.jar','source-commit.txt','release-contract.json'):
            shutil.copyfile(STAGE/file,target/file)
            (target/file).chmod(0o644)
        approved=json.loads(policy_bytes)
        approved['approved'][plan['jarSha256']]={'sourceCommit':source,
            'contractSha256':plan['contractSha256'],'schemaEvidenceSha256':digest(evidence_path)}
        assert current.resolve()==old and POLICY.read_bytes()==policy_bytes
        assert sql(HISTORY)==(backup/'flyway-history.tsv').read_bytes()
        atomic_write(POLICY,json.dumps(approved).encode())
        policy_changed=True
        verify_release(old)
        verify_release(target)
        status('SWITCHING',backup=str(backup))
        assert current.resolve()==old
        switch(target)
        switched=True
        restart_attempted=True
        run('systemctl','restart',SERVICE,timeout=110)
        wait_health()
        run('systemctl','is-active',SERVICE,timeout=5)
        assert current.resolve()==target
        smoke_result=smoke(target)
        hook = Path('/usr/local/sbin/qingfeng-release-smoke')
        if hook.exists():
            run(str(hook), source, timeout=120)
        assert sql(HISTORY)==(backup/'flyway-history.tsv').read_bytes()
        receipt={'sourceCommit':source,'release':str(target),'backup':str(backup),
                 'jarSha256':plan['jarSha256'],'health':'UP',**smoke_result}
        (backup/'deployment-receipt.json').write_text(json.dumps(receipt,sort_keys=True))
        status('COMPLETE',**receipt)
    except Exception as error:
        phase='PRECHECK_FAILED'
        try:
            if switched:
                switch(old)
            if policy_changed:
                atomic_write(POLICY,policy_bytes)
            if restart_attempted:
                verify_release(old)
                run('systemctl','restart',SERVICE,timeout=110)
                wait_health()
                phase='ROLLED_BACK'
        except Exception as rollback:
            status('ROLLBACK_FAILED',error=type(error).__name__,rollback=type(rollback).__name__)
            raise
        status(phase,error=type(error).__name__,details=str(error)[:320])
        raise
    finally:
        lock.close()

if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='restore':
        restore(Path(sys.argv[2]))
    else:
        deploy()
