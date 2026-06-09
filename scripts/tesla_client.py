"""Tesla findus client — cookie loading + curl-impersonate fetch.

Used by the offline refresh worker (fetch_tesla_superchargers.py). The live
serving stack does NOT use this — pricing is served from data/pricing-cache/
by the Kotlin backend, no Tesla calls in the user request path.

Cookies are bound to the egress IP that minted them, so this only works from
the machine where you ran `make fetch-tesla-supercharger-pricing` (or equivalent).
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE_DIR = ROOT / "data" / "pricing-cache"
ENV_PATH = ROOT / ".env"

# When COOKIE_BOT_URL is set, we fetch cookies from the sidecar instead of
# .env. Bot responses are cached in-process to avoid hammering it for every
# pricing call; the bot has its own TTL but we stay under it to stay fresh.
_bot_cache: dict = {}
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
    any failure fall back to TESLA_COOKIES env."""
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
    """Hit get-charger-details for a slug with curl-impersonate so the TLS/H2
    fingerprint matches the browser that minted _abck. Stock curl (OpenSSL)
    produces a different ClientHello than real browsers (BoringSSL/NSS) and
    Akamai returns 403 even with valid cookies. The wrapper (default
    curl_safari15_5; set TESLA_CURL=curl_chrome116 for Chrome cookies)
    presets ciphers, extensions, H2 settings, and UA/sec-ch-ua headers.
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
    if status in (401, 403, 429):
        _bot_cache.clear()
    return status, {"error": f"tesla upstream HTTP {status}", "body_head": body[:300]}
