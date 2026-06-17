#!/usr/bin/env python3
"""Capture small Aspira tenant dictionaries used by reservable ETL.

The per-park `/api/resourcelocation/resources` catalog stores IDs for
resource categories, allowed equipment, and defined attributes. These
tenant-level endpoints resolve those IDs to labels:

  - /api/equipment
  - /api/resourcecategory
  - /api/attribute/filterable

The ETL loads this single envelope into memory and writes resolved names
into each reservable's existing raw JSON. No dictionary table is created.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    err,
    http_get_text,
    load_source,
    parse_payload,
    write_envelope,
)

UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)

FETCHER = "fetch_aspira_dictionaries"
FETCHER_VERSION = "1"
MIN_GAP_S = 1.5
RETRY_DELAYS_S = (3.0, 6.0, 12.0)
TIMEOUT_S = 30

ENDPOINTS = {
    "equipment": "/api/equipment",
    "resource_categories": "/api/resourcecategory",
    "attributes": "/api/attribute/filterable",
}


def _fetch_with_backoff(url: str, headers: dict) -> tuple[int, dict, str] | None:
    delays = (0.0,) + RETRY_DELAYS_S
    for attempt, delay in enumerate(delays):
        if delay:
            time.sleep(delay)
        try:
            status, resp_headers, body = http_get_text(
                url, headers=headers, timeout=TIMEOUT_S
            )
        except Exception as e:  # noqa: BLE001
            err(f"    attempt {attempt + 1}/{len(delays)}: transport error: {e}")
            continue
        if body.lstrip().startswith("<"):
            err(f"    attempt {attempt + 1}/{len(delays)}: WAF challenge HTML")
            continue
        if status == 200:
            return status, resp_headers, body
        if status >= 500 or status == 429:
            err(f"    attempt {attempt + 1}/{len(delays)}: HTTP {status}")
            continue
        err(f"    attempt {attempt + 1}/{len(delays)}: HTTP {status}: {body[:200]}")
        return status, resp_headers, body
    err(f"    giving up after {len(delays)} attempts")
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--slug",
        required=True,
        help="data_source slug from poi-registry.yaml",
    )
    parser.add_argument("--host", required=True, help="Aspira host")
    args = parser.parse_args()

    src = load_source(args.slug)
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Referer": f"https://{args.host}/",
    }

    payload = {}
    endpoint_status = {}
    last_call_at = 0.0
    for name, path in ENDPOINTS.items():
        gap = time.monotonic() - last_call_at
        if gap < MIN_GAP_S:
            time.sleep(MIN_GAP_S - gap)

        url = f"https://{args.host}{path}"
        err(f"  fetching {name}: {url}")
        result = _fetch_with_backoff(url, headers)
        last_call_at = time.monotonic()
        if result is None:
            return 1
        status, resp_headers, body = result
        endpoint_status[name] = status
        if status != 200:
            return 1
        payload[name] = parse_payload(resp_headers.get("content-type", ""), body)

    write_envelope(
        source_obj=src,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=f"https://{args.host}/api/{{equipment,resourcecategory,attribute/filterable}}",
        request_method="GET",
        request_headers=headers,
        response_status=200,
        response_headers={
            f"x-roadtrip-{name.replace('_', '-')}-status": str(status)
            for name, status in endpoint_status.items()
        },
        payload=payload,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
