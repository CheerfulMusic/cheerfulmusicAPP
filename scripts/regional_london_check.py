"""Measure a real share page from a private London runner. Never sends its token to a probe service."""
import argparse
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import statistics
import sys
from urllib.parse import urlsplit

ORIGIN = "https://cheerfulmusic.com"
EGRESS_CHECK = "https://www.cloudflare.com/cdn-cgi/trace"
CARD = ".live-gift-content h2"
PLAYER = ".live-gift-play button"
PLAY_TIMING = """
(() => {
  const play = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function (...args) {
    window.__qfMeasuredAudio = this;
    this.addEventListener('playing', () => {
      window.__qfPlayingAt = performance.now();
    }, {once: true});
    this.addEventListener('error', () => {
      window.__qfPlaybackFailure = 'media-error-' + (this.error?.code ?? 0);
    }, {once: true});
    const attempt = play.apply(this, args);
    attempt?.catch(error => { window.__qfPlaybackFailure ??= error.name; });
    return attempt;
  };
})();
"""
OPT_OUT = "if (location.origin === 'https://cheerfulmusic.com') localStorage.setItem('qf.analytics.optout', '1');"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def share_token(file):
    url = file.read_text(encoding="utf-8-sig").strip() if file else os.environ.get("QF_SHARE_URL", "").strip()
    parsed = urlsplit(url)
    token = re.fullmatch(r"/gift/([a-zA-Z0-9_-]{43})", parsed.path)
    require(parsed.scheme == "https" and parsed.netloc == "cheerfulmusic.com" and token
            and not parsed.query and not parsed.fragment, "expected a canonical existing share link")
    return token[1]


def allow_safe_method(route):
    if route.request.method in ("GET", "HEAD"):
        route.continue_()
    else:
        route.abort()


def admission_cookie():
    raw = os.environ.get("QF_INVITE_COOKIE", "").strip()
    if not raw:
        return None
    name, sep, value = raw.split(";", 1)[0].partition("=")
    require(sep and re.fullmatch(r"[a-zA-Z0-9_-]+", name) and re.fullmatch(r"[!-~]+", value),
            "invalid private invite cookie")
    return {"name": name, "value": value, "url": ORIGIN}


def egress(browser, allow_other):
    from playwright.sync_api import Error

    try:
        with browser.new_context() as context:
            page = context.new_page()
            page.goto(EGRESS_CHECK, wait_until="domcontentloaded", timeout=8000)
            info = dict(line.split("=", 1) for line in page.locator("body").inner_text().splitlines() if "=" in line)
    except Error:
        require(allow_other, "egress location check failed before the share URL was requested")
        info = {}
    loc, colo = info.get("loc"), info.get("colo")
    require(allow_other or (loc and colo), "egress location check unavailable")
    require(allow_other or (loc == "GB" and colo == "LHR"),
            "egress is not GB/LHR; run on the private London host or use --allow-non-london for smoke only")
    return {"country": loc, "edge": colo, "london_egress_check": loc == "GB" and colo == "LHR"}


def seed(browser, url, metadata_url, timeout, cookie):
    with browser.new_context(service_workers="block") as context:
        if cookie:
            context.add_cookies([cookie])
        context.route("**/*", allow_safe_method)
        context.add_init_script(OPT_OUT)
        page = context.new_page()
        with page.expect_response(lambda r: r.url == metadata_url and r.request.method == "GET",
                                  timeout=timeout) as captured:
            document = page.goto(url, wait_until="domcontentloaded", timeout=timeout)
            require(document and document.status == 200, "share document did not return 200")
        response = captured.value
        require(response.status == 200, "share metadata did not return 200")
        body = response.body()
        require(0 < len(body) <= 131072, "metadata size outside expected range")
        json.loads(body)
        page.locator(CARD).wait_for(state="visible", timeout=timeout)
        return body


