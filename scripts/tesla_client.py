"""Tesla findus client — cookie loading + curl-impersonate fetch.

Used by the offline refresh fetchers (fetch_tesla_index.py and
fetch_tesla_locations.py). The live serving stack does NOT use this —
no Tesla calls happen in the user request path.

Cookies are bound to the egress IP that minted them, so this only works from
the machine where you ran refresh-tesla-cookies.sh.
"""
import json
import os
import subprocess
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# TESLA_COOKIES is deliberately not in the vault: Akamai binds _abck to the
# egress IP that minted it, so one shared copy would give every host a cookie
# only one of them can use. refresh-tesla-cookies.sh mints it here per machine.
# Everything else arrives as an environment variable from `manage.py exec`.
ENV_LOCAL_PATH = ROOT / ".env.local"


def load_env():
    if not ENV_LOCAL_PATH.exists():
        return
    for line in ENV_LOCAL_PATH.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def get_tesla_cookies() -> str:
    """Return a Cookie-header value from the TESLA_COOKIES env."""
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
        return 503, {"error": "No cookies available (TESLA_COOKIES empty)."}

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
    return status, {"error": f"tesla upstream HTTP {status}", "body_head": body[:300]}
