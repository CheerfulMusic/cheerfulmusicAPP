"""Runs only inside the restricted verification namespace; never connects to production."""
import os,sys,subprocess,pathlib,time,hashlib,json
from restore_dump_verifier import compare, require
root=pathlib.Path('/sandbox'); data=root/'data'; (root/'tmp').mkdir(mode=0o700)
require(os.getuid()==65534, 'ISOLATION_UID')
status=pathlib.Path('/proc/self/status').read_text()
require('CapEff:\t0000000000000000' in status and 'NoNewPrivs:\t1' in status, 'ISOLATION_CAPABILITIES')
assert not pathlib.Path('/opt/qingfeng-music').exists()
assert not pathlib.Path('/etc/qingfeng-music').exists()
assert not pathlib.Path('/var/lib/mysql').exists()
assert list(pathlib.Path('/sys/class/net').glob('*'))==[] # sys is not mounted
assert pathlib.Path('/proc/net/route').read_text().count('\n')==1
args=['/usr/sbin/mysqld','--no-defaults','--basedir=/usr','--datadir=/sandbox/data','--socket=/sandbox/mysql.sock','--pid-file=/sandbox/mysql.pid','--log-error=/sandbox/mysql.log','--skip-networking','--mysqlx=OFF','--event-scheduler=OFF','--local-infile=OFF','--skip-log-bin','--innodb-buffer-pool-size=64M','--max-connections=8','--secure-file-priv=NULL','--tmpdir=/sandbox/tmp']
with open('/sandbox/init.log','wb') as out:
 subprocess.run(args+['--initialize-insecure'],stdout=out,stderr=out,check=True,timeout=120)
p=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
cli=['/usr/bin/mysql','--no-defaults','--socket=/sandbox/mysql.sock','-uroot','--binary-mode=1']
def sql(s):return subprocess.check_output(cli+['-N','-e',s],stderr=subprocess.DEVNULL,timeout=30)
try:
 for _ in range(120):
  if p.poll() is not None:raise RuntimeError('ISOLATED_MYSQL_EXIT')
  try:sql('SELECT 1');break
  except subprocess.CalledProcessError:time.sleep(1)
 else:raise RuntimeError('ISOLATED_MYSQL_TIMEOUT')
 assert sql('SELECT @@skip_networking,@@event_scheduler,@@local_infile,@@log_bin').strip()==b'1\tOFF\t0\t0'
 sql('CREATE DATABASE qingfeng_music')
 with open('/backup.sql','rb') as source:
  subprocess.run(cli+['qingfeng_music'],stdin=source,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=600)
 options=['--single-transaction','--skip-comments','--skip-dump-date','--skip-add-locks','--skip-lock-tables','--set-gtid-purged=OFF','--no-tablespaces','--hex-blob','--order-by-primary','--skip-extended-insert']
 with open('/sandbox/restored.sql','wb') as out:
  subprocess.run(['/usr/bin/mysqldump','--no-defaults','--socket=/sandbox/mysql.sock','-uroot']+options+['qingfeng_music'],stdout=out,stderr=subprocess.DEVNULL,check=True,timeout=600)
 comparison=compare(pathlib.Path('/backup.sql').read_bytes(),pathlib.Path('/sandbox/restored.sql').read_bytes())
 critical={'credit_account','credit_ledger','credit_consumption','payment_order','payment_webhook_inbox','flyway_schema_history','music_work','lyric_revision','artist_profile'}
 require(critical.issubset(comparison['tables']), 'CRITICAL_TABLES_MISSING')
 for table, facts in comparison['tables'].items():
  count=int(sql('SELECT COUNT(*) FROM qingfeng_music.`'+table+'`'))
  require(count==facts['rows'], 'DATABASE_ROW_COUNT_MISMATCH:'+table)
  columns=sql("SELECT column_name FROM information_schema.columns WHERE table_schema='qingfeng_music' AND table_name='"+table+"' ORDER BY ordinal_position").decode().splitlines()
  require(columns==facts['columns'], 'DATABASE_COLUMN_MISMATCH:'+table)
 expected_history=pathlib.Path('/history.tsv').read_bytes()
 actual_history=sql('SELECT version,script,checksum,success FROM qingfeng_music.flyway_schema_history ORDER BY installed_rank')
 require(expected_history==actual_history, 'MIGRATION_HISTORY_MISMATCH')
 result={'restore':'PASS','comparison':comparison,'historySha256':hashlib.sha256(actual_history).hexdigest(),
         'network':'isolated','uid':os.getuid(),'allTableCountsChecked':True,'allColumnOrdersChecked':True,
         'allRowFieldsExact':True,'criticalTablesChecked':sorted(critical)}

finally:
 try:sql('SHUTDOWN')
 except Exception:pass
 try:p.wait(timeout=30)
 except subprocess.TimeoutExpired:p.terminate();p.wait(timeout=30)




require(p.returncode==0, 'ISOLATED_MYSQL_SHUTDOWN_FAILED')
print(json.dumps(result),flush=True)
