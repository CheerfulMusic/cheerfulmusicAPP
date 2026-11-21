"""Exercise the Singapore gateway snippets with disposable loopback upstreams."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import http.client
import json
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.parse import urlsplit


GIFT = "/gift/" + "A" * 43
DISCOVER = "/api/public/discover"
ASSET = b"export const sample = 'public content';\n" * 2000


class Backend(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def dispatch(self):
        path = urlsplit(self.path).path
        self.server.requests.append((self.command, path, self.headers.get("Cookie")))
        marker = None
        if self.server.role == "primary":
            if path == "/":
                time.sleep(self.server.home_delay)
            if path == "/api/public/gifts/" + "A" * 43:
                status = self.server.authority
                if status == 200 and self.server.active_marker:
                    marker = "active"
            else:
                status = self.server.primary_status
        elif path == "/internal/regional/ready":
            status = 204 if self.server.local_status == 200 else 503
        elif path == GIFT:
            status = self.server.local_status if self.headers.get("X-QF-Primary-Share-Authorized") == "active" else 404
        else:
            status = self.server.local_status
        self.send_response(status)
        if marker:
            self.send_header("X-QF-Share-Status", marker)
        if path == "/assets/public-test.js" and status == 200:
            self.send_header("Content-Type", "application/javascript")
            self.send_header("Content-Length", str(len(ASSET)))
        self.end_headers()
        if path == "/assets/public-test.js" and self.command == "GET" and status == 200:
            self.wfile.write(ASSET)

    do_GET = do_HEAD = do_POST = dispatch


@contextmanager
def upstream(role):
    with ThreadingHTTPServer(("127.0.0.1", 0), Backend) as server:
        server.role = role
        server.requests = []
        server.authority = server.local_status = server.primary_status = 200
        server.active_marker = True
        server.home_delay = 0
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server
        finally:
            server.shutdown()
            worker.join()


def request(port, path, method="GET", forged=False):
    client = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
    try:
        headers = {"Cookie": "session=fixture"}
        if forged:
            headers["X-QF-Primary-Share-Authorized"] = "active"
        client.request(method, path, headers=headers)
        response = client.getresponse()
        response.read()
        return response.status, response.getheader("X-QF-Origin")
    finally:
        client.close()


@contextmanager
def gateway(executable, temp, snippets, reader, primary):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    def load(name):
        return ((snippets / name).read_text(encoding="utf-8")
                .replace("127.0.0.1:18081", f"127.0.0.1:{reader.server_port}")
                .replace("https://120.77.19.199", f"http://127.0.0.1:{primary.server_port}")
                .replace("/var/log/nginx/", str(temp) + "/"))

    files = [load(name) for name in ("sg-fallback-http.conf", "sg-gift-http.conf",
                                      "sg-public-read-server.conf", "sg-gift-server.conf",
                                      "sg-static-read-server.conf", "sg-primary-callback-server.conf")]
    budget = temp / "sg-fallback-budget.conf"
    budget.write_text(load("sg-fallback-budget.conf"), encoding="utf-8")
    files[4] = files[4].replace("/etc/nginx/qingfeng/sg-fallback-budget.conf", str(budget))
    config = temp / "nginx.conf"
    config.write_text(f"""daemon off;
