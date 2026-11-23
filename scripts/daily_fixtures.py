"""Provide private, disposable MySQL 8.0.46 instances for the project release gate."""
import configparser
from contextlib import ExitStack, contextmanager
import json
from pathlib import Path
import secrets
import subprocess
import time

import pymysql


@contextmanager
def cohort(cache, mysql):
    revision = "r" + str(time.time_ns())[-13:]
    base = cache / "release-gate-cohorts"
    instances = {}
    with ExitStack() as stack:
        for role, port in (("financial", 23316), ("compatibility", 57346)):
            data = base / "instances" / revision / role
            data.mkdir(parents=True)
            command = [str(mysql), "--no-defaults", "--console", "--datadir=" + str(data)]
            subprocess.run(command + ["--initialize-insecure"], check=True, timeout=180)
            config = configparser.ConfigParser()
            config.read(data / "auto.cnf")
            uuid = config["auto"]["server-uuid"]
            instances[role] = {"uuid": uuid, "datadir": str(data)}
            flags = ["--no-monitor", "--bind-address=127.0.0.1", f"--port={port}", "--mysqlx=OFF",
                     "--skip-log-bin", "--innodb-buffer-pool-size=96M"]
            log = stack.enter_context((data.parent / (role + ".log")).open("wb"))
            proc = subprocess.Popen(command + flags, stdout=log, stderr=log)
            def stop(process=proc):
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=30)
            stack.callback(stop)
            for _ in range(60):
                if proc.poll() is not None:
                    raise RuntimeError(f"Fixture exited {proc.returncode}: " + Path(log.name).read_text(errors="replace")[-2000:])
                try:
                    conn = pymysql.connect(host="127.0.0.1", port=port, user="root", connect_timeout=2,
                                           autocommit=True)
                except pymysql.OperationalError:
                    time.sleep(1)
                    continue
                stack.callback(conn.close)
                with conn.cursor() as cursor:
                    cursor.execute("SELECT @@server_uuid, @@datadir, VERSION()")
                    seen, directory, version = cursor.fetchone()
                    if (seen, Path(directory), version) != (uuid, data, "8.0.46"):
                        raise RuntimeError("Test MySQL identity mismatch")
                def shutdown(connection=conn, process=proc):
                    with connection.cursor() as shutdown_cursor:
                        shutdown_cursor.execute("SHUTDOWN")
                    process.wait(timeout=60)
                stack.callback(shutdown)
                break
            else:
                raise RuntimeError("Test MySQL startup timed out")
            if role == "financial":
                financial = conn
            else:
                with conn.cursor() as cursor:
                    for db in ("qf_execution_concurrency_test", "qf_quick_creation_test"):
                        cursor.execute(f"CREATE DATABASE `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
        with financial.cursor() as cursor:
            for label, prefix in (("migration","integration_migration"), ("credits","integrated_credits"),
                                  ("application","integration_application"), ("legacy40","integration_legacy40"),
                                  ("core40","core40_application"), ("overlap","dispute_overlap"),
                                  ("history","historical_cutover"), ("history","historical_cutover")):
                folder = "payment-go-live-execution-20261113" if label in ("history","overlap") else "payment-main-integration-20261112"
                folder = cache / folder
                folder.mkdir(exist_ok=True)
                rev = revision
                if label == "history" and (folder / f"history-db-private-{rev}.json").exists():
                    rev += "1"
                db = f"p0_{prefix}_202611{13 if label in ('history','overlap') else 12}{rev}"
                user = f"qf_{label}_{rev}"
                password = secrets.token_hex(24)
                cursor.execute(f"CREATE DATABASE `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
                cursor.execute("CREATE USER %s@'127.0.0.1' IDENTIFIED BY %s", (user,password))
                cursor.execute(f"GRANT ALL PRIVILEGES ON `{db}`.* TO %s@'127.0.0.1'", (user,))
                cfg = dict(host="127.0.0.1",port=23316,database=db,user=user,password=password,
                           uuid=instances["financial"]["uuid"])
                if label == "application":
                    monitor, secret = "monitor_"+rev, secrets.token_hex(24)
                    cursor.execute("CREATE USER %s@'127.0.0.1' IDENTIFIED BY %s",(monitor,secret))
                    cursor.execute("GRANT PROCESS ON *.* TO %s@'127.0.0.1'",(monitor,))
                    cursor.execute(f"GRANT SELECT ON `{db}`.* TO %s@'127.0.0.1'",(monitor,))
                    cfg["monitor"] = dict(user=monitor,password=secret)
                (folder / f"{label}-db-private-{rev}.json").write_text(json.dumps(cfg))
        manifest = base / (revision + ".json")
        manifest.write_text(json.dumps(dict(version=2,revision=revision,carryForwardRevision=revision+"1",
                                            compatibilityPort=57346,instances=instances)))
        yield manifest
