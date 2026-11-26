"""Exercise the SEO release checks with local HTTP fixtures and simulated platform receipts."""

import contextlib
from email.message import Message
import hashlib
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import ssl
from pathlib import Path
import tempfile
from threading import Thread
import unittest
from unittest.mock import patch
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("seo_release", Path(__file__).with_name("seo-release.py"))
seo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seo)

ICON = b"isolated PNG payload"
KEY = "test-only-key"
MANIFEST = {
    "version": 1,
    "origin": seo.SITE,
    "icon": {"path": "/brand/favicon-symbol-96.png", "sha256": hashlib.sha256(ICON).hexdigest()},
    "pages": [
        {"path": "/", "language": "zh-CN", "title": "青妙Ai", "description": "中文描述", "brand": "青妙Ai",
         "aliases": ["cheerfulmusic", "cheerfulmusicAi", "cheerfulmusicAI"],
         "intro": "青妙Ai（cheerfulmusicai）是 AI 音乐创作平台。"},
        {"path": "/en/", "language": "en", "title": "cheerfulmusic Ai | Everyone Can Be a Creator",
         "description": "Create your own song in just a few clicks. No music skills needed.",
         "brand": "cheerfulmusic Ai", "lastModified": "2026-11-25",
         "intro": "Create a song with cheerfulmusic Ai (cheerfulmusicai)."},
    ],
}


def fixture_files():
    files = {}
    sitemap = []
    for page in MANIFEST["pages"]:
        path = page["path"]
        title = escape(page["title"])
        description = escape(page["description"])
        brand = escape(page["brand"])
        intro = escape(page["intro"])
        schema = ""
        if "aliases" in page:
            schema = '<script type="application/ld+json">' + json.dumps({
                "@type": "WebSite", "name": page["brand"], "alternateName": page["aliases"],
            }) + "</script>"
        files[path] = ("text/html", f"""<html lang="{page['language']}"><head>
            <title>{title}</title>
            <meta name="description" content="{description}">
            <meta property="og:title" content="{title}">
            <meta property="og:description" content="{description}">
            <meta property="og:site_name" content="{brand}">
            <link rel="canonical" href="{seo.SITE + path}">
            <link rel="icon" href="{MANIFEST['icon']['path']}">
            {schema}</head><body><main><p class="beta-access-intro">{intro}</p></main></body></html>""".encode())
        modified = f"<lastmod>{page['lastModified']}</lastmod>" if "lastModified" in page else ""
        sitemap.append(f"<url><loc>{seo.SITE + path}</loc>{modified}</url>")
    sitemap_xml = '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' + "".join(sitemap) + "</urlset>"
    files["/sitemap.xml"] = ("application/xml", sitemap_xml.encode())
    files["/robots.txt"] = ("text/plain", f"Sitemap: {seo.SITE}/sitemap.xml\n".encode())
    files[MANIFEST["icon"]["path"]] = ("image/png", ICON)
    files[f"/{KEY}.txt"] = ("text/plain", KEY.encode())
    return files


@contextlib.contextmanager
def running_site(files):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in files:
                self.send_error(404)
                return
            content_type, body = files[self.path]
            self.send_response(204 if self.path == "/internal/regional/ready" else 200)
            self.send_header("Content-Type", content_type)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        worker = Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        worker.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}"
        finally:
            server.shutdown()
            worker.join()


class SeoReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = self.enterContext(tempfile.TemporaryDirectory())
        self.files = fixture_files()
        icon = MANIFEST["icon"]["path"]
        self.regional_files = {path: self.files[path] for path in ("/", icon)}
        self.regional_files["/internal/regional/ready"] = ("text/plain", b"")
        self.sz = self.enterContext(running_site(self.files))
        self.sg = self.enterContext(running_site(self.regional_files))
        self.local_public = self.enterContext(running_site(dict(self.files)))
        self.manifest = Path(self.temp) / "seo-release.json"
        self.manifest.write_text(json.dumps(MANIFEST))
        self.receipt = Path(self.temp) / "receipt.json"

        get = seo.get
        self.enterContext(patch.object(seo, "get", side_effect=lambda base, path, **kwargs:
                                      get(self.local_public if base == seo.SITE else base, path, **kwargs)))
        self.post = self.enterContext(patch.object(seo, "post"))
        self.enterContext(patch.dict(seo.os.environ, {
            "QF_SEO_INDEXNOW_KEY": KEY,
            "QF_SEO_BAIDU_TOKEN": "fixture-token",
        }))

    def run_release(self, *args):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = seo.main(["--manifest", str(self.manifest), "--singapore-manifest", str(self.manifest),
                               "--shenzhen-app", self.sz, "--singapore-app", self.sg, *args])
        return status, json.loads(output.getvalue())

    def test_read_only_checks_both_origins_and_public(self):
        status, report = self.run_release()
        self.assertEqual(status, 0)
        self.assertEqual(report["verification"], dict.fromkeys(("shenzhen", "singapore", "public"), "passed"))
        self.assertEqual(len(report["urls"]), 2)
        self.post.assert_not_called()

    def test_public_checks_cannot_mask_stale_singapore_html(self):
        media, body = self.regional_files["/"]
        self.regional_files["/"] = (media, body.replace("青妙Ai".encode(), b"Previous brand"))
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertIn("title differs", report["verification"]["singapore"]["reason"])
        self.assertEqual(report["verification"]["public"], "passed")
        self.post.assert_not_called()

    def test_stale_english_intro_prevents_notifications(self):
        media, body = self.files["/en/"]
        self.files["/en/"] = (media, body.replace(b"cheerfulmusicai", b"the song studio"))
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertIn("visible product introduction differs", report["verification"]["shenzhen"]["reason"])
        self.assertEqual(report["verification"]["public"], "passed")
        self.post.assert_not_called()

    def test_stale_sitemap_and_icon_fail_the_check(self):
        media, sitemap = self.files["/sitemap.xml"]
        self.files["/sitemap.xml"] = (media, sitemap.replace(b"2026-11-25", b"2026-11-24"))
        status, report = self.run_release()
        self.assertEqual(status, 1)
        self.assertIn("Sitemap", report["verification"]["shenzhen"]["reason"])
        self.regional_files[MANIFEST["icon"]["path"]] = ("image/png", b"previous version")
        status, report = self.run_release()
        self.assertEqual(status, 1)
        self.assertIn("icon content", report["verification"]["singapore"]["reason"])

    def test_singapore_readiness_cannot_be_replaced_by_primary(self):
        status, report = self.run_release("--singapore-app", self.local_public)
        self.assertEqual(status, 1)
        self.assertIn("regional/ready", report["verification"]["singapore"]["reason"])
        self.post.assert_not_called()

    def test_robots_block_prevents_notifications(self):
        _, robots = self.files["/robots.txt"]
        self.files["/robots.txt"] = ("text/plain", b"User-agent: *\nDisallow: /\n" + robots)
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertIn("robots.txt blocks", report["verification"]["shenzhen"]["reason"])
        self.post.assert_not_called()

    def test_noindex_is_a_release_failure(self):
        page = MANIFEST["pages"][0]
        _, body = self.files["/"]
        headers = Message()
        headers["X-Robots-Tag"] = "googlebot: noindex"
        with self.assertRaisesRegex(ValueError, "noindex"):
            seo.verify_page(page, MANIFEST, body, headers)

    def test_accepted_receipts_make_resuming_idempotent(self):
        self.post.side_effect = [(202, b""), (200, b'{"success":2,"remain":8}')]
        args = ("--notify", "--receipt", str(self.receipt))
        status, report = self.run_release(*args)
        self.assertEqual(status, 0)
        self.assertEqual(report["notifications"]["indexnow"]["state"], "pending_key_validation")
        self.assertEqual(report["notifications"]["baidu"]["remaining_quota"], 8)
        self.assertEqual(json.loads(self.receipt.read_bytes()), report)
        _, data, _ = self.post.call_args_list[0].args
        self.assertEqual(json.loads(data)["urlList"], report["urls"])
        _, data, _ = self.post.call_args_list[1].args
        self.assertEqual(data.decode().splitlines(), report["urls"])
        self.assertEqual(self.run_release(*args)[0], 0)
        self.assertEqual(self.post.call_count, 2)

    def test_tls_failure_is_recorded_without_exposing_credentials(self):
        self.post.side_effect = [(200, b""), ssl.SSLCertVerificationError("fixture-token")]
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertEqual(report["notifications"]["indexnow"]["state"], "accepted")
        self.assertEqual(report["notifications"]["baidu"]["reason"], "tls_certificate_verification")
        self.assertNotIn("fixture-token", self.receipt.read_text(encoding="utf-8"))

    def test_two_packages_must_share_one_seo_manifest(self):
        other = Path(self.temp) / "other.json"
        different = json.loads(json.dumps(MANIFEST))
        different["pages"][1]["title"] = "Stale title"
        other.write_text(json.dumps(different))
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            status = seo.main(["--manifest", str(self.manifest), "--singapore-manifest", str(other),
                               "--shenzhen-app", self.sz, "--singapore-app", self.sg,
                               "--notify", "--receipt", str(self.receipt)])
        self.assertEqual(status, 1)
        self.assertIn("release manifests differ", errors.getvalue())
        self.post.assert_not_called()

    def test_unconfigured_notifications_do_not_report_success(self):
        with patch.dict(seo.os.environ, {"QF_SEO_INDEXNOW_KEY": "", "QF_SEO_BAIDU_TOKEN": ""}):
            status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertEqual(report["notifications"]["indexnow"]["state"], "not_configured")
        self.assertEqual(report["notifications"]["baidu"]["state"], "not_configured")
        self.post.assert_not_called()

    def test_unchanged_release_probes_without_resubmitting(self):
        status, report = self.run_release("--previous", str(self.manifest), "--notify",
                                          "--receipt", str(self.receipt))
        self.assertEqual(status, 0)
        self.assertEqual(report["urls"], [])
        self.post.assert_not_called()

    def test_comparing_manifests_includes_brand_alias_updates(self):
        old = json.loads(json.dumps(MANIFEST))
        old["pages"][0]["aliases"].pop()
        self.assertEqual(seo.changed_urls(MANIFEST, old), [seo.SITE + "/"])
        old["icon"]["sha256"] = "0" * 64
        self.assertEqual(seo.changed_urls(MANIFEST, old), [seo.SITE + "/", seo.SITE + "/en/"])

    def test_reads_same_manifest_from_the_jar_layout(self):
        jar = Path(self.temp) / "app.jar"
        with ZipFile(jar, "w") as archive:
            archive.writestr(seo.MANIFEST_ENTRY, self.manifest.read_bytes())
        self.assertEqual(seo.read_manifest(jar), MANIFEST)


if __name__ == "__main__":
    unittest.main()
