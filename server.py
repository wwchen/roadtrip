#!/usr/bin/env python3
"""Static file server + Tesla findus pricing proxy.

Routes:
  /api/pricing/<locationSlug>  → proxies tesla.com/api/findus/get-charger-details,
                                 caches to data/pricing-cache/<slug>.json for 30 days
  everything else              → serves files from the repo root

Cookies for Tesla are read from .env:  TESLA_COOKIES=<raw cookie header from DevTools>
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).parent
CACHE_DIR = ROOT / "data" / "pricing-cache"
CACHE_TTL_SECONDS = 30 * 24 * 3600  # 30 days
ENV_PATH = ROOT / ".env"


def load_env():
    if not ENV_PATH.exists():
        return
    for line in ENV_PATH.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def fetch_tesla_pricing(slug: str) -> tuple[int, dict | str]:
    """Uses curl-impersonate so the TLS/H2 fingerprint matches the browser
    that minted _abck — stock curl (OpenSSL) produces a different ClientHello
    than real browsers (BoringSSL/NSS) and Akamai returns 403 even with valid
    cookies. The wrapper (default curl_safari15_5; set TESLA_CURL=curl_chrome116
    for Chrome-minted cookies) presets ciphers, extensions, H2 settings, and
    UA/sec-ch-ua headers. We only add request-specific headers here.
    """
    cookies = os.environ.get("TESLA_COOKIES", "").strip()
    if not cookies:
        return 503, {"error": "TESLA_COOKIES not set in .env. Paste a Cookie header from DevTools."}

    qs = urllib.parse.urlencode({
        "locationSlug": slug,
        "programType": "supercharger",
        "locale": "en-US",
        "isInHkMoTw": "false",
    })
    url = f"https://www.tesla.com/api/findus/get-charger-details?{qs}"
    # The impersonation target must match the browser that minted _abck —
    # Akamai's fingerprint is per-session. Override with TESLA_CURL=curl_chrome116
    # if cookies came from Chrome.
    curl_bin = os.environ.get("TESLA_CURL", "curl_safari15_5")
    cmd = [
        curl_bin, "-sS", "-w", "\n__HTTP_STATUS__%{http_code}", url,
        "-H", "accept: application/json, text/plain, */*",
        "-b", cookies,
        "-H", "priority: u=1, i",
        "-H", f"referer: https://www.tesla.com/findus?location={slug}&functionType=supercharger",
        "-H", "sec-fetch-dest: empty",
        "-H", "sec-fetch-mode: cors",
        "-H", "sec-fetch-site: same-origin",
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
    except subprocess.TimeoutExpired:
        return 504, {"error": "curl timeout"}
    except FileNotFoundError:
        return 500, {"error": f"{curl_bin} not found on PATH"}

    if result.returncode != 0:
        return 502, {"error": f"curl exit {result.returncode}: {result.stderr[:300]}"}

    out = result.stdout
    marker = "\n__HTTP_STATUS__"
    if marker in out:
        body, _, status_str = out.rpartition(marker)
        try:
            status = int(status_str.strip())
        except ValueError:
            status = 0
    else:
        body, status = out, 0

    if status == 200:
        try:
            return 200, json.loads(body)
        except json.JSONDecodeError:
            return 502, {"error": "tesla returned non-JSON on 200", "body_head": body[:300]}
    return status, {"error": f"tesla upstream HTTP {status}", "body_head": body[:300]}


def get_cached_or_fetch(slug: str) -> tuple[int, dict]:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_file = CACHE_DIR / f"{slug}.json"

    if cache_file.exists():
        age = time.time() - cache_file.stat().st_mtime
        if age < CACHE_TTL_SECONDS:
            data = json.loads(cache_file.read_text())
            data["_cache"] = {"age_seconds": int(age), "hit": True}
            return 200, data

    status, data = fetch_tesla_pricing(slug)
    if status == 200 and isinstance(data, dict):
        cache_file.write_text(json.dumps(data, indent=2))
        data["_cache"] = {"age_seconds": 0, "hit": False}
    return status, data


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        sys.stderr.write(f"[{self.log_date_time_string()}] {format % args}\n")

    def do_GET(self):
        if self.path.startswith("/api/pricing/"):
            slug = self.path[len("/api/pricing/"):].split("?", 1)[0]
            if not slug or "/" in slug or ".." in slug:
                self._json(400, {"error": "bad slug"})
                return
            status, data = get_cached_or_fetch(slug)
            self._json(status, data)
            return

        # Static files
        path = urllib.parse.unquote(self.path.split("?", 1)[0])
        if path == "/":
            path = "/index.html"
        target = (ROOT / path.lstrip("/")).resolve()
        try:
            target.relative_to(ROOT.resolve())
        except ValueError:
            self._json(403, {"error": "forbidden"})
            return
        if not target.is_file():
            self._json(404, {"error": "not found"})
            return
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript",
            ".css": "text/css",
            ".json": "application/json",
            ".geojson": "application/geo+json",
            ".svg": "image/svg+xml",
            ".png": "image/png",
        }.get(target.suffix, "application/octet-stream")
        body = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, data: dict):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)


def main():
    load_env()
    port = int(os.environ.get("PORT", "8765"))
    host = os.environ.get("HOST", "127.0.0.1")
    srv = ThreadingHTTPServer((host, port), Handler)
    print(f"serving at http://{host}:{port}", file=sys.stderr)
    print(f"  pricing proxy  : /api/pricing/<slug>", file=sys.stderr)
    print(f"  cache dir      : {CACHE_DIR}", file=sys.stderr)
    cookies_set = bool(os.environ.get("TESLA_COOKIES"))
    print(f"  TESLA_COOKIES  : {'set' if cookies_set else 'MISSING — see README_PRICING.md'}", file=sys.stderr)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
