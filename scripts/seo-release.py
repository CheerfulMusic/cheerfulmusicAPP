"""Verify the two application origins and the public SEO, then optionally notify search engines."""

import argparse
import hashlib
from html.parser import HTMLParser
from http.client import HTTPException
import ipaddress
import json
import os
from pathlib import Path
import re
import ssl
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.robotparser import RobotFileParser
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile

SITE = "https://cheerfulmusic.com"
HOME_LANGUAGES = {"/": "en", "/zh/": "zh-CN"}
LEGACY_HOME_LANGUAGES = {"/": "zh-CN", "/en/": "en"}
LEGACY_ENGLISH_PATHS = ("/en", "/en/", "/en/index.html")
MANIFEST_ENTRY = "BOOT-INF/classes/static/seo-release.json"
BING_API = "https://www.bing.com/indexnow"
BAIDU_API = "https://data.zz.baidu.com/urls"
TIMEOUT = 12
MAX_BODY = 10 * 1024 * 1024
SITEMAP = "{http://www.sitemaps.org/schemas/sitemap/0.9}"


class RedirectsDisabled(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


# App probes must travel through the explicitly selected loopback forwards.
HTTP = build_opener(ProxyHandler({}), RedirectsDisabled())


class HomeDocument(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.language = None
        self.head = False
        self.title_open = False
        self.titles = []
        self.metadata = []
        self.links = []
        self.schema_open = False
        self.schemas = []
        self.intro_open = False
        self.intros = []
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "html":
            self.language = values.get("lang")
        if tag == "head":
            self.head = True
        if not self.head:
            if tag == "p" and "beta-access-intro" in (values.get("class") or "").split():
                self.intros.append("")
                self.intro_open = True
            return
        if tag == "title":
            self.titles.append("")
            self.title_open = True
        if tag == "meta":
            self.metadata.append(values)
        if tag == "link":
            self.links.append(values)
        if tag == "script" and values.get("type") == "application/ld+json":
            self.schemas.append("")
            self.schema_open = True

    def handle_endtag(self, tag):
        if tag == "head":
            self.head = False
        if tag == "title":
            self.title_open = False
        if tag == "script":
            self.schema_open = False
        if tag == "p":
            self.intro_open = False

    def handle_data(self, data):
        if self.title_open:
            self.titles[-1] += data
        if self.schema_open:
            self.schemas[-1] += data
        if self.intro_open:
            self.intros[-1] += data

    def meta(self, attr, key):
        return [value.get("content") for value in self.metadata if value.get(attr) == key]


def read_manifest(path):
    if path.suffix == ".jar":
        with ZipFile(path) as jar:
            value = json.loads(jar.read(MANIFEST_ENTRY))
    else:
        value = json.loads(path.read_bytes())
    if value.get("version") != 1 or value.get("origin") != SITE:
        raise ValueError("Expected a version 1 cheerfulmusic.com SEO build manifest")
    icon = value["icon"]
    if (not re.fullmatch(r"/brand/[A-Za-z0-9._-]+\.png", icon["path"])
            or not re.fullmatch("[a-f0-9]{64}", icon["sha256"])):
        raise ValueError("Invalid path or digest for the built site icon")
    pages = value["pages"]
    languages = {page["path"]: page["language"] for page in pages}
    if len(pages) != len(HOME_LANGUAGES) or languages not in (HOME_LANGUAGES, LEGACY_HOME_LANGUAGES):
        raise ValueError("The SEO manifest must contain both canonical homepages")
    for page in pages:
        if not all(isinstance(page[key], str) and page[key].strip() for key in ("brand", "title", "description")):
            raise ValueError("Homepages require a brand, title and description")
        if "intro" in page and not (isinstance(page["intro"], str) and page["intro"].strip()):
            raise ValueError("The homepage intro must be nonempty text")
        if "lastModified" in page and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", page["lastModified"]):
            raise ValueError("Invalid sitemap lastModified; use YYYY-MM-DD")
        if page["path"] == "/" and (
                not isinstance(page.get("aliases"), list)
                or not all(isinstance(alias, str) and alias.strip() for alias in page["aliases"])):
            raise ValueError("The root homepage requires a list of brand aliases")
    return value


def loopback_origin(value):
    parsed = urlsplit(value)
    try:
        if (parsed.scheme == "http" and ipaddress.ip_address(parsed.hostname or "").is_loopback
                and parsed.port and not (parsed.username or parsed.password or parsed.query or parsed.fragment)
                and parsed.path in ("", "/")):
            return f"http://{parsed.netloc}"
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("Use a direct application forward such as http://127.0.0.1:18081")


def get(base, path, expected_status=200):
    headers = {"Accept-Encoding": "identity", "User-Agent": "CheerfulMusic-SEO-Release/1.0"}
    if base != SITE:
        headers["Host"] = urlsplit(SITE).netloc
    try:
        with HTTP.open(Request(base + path, headers=headers), timeout=TIMEOUT) as response:
            body = response.read(MAX_BODY + 1)
            if response.status != expected_status or len(body) > MAX_BODY:
                raise ValueError(f"{path} did not return a usable HTTP {expected_status} response")
            return body, response.headers
    except HTTPError as exc:
        with exc:
            if expected_status == exc.code == 308:
                return b"", exc.headers
            raise ValueError(f"{path} returned HTTP {exc.code}") from None
    except (OSError, HTTPException):
        raise ValueError(f"{path} could not be fetched") from None


def english_at_root(manifest):
    return any(page["path"] == "/" and page["language"] == "en" for page in manifest["pages"])


def verify_page(page, manifest, body, headers):
    head = HomeDocument(body.decode("utf-8"))
    canonical = SITE + page["path"]
    if head.language != page["language"] or head.titles != [page["title"]]:
        raise ValueError(f"{page['path']} language or title differs from the build")
    if "intro" in page and head.intros != [page["intro"]]:
        raise ValueError(f"{page['path']} visible product introduction differs from the build")
    for attr, key, field in (
        ("name", "description", "description"),
        ("property", "og:title", "title"),
        ("property", "og:description", "description"),
        ("property", "og:site_name", "brand"),
    ):
        if head.meta(attr, key) != [page[field]]:
            raise ValueError(f"{page['path']} {key} differs from the build")
    links = [link.get("href") for link in head.links if link.get("rel") == "canonical"]
    if links != [canonical]:
        raise ValueError(f"{page['path']} canonical differs from the build")
    if english_at_root(manifest):
        expected = {language: SITE + path for path, language in HOME_LANGUAGES.items()}
        expected["x-default"] = SITE + "/"
        alternates = [(link["hreflang"], link.get("href")) for link in head.links
                      if link.get("rel") == "alternate" and "hreflang" in link]
        if len(alternates) != len(expected) or dict(alternates) != expected:
            raise ValueError(f"{page['path']} homepage hreflang differs from the build")
    if not any("icon" in link.get("rel", "").split() and link.get("href") == manifest["icon"]["path"]
               for link in head.links):
        raise ValueError(f"{page['path']} is missing the build's site icon")
    robots = headers.get_all("X-Robots-Tag", [])
    for name in ("robots", "bingbot", "googlebot", "baiduspider", "sogou spider", "360spider"):
        robots.extend(head.meta("name", name))
    if any(re.search(r"\b(?:noindex|none)\b", directive or "", re.IGNORECASE) for directive in robots):
        raise ValueError(f"{page['path']} contains a noindex directive")
    if "aliases" in page:
        schemas = [json.loads(schema) for schema in head.schemas]
        if not any(schema.get("@type") == "WebSite" and schema.get("name") == page["brand"]
                   and schema.get("alternateName") == page["aliases"]
                   for schema in schemas if isinstance(schema, dict)):
            raise ValueError(f"{page['path']} is missing the build's WebSite brand aliases")


def verify_icon(base, manifest):
    icon = manifest["icon"]
    image, headers = get(base, icon["path"])
    if headers.get_content_type() != "image/png" or hashlib.sha256(image).hexdigest() != icon["sha256"]:
        raise ValueError("Site icon content differs from the build")


def verify_singapore(base, manifest):
    # The regional reader intentionally serves just the local root and static assets.
    get(base, "/internal/regional/ready", expected_status=204)
    root = next(page for page in manifest["pages"] if page["path"] == "/")
    verify_page(root, manifest, *get(base, "/"))
    verify_icon(base, manifest)


def verify_site(base, manifest):
    for page in manifest["pages"]:
        verify_page(page, manifest, *get(base, page["path"]))
    verify_icon(base, manifest)
    sitemap, _ = get(base, "/sitemap.xml")
    urls = ElementTree.fromstring(sitemap)
    entries = {entry.findtext(SITEMAP + "loc"): entry.findtext(SITEMAP + "lastmod")
               for entry in urls.findall(SITEMAP + "url")}
    for page in manifest["pages"]:
        canonical = SITE + page["path"]
        if canonical not in entries or ("lastModified" in page and entries[canonical] != page["lastModified"]):
            raise ValueError(f"Sitemap entry for {page['path']} differs from the build")
    redirected_paths = LEGACY_ENGLISH_PATHS if english_at_root(manifest) else ()
    for path in redirected_paths:
        if SITE + path in entries:
            raise ValueError(f"Sitemap still advertises retired homepage {path}")
        _, headers = get(base, path, expected_status=308)
        if headers.get_all("Location", []) not in (["/"], [SITE + "/"]):
            raise ValueError(f"{path} does not redirect to the canonical English homepage")
    robots, _ = get(base, "/robots.txt")
    rules = RobotFileParser()
    rules.parse(robots.decode("utf-8").splitlines())
    if f"{SITE}/sitemap.xml" not in (rules.site_maps() or []):
        raise ValueError("robots.txt does not advertise the canonical sitemap")
    for agent in ("Bingbot", "Googlebot", "Googlebot-Image", "Baiduspider", "Sogou", "360Spider"):
        for path in (*(page["path"] for page in manifest["pages"]), *redirected_paths, manifest["icon"]["path"]):
            if not rules.can_fetch(agent, SITE + path):
                raise ValueError(f"robots.txt blocks {agent} from {path}")


def changed_urls(manifest, previous):
    old_pages = {page["path"]: page for page in previous["pages"]} if previous else {}
    icon_changed = previous is None or previous["icon"] != manifest["icon"]
    return [SITE + page["path"] for page in manifest["pages"]
            if icon_changed or old_pages.get(page["path"]) != page]


def indexnow_urls(manifest, previous, urls):
    # Only notify the old canonical URL when moving from the previous English homepage.
    if english_at_root(manifest) and (previous is None or not english_at_root(previous)):
        return [*urls, SITE + "/en/"]
    return urls


def post(url, body, content_type):
    request = Request(url, data=body, headers={"Content-Type": content_type})
    try:
        with HTTP.open(request, timeout=TIMEOUT) as response:
            return response.status, response.read(65536)
    except HTTPError as exc:
        with exc:
            return exc.code, b""
    except URLError as exc:
        if isinstance(exc.reason, ssl.SSLCertVerificationError):
            raise ssl.SSLCertVerificationError("Search API certificate did not verify") from None
        return None, b""
    except ssl.SSLCertVerificationError:
        raise
    except (OSError, HTTPException):
        # The request URL can contain a token. Never include transport exception details.
        return None, b""


def notify_indexnow(key, urls):
    if not key:
        return {"state": "not_configured"}
    if not re.fullmatch(r"[A-Za-z0-9-]{8,128}", key):
        return {"state": "failed", "reason": "invalid_key_format"}
    key_path = f"/{key}.txt"
    try:
        body, _ = get(SITE, key_path)
    except ValueError:
        return {"state": "failed", "reason": "key_file_unavailable"}
    if body.decode("utf-8", errors="replace").strip() != key:
        return {"state": "failed", "reason": "key_file_mismatch"}
    data = json.dumps({"host": urlsplit(SITE).netloc, "key": key,
                       "keyLocation": SITE + key_path, "urlList": urls}).encode()
    status, _ = post(BING_API, data, "application/json; charset=utf-8")
    return {"state": {200: "accepted", 202: "pending_key_validation"}.get(status, "failed"), "http": status}


def notify_baidu(token, urls):
    if not token:
        return {"state": "not_configured"}
    api = BAIDU_API + "?" + urlencode({"site": SITE, "token": token})
    status, body = post(api, ("\n".join(urls) + "\n").encode(), "text/plain; charset=utf-8")
    if status != 200:
        return {"state": "failed", "http": status}
    try:
        result = json.loads(body)
        accepted = result["success"]
        if type(accepted) is not int or not 0 <= accepted <= len(urls):
            raise ValueError
    except (KeyError, TypeError, ValueError):
        return {"state": "failed", "http": status, "reason": "unexpected_receipt"}
    receipt = {"state": "accepted" if accepted == len(urls) else "partial",
               "http": status, "accepted": accepted}
    if type(result.get("remain")) is int:
        receipt["remaining_quota"] = result["remain"]
    return receipt


def fingerprint(manifest):
    return hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()


def write_receipt(path, report):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def submit(manifest, urls, path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.with_name(path.name + ".lock")
    with lock.open("x"):
        pass
    try:
        previous = json.loads(path.read_bytes()) if path.exists() else {}
        release = fingerprint(manifest)
        indexnow = report.get("indexnow_urls", urls)
        if previous and (previous.get("manifest_sha256") != release or previous.get("urls") != urls
                         or previous.get("indexnow_urls", previous.get("urls")) != indexnow):
            raise ValueError("Receipt belongs to a different SEO update; use a new path")
        report["manifest_sha256"] = release
        results = report["notifications"] = previous.get("notifications", {})
        for name, notify, env, platform_urls in (
            ("indexnow", notify_indexnow, "QF_SEO_INDEXNOW_KEY", indexnow),
            ("baidu", notify_baidu, "QF_SEO_BAIDU_TOKEN", urls),
        ):
            if results.get(name, {}).get("state") in {"accepted", "pending_key_validation"}:
                continue
            # A partial acceptance spends quota; leave further submission to the operator.
            if results.get(name, {}).get("state") == "partial":
                continue
            try:
                results[name] = notify(os.environ.get(env), platform_urls)
            except ssl.SSLCertVerificationError:
                results[name] = {"state": "failed", "reason": "tls_certificate_verification"}
            write_receipt(path, report)
    finally:
        lock.unlink()
    states = {item["state"] for item in results.values()}
    return bool(states & {"accepted", "pending_key_validation"}) and not states & {"failed", "partial"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path, help="Shenzhen built seo-release.json or released JAR")
    parser.add_argument("--singapore-manifest", required=True, type=Path,
                        help="Singapore built seo-release.json or released JAR")
    parser.add_argument("--previous", type=Path, help="Previously released SEO manifest or JAR with a manifest")
    parser.add_argument("--shenzhen-app", required=True, type=loopback_origin)
    parser.add_argument("--singapore-app", required=True, type=loopback_origin)
    parser.add_argument("--notify", action="store_true", help="Submit after both regions and the public site pass")
    parser.add_argument("--receipt", type=Path, help="Release-specific JSON receipt, required for submissions")
    args = parser.parse_args(argv)
    if args.shenzhen_app == args.singapore_app:
        parser.error("Provide separate forwards to the active Shenzhen and Singapore apps")
    if args.notify and not args.receipt:
        parser.error("--notify requires --receipt")
    report = {"verification": {}, "urls": [], "notifications": {}}
    try:
        manifest = read_manifest(args.manifest)
        if read_manifest(args.singapore_manifest) != manifest:
            raise ValueError("The Shenzhen and Singapore release manifests differ")
        previous = read_manifest(args.previous) if args.previous else None
        report["urls"] = changed_urls(manifest, previous)
        indexnow = indexnow_urls(manifest, previous, report["urls"])
        if indexnow != report["urls"]:
            report["indexnow_urls"] = indexnow
        for region, base, verify in (
            ("shenzhen", args.shenzhen_app, verify_site),
            ("singapore", args.singapore_app, verify_singapore),
            ("public", SITE, verify_site),
        ):
            try:
                verify(base, manifest)
                report["verification"][region] = "passed"
            except (ValueError, ElementTree.ParseError) as exc:
                report["verification"][region] = {"state": "failed", "reason": str(exc)}
        verified = all(outcome == "passed" for outcome in report["verification"].values())
        success = verified
        if verified and args.notify and report["urls"]:
            success = submit(manifest, report["urls"], args.receipt, report)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if success else 1
    except (OSError, ValueError, KeyError, TypeError, BadZipFile) as exc:
        print(f"SEO release check failed ({type(exc).__name__}): {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
