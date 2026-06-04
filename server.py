#!/usr/bin/env python3
"""Static file server + Tesla findus pricing proxy.

Routes:
  /api/pricing/<locationSlug>  → proxies tesla.com/api/findus/get-charger-details,
                                 caches to data/pricing-cache/<slug>.json for 30 days
  everything else              → serves files from the repo root

Cookies for Tesla are read from .env:  TESLA_COOKIES=<raw cookie header from DevTools>

Static responses send gzip (when the client accepts it) for text-ish bodies, an
ETag derived from mtime+size so reloads return 304, and a per-type Cache-Control:
data files (geojson/json) live for a day, html/api stay no-cache so deploys land
without a hard refresh.
"""
import gzip
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

# /api/pois proxy target. In prod, cloudflared splits the route at the edge and
# this server never sees the path. In `make dev` the user runs both services on
# localhost — proxying here keeps the webapp talking to a single origin so the
# AbortController-driven moveend loop doesn't trip CORS.
BACKEND_URL = os.environ.get("ROADTRIP_BACKEND_URL", "http://127.0.0.1:8080")

# When COOKIE_BOT_URL is set, we fetch cookies from the sidecar instead of
# .env. Bot responses are cached in-process to avoid hammering it for every
# pricing click; the bot has its own TTL but we stay an order of magnitude
# under it to stay fresh.
_bot_cache: dict = {}  # {"cookies": str, "fetched_at": float}
BOT_CACHE_SECONDS = 10 * 60


def load_env():
    if not ENV_PATH.exists():
        return
    for line in ENV_PATH.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def get_tesla_cookies() -> str:
    """Return a Cookie-header value. Prefer cookie-bot when configured; on
    any failure fall back to the TESLA_COOKIES env var so manual refresh
    still works if the bot is down."""
    bot_url = os.environ.get("COOKIE_BOT_URL", "").strip()
    profile = os.environ.get("COOKIE_BOT_PROFILE", "tesla-findus").strip()
    if bot_url:
        now = time.time()
        if _bot_cache and now - _bot_cache["fetched_at"] < BOT_CACHE_SECONDS:
            return _bot_cache["cookies"]
        try:
            req = urllib.request.Request(
                f"{bot_url.rstrip('/')}/cookies/{profile}?format=header",
                headers={"X-Cookie-Bot-Token": os.environ.get("COOKIE_BOT_TOKEN", "")},
            )
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode())
            cookies = data.get("cookie_header", "")
            if cookies:
                _bot_cache["cookies"] = cookies
                _bot_cache["fetched_at"] = now
                return cookies
            print(f"cookie-bot returned empty cookie_header for {profile}", file=sys.stderr)
        except Exception as e:
            print(f"cookie-bot fetch failed ({e}); falling back to .env", file=sys.stderr)
    return os.environ.get("TESLA_COOKIES", "").strip()


def fetch_tesla_pricing(slug: str) -> tuple[int, dict | str]:
    """Uses curl-impersonate so the TLS/H2 fingerprint matches the browser
    that minted _abck — stock curl (OpenSSL) produces a different ClientHello
    than real browsers (BoringSSL/NSS) and Akamai returns 403 even with valid
    cookies. The wrapper (default curl_safari15_5; set TESLA_CURL=curl_chrome116
    for Chrome-minted cookies) presets ciphers, extensions, H2 settings, and
    UA/sec-ch-ua headers. We only add request-specific headers here.
    """
    cookies = get_tesla_cookies()
    if not cookies:
        return 503, {"error": "No cookies available (cookie-bot and TESLA_COOKIES both empty)."}

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
    # Burned cookies: drop the in-process bot cache so the next call re-fetches
    # a fresh jar from the sidecar. 429/403 are the signals Akamai uses.
    if status in (401, 403, 429):
        _bot_cache.clear()
    return status, {"error": f"tesla upstream HTTP {status}", "body_head": body[:300]}


