"""Measure a share from a private London runner or simulate latency in a local browser."""
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
INLINE_TIMING = 'gift_data;desc="inline"'
INLINE_SCRIPT = re.compile(rb'<script type="application/json" id="qf-initial-gift">[^<]*</script>')
CARD_TIMING = f"""
(() => {{
  function recordFrame() {{
    const card = document.querySelector({json.dumps(CARD)});
    if (card && card.getClientRects().length && getComputedStyle(card).visibility === "visible") {{
      window.__qfCardAt = performance.now();
    }} else {{
      requestAnimationFrame(recordFrame);
    }}
  }}
  requestAnimationFrame(recordFrame);
}})();
"""


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


def capture_inline_document(browser, url, timeout, cookie):
    with browser.new_context(service_workers="block") as context:
        if cookie:
            context.add_cookies([cookie])
        context.route("**/*", allow_safe_method)
        context.add_init_script(OPT_OUT)
        page = context.new_page()
        response = page.goto(url, wait_until="commit", timeout=timeout)
        require(response and response.status == 200 and INLINE_TIMING in response.headers.get("server-timing", ""),
                "simulation requires the live inline share response")
        headers = response.headers
        for name in ("content-encoding", "content-length", "transfer-encoding", "set-cookie"):
            headers.pop(name, None)
        body = response.body()
        split_body, count = INLINE_SCRIPT.subn(b"", body)
        require(count == 1, "expected one inline share payload to remove")
        split_headers = {key: value for key, value in headers.items() if key != "server-timing"}
        return {"simulated_inline": (headers, body), "simulated_split": (split_headers, split_body)}


