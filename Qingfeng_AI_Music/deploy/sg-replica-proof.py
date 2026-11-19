"""Publish a short-lived local proof for Singapore public reads."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time


QUERY = """SHOW REPLICA STATUS;
SELECT @@server_id, @@read_only, @@super_read_only, SERVICE_STATE,
       TIMESTAMPDIFF(SECOND, LAST_HEARTBEAT_TIMESTAMP, NOW()),
       TIMESTAMPDIFF(SECOND, LAST_QUEUED_TRANSACTION_END_QUEUE_TIMESTAMP, NOW())
FROM performance_schema.replication_connection_status WHERE CHANNEL_NAME = '';"""


def healthy(args):
    result = subprocess.run(
        ["mysql", f"--defaults-file={args.mysql_defaults}", "--connect-timeout=1",
         "--batch", "--raw", "--execute", QUERY],
        text=True, capture_output=True, check=True, timeout=3,
    )
    headings, status, _, sample = [row.split("\t") for row in result.stdout.splitlines()]
    status = dict(zip(headings, status))
    identity, readonly, super_readonly, connection, heartbeat_age, queue_age = sample
    ages = [int(age) for age in (heartbeat_age, queue_age) if age != "NULL"]
    return (identity == str(args.replica_id) and readonly == super_readonly == "1"
            and status["Source_Server_Id"] == str(args.source_id)
            and status["Replica_IO_Running"] == status["Replica_SQL_Running"] == "Yes"
            and status["Last_IO_Errno"] == status["Last_SQL_Errno"] == "0"
            and status["Seconds_Behind_Source"].isdecimal()
            and int(status["Seconds_Behind_Source"]) <= args.max_lag
            and connection == "ON"
            and any(0 <= age <= args.max_source_silence for age in ages))


def publish(path):
    fd, name = tempfile.mkstemp(prefix=".proof-", dir=path.parent)
    try:
        try:
            os.fchmod(fd, 0o644)
        finally:
            os.close(fd)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-defaults", required=True, type=Path)
    parser.add_argument("--proof", type=Path)
    parser.add_argument("--replica-id", type=int, default=102618)
    parser.add_argument("--source-id", type=int, default=1)
    parser.add_argument("--max-lag", type=int, default=3)
    parser.add_argument("--max-source-silence", type=int, default=35)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    if not args.check_only and not args.proof:
        parser.error("set --proof or --check-only")
    if args.source_id == args.replica_id or min(args.max_lag, args.max_source_silence) < 0:
        parser.error("invalid replica identity or freshness thresholds")

    previous = None
    while True:
        try:
            ready = healthy(args)
        except (ValueError, KeyError, OSError, subprocess.SubprocessError):
            ready = False
        if not args.check_only:
            if ready:
                publish(args.proof)
            else:
                args.proof.unlink(missing_ok=True)
        if ready != previous:
            print("Singapore replica " + ("ready" if ready else "unavailable or stale"), flush=True)
        if args.check_only:
            return 0 if ready else 1
        previous = ready
        time.sleep(2)


if __name__ == "__main__":
    raise SystemExit(main())
