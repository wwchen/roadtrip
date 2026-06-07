#!/usr/bin/env python3
"""Capture Aspira NextGen `/api/maps` indexes for all 3 hosts.

Thin fetch-only per RFC 0007. Three captures per run, one per Aspira-
deployed host:

  data/raw/aspira-maps-pc/<UTC-ts>.json   (reservation.pc.gc.ca)
  data/raw/aspira-maps-bc/<UTC-ts>.json   (camping.bcparks.ca)
  data/raw/aspira-maps-wa/<UTC-ts>.json   (washington.goingtocamp.com)

Each capture is the raw `/api/maps` JSON (the per-park hierarchy used to
look up `transactionLocationId` + `mapId` + `resourceLocationId` for
reservation deeplinks). The Kotlin ETL builds the index at parse time
and binds aspira IDs to campground POIs by exact title match against
the curated `aspira_park_title` field.

Aspira's WAF (Azure App Gateway) rejects bare-curl UAs and `Accept-
Charset` headers — same browser-shaped UA as the backend's
AspiraAvailabilityClient is required.

Run:
  python3 scripts/fetch_aspira_maps.py
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import err, http_get_text, parse_payload, write_envelope  # noqa: E402

UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)
HOSTS = [
    ("aspira-maps-pc", "reservation.pc.gc.ca"),
    ("aspira-maps-bc", "camping.bcparks.ca"),
    ("aspira-maps-wa", "washington.goingtocamp.com"),
]
THROTTLE_S = 1.5  # match the backend's AspiraAvailabilityClient mutex window

FETCHER = "fetch_aspira_maps"
FETCHER_VERSION = "2"


def fetch_host(source: str, host: str) -> bool:
    url = f"https://{host}/api/maps"
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Referer": f"https://{host}/",
    }
    err(f"  fetching {host}…")
    try:
        status, resp_headers, body = http_get_text(url, headers=headers, timeout=30)
    except Exception as e:  # noqa: BLE001
        err(f"  {host} failed: {e}")
        return False

    # WAF challenge detection: Aspira sometimes returns HTML 200s.
    if body.lstrip().startswith("<"):
        err(f"  {host} WAF-challenge HTML response (status={status}); not writing")
        return False

    payload = parse_payload(resp_headers.get("content-type", ""), body)
    if status != 200:
        err(f"  {host} HTTP {status}; not writing")
        return False
    write_envelope(
        source=source,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="GET",
        request_headers=headers,
        response_status=status,
        response_headers=resp_headers,
        payload=payload,
    )
    n = len(payload) if isinstance(payload, list) else "?"
    err(f"  {host}: wrote ({n} top-level entries)")
    return True


def main() -> int:
    rc = 0
    for i, (source, host) in enumerate(HOSTS):
        if i > 0:
            time.sleep(THROTTLE_S)
        if not fetch_host(source, host):
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
