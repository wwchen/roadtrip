#!/usr/bin/env python3
"""Capture Tesla's bulk locations endpoint.

Thin fetch-only per RFC 0007. One capture per run:

  data/raw/tesla-index/<UTC-ts>.json

The endpoint dumps the entire global supercharger feed despite the
country=US param; the Kotlin ETL filters to NA at parse time. Uses the
same curl-impersonate / Akamai-cookie machinery as
fetch_tesla_locations — same cookie burn risk, so we space these calls
out and only run when needed (TTL: 1 week).

Run:
  python3 scripts/fetch_tesla_index.py
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import tesla_client  # noqa: E402
from _envelope import err, parse_payload, write_envelope  # noqa: E402

URL = "https://www.tesla.com/api/findus/get-locations?country=US&view=map"
FETCHER = "fetch_tesla_index"
FETCHER_VERSION = "1"


def fetch() -> tuple[int, str]:
    cookies = tesla_client.get_tesla_cookies()
    if not cookies:
        raise RuntimeError(
            "No Tesla cookies (cookie-bot and TESLA_COOKIES both empty)"
        )
    curl_bin = os.environ.get("TESLA_CURL", "curl_safari15_5")
    cmd = [
        curl_bin, "-sS", "-w", "\n__HTTP_STATUS__%{http_code}", URL,
        "-H", "accept: application/json, text/plain, */*",
        "-b", cookies,
        "-H", "priority: u=1, i",
        "-H", "referer: https://www.tesla.com/findus",
        "-H", "sec-fetch-dest: empty",
        "-H", "sec-fetch-mode: cors",
        "-H", "sec-fetch-site: same-origin",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    out = result.stdout
    marker = "\n__HTTP_STATUS__"
    if marker not in out:
        raise RuntimeError(f"unexpected curl output (no status marker): {out[:300]}")
    body, _, status_str = out.rpartition(marker)
    status = int(status_str.strip())
    return status, body


def main() -> int:
    tesla_client.load_env()
    err("fetching Tesla bulk locations…")
    try:
        status, body = fetch()
    except Exception as e:  # noqa: BLE001
        err(f"  fetch failed: {e}")
        return 1
    if status != 200:
        # Don't pollute the raw cache with WAF/throttle responses. Cookies
        # that fail (403/429) typically mean an IP-binding mismatch or
        # Akamai escalation — re-mint and retry, don't archive the failure.
        err(f"  upstream HTTP {status} — skipping write (body head: {body[:200]!r})")
        return 1
    payload = parse_payload("application/json", body)
    write_envelope(
        source="tesla-index",
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=URL,
        request_method="GET",
        request_headers={"User-Agent": os.environ.get("TESLA_CURL", "curl_safari15_5")},
        response_status=status,
        response_headers={},  # curl-impersonate response headers aren't parsed
        payload=payload,
    )
    n = "?"
    try:
        n = len((payload.get("data") or {}).get("data") or [])
    except Exception:
        pass
    err(f"  wrote {n} locations (HTTP {status})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