def _health_snapshot() -> dict:
    """One-shot status JSON for /api/health — useful to hit from the phone
    on the trip to confirm cookies are alive and the cache is warm. We
    deliberately avoid making any Tesla call here so a hot Cloudflare cache
    doesn't trigger a needless cookie burn just to answer "are we okay?"."""
    cache_dir_exists = CACHE_DIR.exists()
    cache_count = sum(1 for _ in CACHE_DIR.glob("*.json")) if cache_dir_exists else 0
    cookie_source = "cookie-bot" if os.environ.get("COOKIE_BOT_URL") else "env"
    cookies_present = bool(get_tesla_cookies())
    return {
        "status": "ok" if cookies_present else "degraded",
        "cookie_source": cookie_source,
        "cookies_present": cookies_present,
        "pricing_cache_count": cache_count,
        "now": int(time.time()),
    }


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

        if self.path.startswith("/api/health"):
            self._json(200, _health_snapshot())
            return

        if self.path.startswith("/api/pois"):
            self._proxy_to_backend()
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
        self._serve_static(target)

    # Compressible types — geojson is the big payoff (~5x smaller).
    _GZIPPABLE = {".html", ".js", ".css", ".json", ".geojson", ".svg"}
    # Long-cache the data files: trip is offline-tolerant once primed, and the
    # ETag still revalidates so a deploy of new geojson lands within seconds.
    # index.html stays no-cache so the deploy you just shipped is what loads.
    _CACHE_CONTROL = {
        ".html": "no-cache",
        ".geojson": "public, max-age=86400",
        ".json": "public, max-age=86400",
        ".js": "public, max-age=3600",
        ".css": "public, max-age=3600",
        ".svg": "public, max-age=86400",
        ".png": "public, max-age=86400",
    }

    def _serve_static(self, target: Path):
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript",
            ".css": "text/css",
            ".json": "application/json",
            ".geojson": "application/geo+json",
            ".svg": "image/svg+xml",
            ".png": "image/png",
        }.get(target.suffix, "application/octet-stream")

        st = target.stat()
        # mtime+size is enough for a static-file ETag — we don't worry about
        # collisions across deploys since both fields change on rebuild.
        etag = f'"{int(st.st_mtime)}-{st.st_size}"'
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.send_header("Cache-Control", self._CACHE_CONTROL.get(target.suffix, "no-cache"))
            self.end_headers()
            return

        body = target.read_bytes()
        accept_enc = (self.headers.get("Accept-Encoding") or "").lower()
        gzipped = False
        # Skip gzip on tiny files — header overhead outweighs the savings.
        if target.suffix in self._GZIPPABLE and "gzip" in accept_enc and len(body) > 1024:
            body = gzip.compress(body, compresslevel=6)
            gzipped = True

        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", self._CACHE_CONTROL.get(target.suffix, "no-cache"))
        self.send_header("ETag", etag)
        self.send_header("Vary", "Accept-Encoding")
        if gzipped:
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        self.wfile.write(body)

    def _proxy_to_backend(self):
        url = BACKEND_URL.rstrip("/") + self.path
        try:
            with urllib.request.urlopen(url, timeout=10) as resp:
                body = resp.read()
                ctype = resp.headers.get("Content-Type", "application/json")
                self.send_response(resp.status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as e:
            body = e.read() or b'{"error":"backend error"}'
            self.send_response(e.code)
            self.send_header("Content-Type", e.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self._json(502, {"error": "backend unreachable", "detail": str(e)})

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
    print(f"  pois proxy     : /api/pois -> {BACKEND_URL}", file=sys.stderr)
    print(f"  cache dir      : {CACHE_DIR}", file=sys.stderr)
    bot_url = os.environ.get("COOKIE_BOT_URL", "")
    cookies_set = bool(os.environ.get("TESLA_COOKIES"))
    if bot_url:
        print(f"  cookie source  : cookie-bot ({bot_url})", file=sys.stderr)
    else:
        print(f"  cookie source  : .env TESLA_COOKIES ({'set' if cookies_set else 'MISSING'})", file=sys.stderr)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
