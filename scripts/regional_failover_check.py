"""Exercise a disposable loopback Nginx with simulated upstreams. Never stops live services."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time


class Fixture(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, label):
        super().__init__(("127.0.0.1", 0), Handler)
        self.label = label
        self.mode = "healthy"
        self.lock = threading.Lock()
        self.active = self.peak = self.requests = 0

    def reset(self, mode):
        with self.lock:
            if self.active:
                raise RuntimeError("prior fixture requests have not drained")
            self.mode = mode
            self.peak = self.requests = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        fixture = self.server
        with fixture.lock:
            fixture.active += 1
            fixture.requests += 1
            fixture.peak = max(fixture.peak, fixture.active)
            sequence = fixture.requests
        try:
            mode = fixture.mode
            if mode == "jitter":
                time.sleep((0.12, 0.25, 0.4)[sequence % 3])
            if mode == "stalled":
                time.sleep(1.6)
            if mode == "forbidden":
                code = 403
            else:
                code = 503 if mode == "unavailable" or (
                    mode == "database_down" and self.path == "/shadow/db-read"
                ) else 200
            self.send_response(code)
            self.send_header("X-Fixture-Upstream", fixture.label)
            self.send_header("Content-Length", "0")
            self.end_headers()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with fixture.lock:
                fixture.active -= 1


@contextmanager
def serving(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def config(gateway, reader, primary, policy):
    http_policy, fallback_policy = policy if policy else (
        "", "proxy_connect_timeout 2s; proxy_read_timeout 180s; proxy_next_upstream off;"
    )
    errors = "404 500 502 503 504" if policy else "403 404 500 502 503 504"
    return f"""
pid nginx.pid;
error_log stderr crit;
events {{ worker_connections 256; }}
http {{
    access_log off;
    {http_policy}
    server {{
        listen 127.0.0.1:{gateway};
        server_name fixture;
        location = /assets/probe {{
            proxy_pass http://127.0.0.1:{reader};
            proxy_connect_timeout 2s;
            proxy_read_timeout 8s;
            proxy_intercept_errors on;
            error_page {errors} = @primary;
        }}
        # Shadow route tests the prospective DB-read policy. Production sends this class to Shenzhen.
        location = /shadow/db-read {{
            proxy_pass http://127.0.0.1:{reader};
            proxy_connect_timeout 2s;
            proxy_read_timeout 8s;
            proxy_intercept_errors on;
            error_page {errors} = @primary;
        }}
        location = /api/public/discover {{ proxy_pass http://127.0.0.1:{primary}; }}
        location @primary {{
            {fallback_policy}
            proxy_pass http://127.0.0.1:{primary};
        }}
    }}
}}
"""


@contextmanager
def nginx(executable, gateway, reader, primary, policy):
    with tempfile.TemporaryDirectory(prefix="qf-nginx-fixture-") as workspace:
        path = Path(workspace)
        (path / "nginx.conf").write_text(config(gateway, reader, primary, policy), encoding="ascii")
        command = [executable, "-p", workspace + "/", "-c", "nginx.conf"]
        subprocess.run([*command, "-t"], capture_output=True, check=True, timeout=5)
        process = subprocess.Popen([*command, "-g", "daemon off; master_process off;"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(40):
                if process.poll() is not None:
                    raise RuntimeError("disposable Nginx exited during startup")
                try:
                    with socket.create_connection(("127.0.0.1", gateway), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                raise RuntimeError("disposable Nginx did not listen")
            yield
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def request(port, path):
    start = time.monotonic()
    connection = HTTPConnection("127.0.0.1", port, timeout=5)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        response.read()
        return (response.status, response.getheader("X-Fixture-Upstream"), time.monotonic() - start,
                response.getheader("Retry-After"))
    finally:
        connection.close()


def drain(server):
    deadline = time.monotonic() + 4
    while server.active and time.monotonic() < deadline:
        time.sleep(0.02)
    if server.active:
        raise RuntimeError("fixture requests did not drain")


def run(executable, policy):
    bounded = bool(policy)
    reader, primary = Fixture("singapore"), Fixture("shenzhen")
    gateway = free_port()
    reports = []

    def scenario(case, path, origin, count=12, overloaded=False, status=200):
        with ThreadPoolExecutor(max_workers=count) as pool:
            rows = list(pool.map(lambda _: request(gateway, path), range(count)))
        drain(primary)
        drain(reader)
        peak, calls = primary.peak, primary.requests
        counts = {str(code): sum(row[0] == code for row in rows) for code in {row[0] for row in rows}}
        max_ms = round(max(row[2] for row in rows) * 1000)
        if overloaded and bounded:
            if (peak > 4 or calls > 4 or not counts.get("503")
                    or set(counts) - {"503", "504"} or max_ms > 2500 or any(row[3] != "1" for row in rows)):
                raise RuntimeError(f"bounded policy failed: peak={peak} calls={calls} status={counts} max_ms={max_ms}")
        elif any(code != status or actual != origin for code, actual, _, _ in rows):
            raise RuntimeError(f"unexpected route or response during {case}")
        if calls > count:
            raise RuntimeError("gateway amplified requests to Shenzhen")
        reports.append(dict(case=case, statuses=counts, max_ms=max_ms,
                            primary_calls=calls, primary_peak_in_flight=peak))

    with serving(primary):
        with nginx(executable, gateway, reader.server_port, primary.server_port, policy):
            with serving(reader):
                scenario("healthy_local", "/assets/probe", "singapore", count=4)
                if bounded:
                    reader.reset("forbidden")
                    scenario("forbidden_does_not_fallback", "/assets/probe", "singapore", count=3, status=403)
                    if primary.requests:
                        raise RuntimeError("a denied regional request reached Shenzhen")
                reader.reset("database_down")
                scenario("db_down_static_unaffected", "/assets/probe", "singapore", count=4)
                scenario("shadow_db_down_fallback", "/shadow/db-read", "shenzhen", count=3)
                primary.reset("healthy")
                scenario("current_dynamic_route_remains_primary", "/api/public/discover", "shenzhen", count=3)
                primary.reset("jitter")
                reader.reset("unavailable")
                scenario("cross_border_jitter", "/assets/probe", "shenzhen", count=4)
                primary.reset("stalled")
                scenario("compound_failure_burst", "/assets/probe", "shenzhen", overloaded=True)
                primary.reset("healthy")
                reader.reset("healthy")
                scenario("recovery", "/assets/probe", "singapore", count=4)
            primary.reset("healthy")
            scenario("app_port_unavailable", "/assets/probe", "shenzhen", count=4)
    return dict(policy="proposed_bounded" if bounded else "current_shape",
                scope="isolated_loopback_nginx_and_synthetic_upstreams",
                cases=reports, observed_guard=bounded)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", default=shutil.which("nginx"), help="Linux Nginx executable")
    parser.add_argument("--policy-dir", type=Path,
                        default=Path(__file__).resolve().parent.parent / "Qingfeng_AI_Music/deploy/nginx")
    args = parser.parse_args()
    if not args.nginx:
        parser.error("Nginx required; run on a Linux test host or in an isolated Linux container")
    policy = tuple((args.policy_dir / name).read_text(encoding="utf-8") for name in
                   ("sg-fallback-http.conf", "sg-fallback-budget.conf"))
    print(json.dumps([run(args.nginx, None), run(args.nginx, policy)], indent=2))


if __name__ == "__main__":
    main()