error_log {temp / 'error.log'} crit;
pid {temp / 'nginx.pid'};
events {{}}
http {{
    access_log off;
    {files[0]}
    {files[1]}
    server {{
        listen 127.0.0.1:{port};
        server_name cheerfulmusic.com;
        {files[2]}
        {files[3]}
        {files[4]}
        {files[5]}
        location / {{ proxy_pass http://127.0.0.1:{primary.server_port}; proxy_next_upstream off; }}
    }}
}}
""", encoding="utf-8")
    command = [executable, "-p", str(temp), "-c", str(config)]
    subprocess.run([*command, "-t"], check=True, timeout=5)
    process = subprocess.Popen(command)
    try:
        for _ in range(30):
            try:
                request(port, "/internal/regional/ready")
                break
            except ConnectionRefusedError:
                if process.poll() is not None:
                    raise RuntimeError("isolated Nginx exited") from None
                time.sleep(0.1)
        yield port
    finally:
        process.terminate()
        process.wait(timeout=5)


def verify_static_compression(port):
    client = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
    try:
        client.request("GET", "/assets/public-test.js", headers={"Accept-Encoding": "gzip"})
        response = client.getresponse()
        assert response.status == 200 and response.getheader("Content-Encoding") == "gzip"
        assert "Accept-Encoding" in response.getheader("Vary")
        assert gzip.decompress(response.read()) == ASSET
    finally:
        client.close()


def run(executable, snippets):
    with tempfile.TemporaryDirectory(prefix="qf-regional-gateway-") as directory:
        temp = Path(directory)
        with upstream("reader") as reader, upstream("primary") as primary, gateway(
                executable, temp, snippets, reader, primary) as port:
            verify_static_compression(port)
            assert request(port, DISCOVER + "?featured=true") == (200, None)
            assert primary.requests[-1] == ("GET", DISCOVER, "session=fixture")
            assert request(port, "/") == (200, "singapore-read")
            assert request(port, "/__qf_region_ready")[0] == 204
            assert ("GET", "/", None) in reader.requests
            reader.local_status = 503
            assert request(port, "/__qf_region_ready")[0] == 503
            assert request(port, "/") == (200, "shenzhen-fallback")
            assert primary.requests[-1] == ("GET", "/", None)
            assert request(port, "/api/app/credits") == (200, None)
            assert primary.requests[-1] == ("GET", "/api/app/credits", "session=fixture")
            assert request(port, DISCOVER + "/songs/fixture/1/play", "POST") == (200, None)
            assert primary.requests[-1] == ("POST", DISCOVER + "/songs/fixture/1/play", "session=fixture")
            assert request(port, "/api/public/auth/google/callback?code=fixture") == (200, None)
            assert primary.requests[-1] == ("GET", "/api/public/auth/google/callback", "session=fixture")
            assert request(port, "/internal/regional/ready")[0] == 404
            before = len(primary.requests)
            assert request(port, "/__qf_primary_gift_authority")[0] in (403, 404)
            assert len(primary.requests) == before
            reader.local_status = 200
            assert request(port, GIFT + "?utm_source=fixture") == (200, "singapore-read")
            assert primary.requests[-1] == ("HEAD", "/api/public/gifts/" + "A" * 43, None)
            assert reader.requests[-1] == ("GET", GIFT, None)
            assert request(port, GIFT, "HEAD") == (200, "singapore-read")
            before = len(reader.requests)
            primary.authority = 404
            assert request(port, GIFT, forged=True)[0] == 404
            assert len(reader.requests) == before
            primary.authority = 503
            assert request(port, GIFT) == (200, "shenzhen-fallback")
            assert len(reader.requests) == before
            primary.authority = 200
            primary.active_marker = False
            assert request(port, GIFT, forged=True) == (200, "shenzhen-fallback")
            primary.active_marker = True
            reader.local_status = 404
            assert request(port, GIFT) == (200, "shenzhen-fallback")
            reader.local_status = 503
            primary.home_delay = 1.3
            before = len(primary.requests)
            with ThreadPoolExecutor(max_workers=12) as pool:
                codes = sorted(pool.map(lambda _: request(port, "/")[0], range(12)))
            assert codes == [503] * 8 + [504] * 4, codes
            assert len(primary.requests) - before == 4
        for path in temp.glob("qingfeng-sg-*.log"):
            for line in path.read_text(encoding="utf-8").splitlines():
                json.loads(line)
                assert GIFT not in line and "session=fixture" not in line
    print("regional gateway: local read, primary writes, freshness fallback, gift authority and private logs passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", default=shutil.which("nginx"))
    parser.add_argument("--snippets", type=Path,
                        default=Path(__file__).resolve().parent.parent / "Qingfeng_AI_Music/deploy/nginx")
    arguments = parser.parse_args()
    if not arguments.nginx:
        parser.error("run on a Linux host with Nginx and auth_request support")
    run(arguments.nginx, arguments.snippets)