def measure(browser, url, metadata_url, payload, timeout, playback, cookie):
    from playwright.sync_api import TimeoutError

    with browser.new_context(service_workers="block") as context:
        if cookie:
            context.add_cookies([cookie])
        mode = "combined_replay" if payload else "current_split"
        observations = {"metadata_origin_requests": 0, "metadata_replays": 0}
        metadata_timing = {}
        audio = []

        def intercept(route):
            req = route.request
            if req.method not in ("GET", "HEAD"):
                route.abort()
            elif req.url == metadata_url and req.method == "GET":
                if payload:
                    observations["metadata_replays"] += 1
                    route.fulfill(status=200, content_type="application/json", body=payload)
                else:
                    observations["metadata_origin_requests"] += 1
                    route.continue_()
            else:
                route.continue_()

        def on_response(response):
            if response.url == metadata_url and response.request.method == "GET":
                metadata_timing["status"] = response.status
                start = response.request.timing.get("responseStart", -1)
                if start >= 0 and not payload:
                    metadata_timing["ttfb_ms"] = round(start, 1)
            if response.request.resource_type == "media":
                headers = response.headers
                if urlsplit(response.url).hostname == "media.cheerfulmusic.com":
                    audio.append({"status": response.status,
                                  "cache": headers.get("x-cache", "").split(" ")[0] or None})

        context.route("**/*", intercept)
        context.add_init_script(OPT_OUT + PLAY_TIMING)
        page = context.new_page()
        page.on("response", on_response)
        doc = page.goto(url, wait_until="domcontentloaded", timeout=timeout)
        require(doc and doc.status == 200, "share document did not return 200")
        page.locator(CARD).wait_for(state="visible", timeout=timeout)
        start_times = page.evaluate("""() => ({
            card_ms: performance.now(),
            document_ttfb_ms: performance.getEntriesByType('navigation')[0].responseStart
        })""")
        start_times = {key: round(value, 1) for key, value in start_times.items()}
        require(observations["metadata_origin_requests"] == (0 if payload else 1)
                and observations["metadata_replays"] == (1 if payload else 0)
                and metadata_timing.get("status") == 200, "unexpected live/replayed metadata path")
        server = doc.server_addr() or {}
        result = dict(mode=mode, document_ip=server.get("ipAddress"),
                      ingress=doc.headers.get("x-qf-origin"), **observations, **start_times,
                      **{"metadata_" + key: value for key, value in metadata_timing.items()})
        if playback:
            if page.locator(".beta-access-dialog").is_visible():
                return dict(result, playback_failure="invite-gate-overlay")
            clicked = page.evaluate("performance.now()")
            try:
                page.locator(PLAYER).click(timeout=timeout)
            except TimeoutError:
                result["playback_failure"] = "play-click-timeout"
            if "playback_failure" not in result:
                try:
                    page.wait_for_function("typeof window.__qfPlayingAt === 'number' || window.__qfPlaybackFailure",
                                           timeout=timeout)
                except TimeoutError:
                    result["playback_failure"] = "playing-timeout"
            if "playback_failure" not in result:
                playing_at = page.evaluate("window.__qfPlayingAt ?? null")
                if playing_at is not None:
                    result["play_to_playing_ms"] = round(playing_at - clicked, 1)
                else:
                    result["playback_failure"] = page.evaluate("window.__qfPlaybackFailure")
            page.evaluate("window.__qfMeasuredAudio?.pause()")
            result["cdn_audio_response"] = audio[0] if audio else None
        return result


def run(args):
    from playwright.sync_api import Error, sync_playwright

    token = share_token(args.share_file)
    cookie = admission_cookie()
    url = f"{ORIGIN}/gift/{token}"
    metadata_url = f"{ORIGIN}/api/public/gifts/{token}"
    options = ["--no-proxy-server"]
    if args.target_ip:
        options.append(f"--host-resolver-rules=MAP cheerfulmusic.com {args.target_ip}")
    samples = []
    with sync_playwright() as playwright:
        kwargs = {"channel": args.channel} if args.channel else {}
        browser = playwright.chromium.launch(args=options, **kwargs)
        try:
            location = egress(browser, args.allow_non_london)
            try:
                payload = seed(browser, url, metadata_url, args.timeout_ms, cookie)
            except Error as error:
                raise RuntimeError(f"share metadata seed failed: {type(error).__name__}") from None
            for index in range(args.pairs):
                modes = (None, payload) if index % 2 == 0 else (payload, None)
                for data in modes:
                    # Playback is a separate current-route metric. Replay warmup must not bias it.
                    try:
                        sample = measure(browser, url, metadata_url, data, args.timeout_ms,
                                         playback=not args.skip_playback and data is None, cookie=cookie)
                    except Error as error:
                        phase = "combined replay" if data else "current split or playback"
                        raise RuntimeError(f"{phase} failed: {type(error).__name__}") from None
                    samples.append(dict(pair=index + 1, **sample))
        finally:
            browser.close()
    medians = {
        mode: round(statistics.median(s["card_ms"] for s in samples if s["mode"] == mode), 1)
        for mode in ("current_split", "combined_replay")
    }
    print(json.dumps({
        "sampled_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "london_browser" if location["london_egress_check"] else "non_london_smoke",
        "playback_enabled": not args.skip_playback,
        "existing_invite_cookie": bool(cookie),
        "location": location, "dns_override": str(args.target_ip) if args.target_ip else None,
        "replay_limit": "Local metadata replay estimates removing a network round trip; "
                        "the production HTML and backend still use the existing implementation. "
                        "Inline payload transfer and rendering changes are not timed.",
        "replayed_metadata_bytes": len(payload), "median_card_ms": medians,
        "estimated_card_delta_ms": round(medians["current_split"] - medians["combined_replay"], 1),
        "samples": samples,
    }, indent=2))
    require(not any("playback_failure" in sample for sample in samples),
            "playback failure recorded; card timing is available but the full end-to-end check failed")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--share-file", type=Path, help="private text file with URL; defaults to QF_SHARE_URL")
    p.add_argument("--target-ip", type=ipaddress.IPv4Address, help="browser-only DNS override, retains HTTPS hostname")
    p.add_argument("--pairs", type=int, default=5, choices=range(1, 16), metavar="1..15")
    p.add_argument("--timeout-ms", type=int, default=30000)
    p.add_argument("--channel", help="optional Playwright channel, for example msedge")
    p.add_argument("--allow-non-london", action="store_true", help="local smoke only, labeled with actual egress")
    p.add_argument("--skip-playback", action="store_true", help="validate only page and metadata")
    args = p.parse_args()
    try:
        require(5000 <= args.timeout_ms <= 60000, "timeout must be 5000–60000 ms")
        run(args)
    except (ImportError, OSError, RuntimeError, ValueError) as error:
        # Browser exception messages can contain the entire unlisted share URL.
        print("FAIL: " + (str(error) if type(error) is RuntimeError else type(error).__name__), file=sys.stderr)
        return 1
    except Exception as error:
        print(f"FAIL: {type(error).__name__}; inspect timings on the private runner", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
