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
LEGACY_ICON = b"isolated ICO payload"
KEY = "test-only-key"
MANIFEST = {
    "version": 1,
    "origin": seo.SITE,
    "icon": {"path": "/brand/favicon-symbol-96.png", "sha256": hashlib.sha256(ICON).hexdigest()},
    "legacyIcon": {"path": "/favicon.ico", "sha256": hashlib.sha256(LEGACY_ICON).hexdigest()},
    "pages": [
        {"path": "/", "language": "en", "title": "cheerfulmusic Ai | Everyone Can Be a Creator",
         "description": "Create your own song in just a few clicks. No music skills needed.",
         "brand": "cheerfulmusic Ai", "lastModified": "2026-11-25",
         "aliases": ["青妙Ai", "cheerfulmusic", "cheerfulmusicAi", "cheerfulmusicAI"],
         "intro": "Create a song with cheerfulmusic Ai (cheerfulmusicai)."},
        {"path": "/zh/", "language": "zh-CN", "title": "青妙Ai", "description": "中文描述", "brand": "青妙Ai",
         "lastModified": "2026-11-25", "intro": "青妙Ai（cheerfulmusicai）是 AI 音乐创作平台。"},
    ],
}
OLD_MANIFEST = {
    **{field: value for field, value in MANIFEST.items() if field != "legacyIcon"},
    "pages": [
        {**MANIFEST["pages"][1], "path": "/", "aliases": MANIFEST["pages"][0]["aliases"]},
        {field: "/en/" if field == "path" else value for field, value in MANIFEST["pages"][0].items()
         if field != "aliases"},
    ],
}


def fixture_files(manifest=MANIFEST):
    files = {}
    sitemap = []
    for page in manifest["pages"]:
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
        alternates = [
            f'<link rel="alternate" hreflang="{other["language"]}" href="{seo.SITE + other["path"]}">'
            for other in manifest["pages"]
        ]
        default = next(other["path"] for other in manifest["pages"] if other["language"] == "en")
        alternates.append(f'<link rel="alternate" hreflang="x-default" href="{seo.SITE + default}">')
        icons = [
            f'<link rel="icon" href="{manifest[field]["path"]}">' for field in ("icon", "legacyIcon")
            if field in manifest
        ]
        files[path] = ("text/html", f"""<html lang="{page['language']}"><head>
            <title>{title}</title>
            <meta name="description" content="{description}">
            <meta property="og:title" content="{title}">
            <meta property="og:description" content="{description}">
            <meta property="og:site_name" content="{brand}">
            <link rel="canonical" href="{seo.SITE + path}">
            {"".join(icons)}
            {"".join(alternates)}
            {schema}</head><body><main><p class="beta-access-intro">{intro}</p></main></body></html>""".encode())
        modified = f"<lastmod>{page['lastModified']}</lastmod>" if "lastModified" in page else ""
        sitemap.append(f"<url><loc>{seo.SITE + path}</loc>{modified}</url>")
    sitemap_xml = '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' + "".join(sitemap) + "</urlset>"
    files["/sitemap.xml"] = ("application/xml", sitemap_xml.encode())
    files["/robots.txt"] = ("text/plain", f"Sitemap: {seo.SITE}/sitemap.xml\n".encode())
    files[manifest["icon"]["path"]] = ("image/png", ICON)
    if "legacyIcon" in manifest:
        files[manifest["legacyIcon"]["path"]] = ("image/x-icon", LEGACY_ICON)
    files[f"/{KEY}.txt"] = ("text/plain", KEY.encode())
    if seo.english_at_root(manifest):
        for path in seo.LEGACY_ENGLISH_PATHS:
            files[path] = ("text/plain", b"", 308, "/")
    return files


