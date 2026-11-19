"""Read-only live validation over two trusted SSH/cloud-run command prefixes.

Each prefix is a JSON argv array and must accept a shell program on stdin.
The deployed MySQL configuration and credentials stay on the respective hosts.
"""
import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone

DIGEST = "SHA2(CAST(JSON_ARRAY(entry_type,reference_id,owner_id,amount,created_at) AS CHAR),256)"
STAMP = "DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f')"
COLUMNS = ("Replica_IO_Running", "Replica_SQL_Running", "Seconds_Behind_Source",
           "Last_IO_Errno", "Last_SQL_Errno", "Source_Server_Id")
DEFAULT_REPLICA = "/srv/qingfeng-data/sg-read-replica"


def need(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def prefix(raw):
    result = json.loads(raw)
    if not isinstance(result, list) or not result or any(not isinstance(x, str) or not x for x in result):
        raise argparse.ArgumentTypeError("expected a nonempty JSON argv array")
    return result


def remote(command, code, timeout=75):
    shell = "python3.11 - <<'QF_REGIONAL_DB_CHECK'\n" + code + "\nQF_REGIONAL_DB_CHECK\n"
    p = subprocess.run(command, input=shell, text=True, encoding="utf-8",
                       capture_output=True, timeout=timeout)
    need(p.returncode == 0, "remote probe failed; check the command prefix and host permissions")
    try:
        return json.loads(p.stdout.strip())
    except ValueError:
        raise RuntimeError("remote probe did not return JSON") from None


def mysql_program(accounts):
    return f"""
import concurrent.futures,json,subprocess,time
accounts={accounts!r}
def sql(who, query, headings=False, timeout=25, check=True):
    args = ['mysql',*accounts[who],'--batch','--raw','--connect-timeout=4']
    if not headings: args.append('--skip-column-names')
    result = subprocess.run(args, input=query, text=True, encoding='utf-8',
                            capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('MySQL read failed')
    return result
def value(who, query):
    return sql(who,query).stdout.strip()
def status(who, query):
    lines = sql(who,query,headings=True).stdout.splitlines()
    if len(lines) != 2: raise RuntimeError('Expected exactly one replication status row')
    return dict(zip(lines[0].split('\\t'),lines[1].split('\\t')))
"""


def primary_sample(args):
    account = {"primary": ["--no-defaults", "-uroot", "--database=qingfeng_music"]}
    latest = f"SET time_zone='+00:00'; SELECT {STAMP},{DIGEST} FROM credit_ledger "              f"ORDER BY created_at DESC,entry_type DESC,reference_id DESC LIMIT {args.rows};"
    script = mysql_program(account) + f"""
identity=value('primary','SELECT @@port,@@server_id,@@read_only,@@log_bin')
if identity.split('\\t') != ['3306',str({args.source_server_id}),'0','1']:
    raise RuntimeError('Primary identity check failed')
rows=[line.split('\\t') for line in value('primary',{latest!r}).splitlines()]
coordinate=status('primary','SHOW MASTER STATUS;')
print(json.dumps(dict(rows=rows,file=coordinate['File'],position=int(coordinate['Position']))))
"""
    data = remote(args.primary, script)
    need(re.fullmatch(r"[A-Za-z0-9_-]+\.[0-9]+", data["file"]) and data["position"] > 0,
         "unexpected primary binlog coordinate")
    need(data["rows"], "no ledger rows available for comparison")
    for stamp, digest in data["rows"]:
        need(re.fullmatch(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{6}", stamp)
             and re.fullmatch(r"[a-f0-9]{64}", digest), "invalid sample digest")
    return data


def validate(args):
    sample = primary_sample(args)
    cutoff = min(stamp for stamp, _ in sample["rows"])
    expected = sorted(digest for _, digest in sample["rows"])
    fingerprint_list = ",".join("'" + digest + "'" for digest in expected)
    wait = f"SELECT SOURCE_POS_WAIT('{sample['file']}', {sample['position']}, {args.wait_seconds});"
    compare = f"SET time_zone='+00:00'; SELECT {DIGEST} FROM credit_ledger "               f"WHERE created_at >= '{cutoff}' AND {DIGEST} IN ({fingerprint_list});"
    base = args.replica_config.rstrip("/")
    accounts = {
        "admin": [f"--defaults-file={base}/admin.cnf", "--database=qingfeng_music"],
        "reader": [f"--defaults-file={base}/app-reader.cnf", "--database=qingfeng_music"],
    }
    load_query = "SELECT /*+ MAX_EXECUTION_TIME(1500) */ COUNT(*) " \
                 "FROM (SELECT id FROM artist_song_release LIMIT 50) AS recent;"
    script = mysql_program(accounts) + f"""
def health():
    state=status('admin','SHOW REPLICA STATUS;')
    return {{name:state[name] for name in {COLUMNS!r}}}
def require_healthy(state):
    if state['Replica_IO_Running']!='Yes' or state['Replica_SQL_Running']!='Yes':
        raise RuntimeError('Replication is not running')
    if state['Last_IO_Errno']!='0' or state['Last_SQL_Errno']!='0':
        raise RuntimeError('Replication reported errors')
    if state['Source_Server_Id']!=str({args.source_server_id}):
        raise RuntimeError('Unexpected replication source')
    if not state['Seconds_Behind_Source'].isdigit() or int(state['Seconds_Behind_Source'])>{args.max_lag}:
        raise RuntimeError('Replication lag exceeds threshold')
identity=value('reader','SELECT @@port,@@server_id,@@read_only,@@super_read_only')
if identity.split('\\t') != ['13307',str({args.replica_server_id}),'1','1']:
    raise RuntimeError('Refusing to probe writes without confirmed replica identity and both read-only flags')
before=health()
require_healthy(before)
barrier=value('admin',{wait!r})
if not barrier.isdigit():
    raise RuntimeError('Replica did not apply the sampled primary position before timeout')
observed=sorted(value('reader',{compare!r}).splitlines())
if observed!={expected!r}:
    raise RuntimeError('Sampled primary ledger rows differ after the replication position barrier')
mutation='UPDATE credit_ledger SET amount=amount WHERE 1=0;'
for who, allowed_errors in (('reader',{{1142,1290}}),('admin',{{1290}})):
    probe=sql(who,mutation,check=False)
    if probe.returncode==0 or not any(f'ERROR {{n}} ' in probe.stderr for n in allowed_errors):
        raise RuntimeError('Expected MySQL read-only rejection not observed')
def worker():
    with subprocess.Popen(['mysql',*accounts['reader'],'--batch','--skip-column-names',
                           '--unbuffered','--connect-timeout=4'],
                          stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True) as connection:
        samples=[]
        for _ in range({args.reads_per_worker}):
            start=time.monotonic()
            connection.stdin.write({load_query!r}+'\\n')
            connection.stdin.flush()
            result=connection.stdout.readline().strip()
            if not result.isdigit():raise RuntimeError('Concurrent read failed')
            samples.append(round((time.monotonic()-start)*1000,2))
        connection.stdin.close()
        if connection.wait(timeout=5)!=0:raise RuntimeError('Reader exited unsuccessfully')
        return samples
with concurrent.futures.ThreadPoolExecutor(max_workers={args.workers}) as pool:
    timings=sorted(t for batch in pool.map(lambda _:worker(),range({args.workers})) for t in batch)
after=health()
require_healthy(after)
p95=timings[(95*len(timings)+99)//100-1]
print(json.dumps(dict(reads=len(timings),workers={args.workers},p95_ms=p95,max_ms=timings[-1],
    sample_rows=len(observed),write_rejected=True,replication_before=before,replication_after=after)))
"""
    result = remote(args.replica, script, max(75, args.wait_seconds + 30))
    result.update(sampled_at_utc=datetime.now(timezone.utc).isoformat(), scope="live_read_only",
                  matched_primary_position={"file": sample["file"], "position": sample["position"]})
    print(json.dumps(result, indent=2))
    need(result["p95_ms"] <= args.max_p95_ms, "parallel reads exceeded the p95 threshold")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--primary", required=True, type=prefix, help="Shenzhen admin shell JSON argv")
    parser.add_argument("--replica", required=True, type=prefix, help="Singapore admin shell JSON argv")
    parser.add_argument("--replica-config", default=DEFAULT_REPLICA)
    parser.add_argument("--source-server-id", type=int, default=1)
    parser.add_argument("--replica-server-id", type=int, default=102618)
    parser.add_argument("--rows", type=int, default=20, choices=range(1, 101), metavar="1..100")
    parser.add_argument("--workers", type=int, default=6, choices=range(1, 17), metavar="1..16")
    parser.add_argument("--reads-per-worker", type=int, default=10, choices=range(1, 51), metavar="1..50")
    parser.add_argument("--wait-seconds", type=int, default=10, choices=range(1, 31), metavar="1..30")
    parser.add_argument("--max-lag", type=int, default=3)
    parser.add_argument("--max-p95-ms", type=int, default=250)
    args = parser.parse_args()
    try:
        need(args.max_lag >= 0 and args.max_p95_ms >= 1, "invalid thresholds")
        need(args.source_server_id != args.replica_server_id, "primary and replica server IDs must differ")
        validate(args)
    except (OSError, RuntimeError, subprocess.TimeoutExpired, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