def measure(browser, url, metadata_url, payload, timeout, playback, cookie, mode,
            simulated_latency_ms, simulated_document):
    from playwright.sync_api import TimeoutError

    with browser.new_context(service_workers="block") as context:
        if cookie:
            context.add_cookies([cookie])
        observations = {"metadata_origin_requests": 0, "metadata_replays": 0}
        metadata_timing = {}
        audio = []

        def intercept(route):
            req = route.request
            if req.method not in ("GET", "HEAD"):
                route.abort()
            elif simulated_document is not None and req.is_navigation_request() and req.url == url:
                headers, body = simulated_document
                route.fulfill(status=200, headers=headers, body=body)
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
        context.add_init_script(OPT_OUT + CARD_TIMING + PLAY_TIMING)
        page = context.new_page()
        if simulated_latency_ms is not None:
            cdp = context.new_cdp_session(page)
            cdp.send("Network.enable")
            cdp.send("Network.emulateNetworkConditions", {
                "offline": False, "latency": simulated_latency_ms,
                "downloadThroughput": -1, "uploadThroughput": -1,
            })
        page.on("response", on_response)
        try:
            doc = page.goto(url, wait_until="commit", timeout=timeout)
        except TimeoutError:
            raise RuntimeError(f"{mode}: timed out waiting for the share HTML") from None
        require(doc and doc.status == 200, "share document did not return 200")
        try:
            page.wait_for_function("typeof window.__qfCardAt === 'number'", timeout=timeout)
        except TimeoutError:
            raise RuntimeError(f"{mode}: card not visible; metadata requests="
                               f"{observations['metadata_origin_requests']}, status={metadata_timing.get('status')}") from None
        start_times = page.evaluate("""() => ({
            card_ms: window.__qfCardAt,
            document_ttfb_ms: performance.getEntriesByType('navigation')[0].responseStart
        })""")
        start_times = {key: round(value, 1) for key, value in start_times.items()}
        navigation = page.evaluate("""() => {
            const nav = performance.getEntriesByType('navigation')[0];
            const round = (value) => Math.round(value * 10) / 10;
            const groups = {};
            for (const item of performance.getEntriesByType('resource')) {
                const url = new URL(item.name);
                const bucket = url.origin === location.origin
                    ? (url.pathname.startsWith('/assets/') ? 'bundled_assets'
                        : url.pathname.startsWith('/api/') ? 'same_origin_api' : 'same_origin_other')
                    : (url.hostname === 'media.cheerfulmusic.com' ? 'media_cdn' : 'other_origins');
                const group = groups[bucket] ??= { count: 0, max_duration_ms: 0, last_response_end_ms: 0,
                    max_ttfb_ms: 0, max_download_ms: 0, transfer_bytes: 0, protocols: [] };
                group.count += 1;
                group.max_duration_ms = Math.max(group.max_duration_ms, round(item.duration));
                group.last_response_end_ms = Math.max(group.last_response_end_ms, round(item.responseEnd));
                if (item.requestStart && item.responseStart) {
                    group.max_ttfb_ms = Math.max(group.max_ttfb_ms, round(item.responseStart - item.requestStart));
                    group.max_download_ms = Math.max(group.max_download_ms, round(item.responseEnd - item.responseStart));
                }
                group.transfer_bytes += item.transferSize;
                if (item.nextHopProtocol && !group.protocols.includes(item.nextHopProtocol)) {
                    group.protocols.push(item.nextHopProtocol);
                }
            }
            const timings = Object.fromEntries(nav.serverTiming
                .filter(item => ['gift_lookup', 'gift_render', 'region_upstream'].includes(item.name))
                .map(item => [item.name, round(item.duration)]));
            return { dns_ms: round(nav.domainLookupEnd - nav.domainLookupStart),
                connect_ms: round(nav.connectEnd - nav.connectStart),
                tls_ms: nav.secureConnectionStart ? round(nav.connectEnd - nav.secureConnectionStart) : null,
                request_to_first_byte_ms: round(nav.responseStart - nav.requestStart),
                html_transfer_ms: round(nav.responseEnd - nav.responseStart),
                server_timing_ms: timings, resources: groups };
        }""")
        inline = mode in ("live_inline", "simulated_inline")
        require((INLINE_TIMING in doc.headers.get("server-timing", "")) == inline,
                "unexpected share HTML version for this benchmark mode")
        require(observations["metadata_origin_requests"] == (0 if payload or inline else 1)
                and observations["metadata_replays"] == (1 if payload else 0)
                and (inline or metadata_timing.get("status") == 200), "unexpected live/replayed metadata path")
        server = doc.server_addr() or {}
        result = dict(mode=mode, document_ip=server.get("ipAddress"),
                      ingress=doc.headers.get("x-qf-origin"), **observations, **start_times, navigation=navigation,
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
            location = egress(browser, args.allow_non_london or args.mode == "simulate-london")
            payload = None
            simulated_documents = {}
            if args.mode == "compare-replay":
                try:
                    payload = seed(browser, url, metadata_url, args.timeout_ms, cookie)
                except Error as error:
                    raise RuntimeError(f"share metadata seed failed: {type(error).__name__}") from None
            elif args.mode == "simulate-london":
                try:
                    simulated_documents = capture_inline_document(browser, url, args.timeout_ms, cookie)
                except Error as error:
                    raise RuntimeError(f"share document seed failed: {type(error).__name__}") from None
            modes = {
                "compare-replay": ("current_split", "combined_replay"),
                "live-split": ("current_split",), "live-inline": ("live_inline",),
                "simulate-london": ("simulated_split", "simulated_inline"),
            }[args.mode]
            for index in range(args.pairs):
                order = modes if index % 2 == 0 else modes[::-1]
                for mode in order:
                    data = payload if mode == "combined_replay" else None
                    # Playback is a separate current-route metric. Replay warmup must not bias it.
                    try:
                        sample = measure(browser, url, metadata_url, data, args.timeout_ms,
                                         playback=not args.skip_playback and data is None, cookie=cookie, mode=mode,
                                         simulated_latency_ms=args.latency_ms,
                                         simulated_document=simulated_documents.get(mode))
                    except Error as error:
                        raise RuntimeError(f"{mode} failed: {type(error).__name__}") from None
                    samples.append(dict(pair=index + 1, **sample))
        finally:
            browser.close()
    medians = {
        mode: round(statistics.median(s["card_ms"] for s in samples if s["mode"] == mode), 1)
        for mode in {s["mode"] for s in samples}
    }
    scope = "london_browser" if location["london_egress_check"] else "non_london_smoke"
    delta = None
    if args.mode == "simulate-london":
        scope = "simulated_london_browser"
        delta = medians["simulated_split"] - medians["simulated_inline"]
    elif payload:
        delta = medians["current_split"] - medians["combined_replay"]
    print(json.dumps({
        "sampled_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": scope,
        "playback_enabled": not args.skip_playback,
        "existing_invite_cookie": bool(cookie),
        "location": location, "dns_override": str(args.target_ip) if args.target_ip else None,
        "mode": args.mode,
        "simulated_added_latency_ms": args.latency_ms,
        "simulation_limit": ("Both modes replay the same captured live HTML with and without its inline payload. "
                             "The browser applies latency to live subrequests. Live document TTFB is excluded."
                             ) if simulated_documents else None,
        "replay_limit": ("Local metadata replay estimates removing a network round trip; "
                        "the production HTML and backend still use the existing implementation. "
                        "Inline payload transfer and rendering changes are not timed.") if payload else None,
        "replayed_metadata_bytes": len(payload) if payload else None, "median_card_ms": medians,
        "estimated_card_delta_ms": round(delta, 1) if delta is not None else None,
        "samples": samples,
    }, indent=2))
    require(not any("playback_failure" in sample for sample in samples),
            "playback failure recorded; card timing is available but the full end-to-end check failed")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--share-file", type=Path, help="private text file with URL; defaults to QF_SHARE_URL")
    p.add_argument("--target-ip", type=ipaddress.IPv4Address, help="browser-only DNS override, retains HTTPS hostname")
    p.add_argument("--mode", choices=("compare-replay", "live-split", "live-inline", "simulate-london"),
                   default="compare-replay")
    p.add_argument("--latency-ms", type=int, help="additional Chromium network latency for simulate-london")
    p.add_argument("--pairs", type=int, default=5, choices=range(1, 16), metavar="1..15")
    p.add_argument("--timeout-ms", type=int, default=30000)
    p.add_argument("--channel", help="optional Playwright channel, for example msedge")
    p.add_argument("--allow-non-london", action="store_true", help="local smoke only, labeled with actual egress")
    p.add_argument("--skip-playback", action="store_true", help="validate only page and metadata")
    args = p.parse_args()
    try:
        require(5000 <= args.timeout_ms <= 60000, "timeout must be 5000–60000 ms")
        if args.mode == "simulate-london":
            require(args.latency_ms is not None and 1 <= args.latency_ms <= 2000,
                    "simulate-london requires --latency-ms between 1 and 2000")
        else:
            require(args.latency_ms is None, "--latency-ms requires simulate-london")
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