@contextlib.contextmanager
def running_site(files):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in files:
                self.send_error(404)
                return
            content_type, body, *redirect = files[self.path]
            if redirect:
                status, location = redirect
                self.send_response(status)
                self.send_header("Location", location)
            else:
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
        legacy_icon = MANIFEST["legacyIcon"]["path"]
        self.regional_files = {path: self.files[path] for path in ("/", icon, legacy_icon)}
        self.regional_files["/internal/regional/ready"] = ("text/plain", b"")
        self.sz = self.enterContext(running_site(self.files))
        self.sg = self.enterContext(running_site(self.regional_files))
        self.public_files = dict(self.files)
        self.local_public = self.enterContext(running_site(self.public_files))
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
        title = MANIFEST["pages"][0]["title"].encode()
        self.regional_files["/"] = (media, body.replace(title, b"Previous brand"))
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertIn("title differs", report["verification"]["singapore"]["reason"])
        self.assertEqual(report["verification"]["public"], "passed")
        self.post.assert_not_called()

    def test_stale_english_intro_prevents_notifications(self):
        media, body = self.files["/"]
        self.files["/"] = (media, body.replace(b"cheerfulmusicai", b"the song studio"))
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

    def test_stale_legacy_icon_prevents_notifications(self):
        path = MANIFEST["legacyIcon"]["path"]
        for region, files in (("shenzhen", self.files), ("singapore", self.regional_files), ("public", self.public_files)):
            with self.subTest(region=region):
                original = files[path]
                files[path] = ("image/x-icon", b"previous version")
                try:
                    status, report = self.run_release("--notify", "--receipt", str(self.receipt))
                    self.assertEqual(status, 1)
                    self.assertIn("icon content differs from the build: /favicon.ico",
                                  report["verification"][region]["reason"])
                finally:
                    files[path] = original
        self.post.assert_not_called()

    def test_legacy_icon_change_refreshes_both_homepages(self):
        previous = {**MANIFEST, "legacyIcon": {**MANIFEST["legacyIcon"], "sha256": "0" * 64}}
        self.assertEqual(seo.changed_urls(MANIFEST, previous), [seo.SITE + "/", seo.SITE + "/zh/"])

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
        self.assertEqual(report["indexnow_urls"], report["urls"] + [seo.SITE + "/en/"])
        self.assertEqual(json.loads(data)["urlList"], report["indexnow_urls"])
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
        self.assertEqual(seo.changed_urls(MANIFEST, old), [seo.SITE + "/", seo.SITE + "/zh/"])

    def test_previous_homepage_manifest_still_reads_and_verifies(self):
        previous = Path(self.temp) / "previous.json"
        previous.write_text(json.dumps(OLD_MANIFEST))
        old = seo.read_manifest(previous)
        self.assertEqual(old, OLD_MANIFEST)
        legacy_site = self.enterContext(running_site(fixture_files(old)))
        seo.verify_site(legacy_site, old)
        urls = seo.changed_urls(MANIFEST, old)
        self.assertEqual(urls, [seo.SITE + "/", seo.SITE + "/zh/"])
        self.assertEqual(seo.indexnow_urls(MANIFEST, old, urls), urls + [seo.SITE + "/en/"])
        self.assertEqual(seo.indexnow_urls(MANIFEST, MANIFEST, []), [])

    def test_missing_legacy_homepage_redirect_prevents_notifications(self):
        self.files["/en/"] = ("text/html", b"Retired English page")
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertIn("/en/ did not return a usable HTTP 308 response",
                      report["verification"]["shenzhen"]["reason"])
        self.assertEqual(report["verification"]["public"], "passed")
        self.post.assert_not_called()

    def test_wrong_legacy_homepage_destination_prevents_notifications(self):
        self.files["/en/index.html"] = ("text/plain", b"", 308, "/zh/")
        status, report = self.run_release("--notify", "--receipt", str(self.receipt))
        self.assertEqual(status, 1)
        self.assertIn("does not redirect to the canonical English homepage",
                      report["verification"]["shenzhen"]["reason"])
        self.post.assert_not_called()

    def test_stale_homepage_alternates_fail_the_check(self):
        media, body = self.files["/"]
        self.files["/"] = (media, body.replace(b'hreflang="en" href="https://cheerfulmusic.com/"',
                                              b'hreflang="en" href="https://cheerfulmusic.com/en/"'))
        status, report = self.run_release()
        self.assertEqual(status, 1)
        self.assertIn("homepage hreflang differs", report["verification"]["shenzhen"]["reason"])

    def test_reads_same_manifest_from_the_jar_layout(self):
        jar = Path(self.temp) / "app.jar"
        with ZipFile(jar, "w") as archive:
            archive.writestr(seo.MANIFEST_ENTRY, self.manifest.read_bytes())
        self.assertEqual(seo.read_manifest(jar), MANIFEST)


if __name__ == "__main__":
    unittest.main()
