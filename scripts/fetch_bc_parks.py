#!/usr/bin/env python3
"""Capture BC Parks (Strapi API) — provincial parks with camping.

Thin fetch-only per RFC 0007. One paginated capture per run:

  data/raw/bcparks-strapi/<UTC-ts>/page-NNN.json

Each page envelope wraps one Strapi response (with the populated
relations the upstream returns: parkPhotos, parkActivities,
parkCampingTypes, parkFacilities). The Kotlin ETL stitches pages and
maps the BC `typeCode` taxonomy to Poi shape.

Strapi pagination: pages stop when we've covered `meta.pagination.pageCount`.

Run:
  python3 scripts/fetch_bc_parks.py
"""
from __future__ import annotations

import sys
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    err,
    http_get_text,
    parse_payload,
    utc_ts,
    write_envelope,
)

API = "https://bcparks.api.gov.bc.ca/api/protected-areas"
PAGE_SIZE = 100
POPULATE = [
    "parkPhotos",
    "parkActivities.activityType",
    "parkCampingTypes.campingType",
    "parkFacilities.facilityType",
]

FETCHER = "fetch_bc_parks"
FETCHER_VERSION = "2"


def build_url(page: int) -> str:
    params = [
        ("pagination[page]", str(page)),
        ("pagination[pageSize]", str(PAGE_SIZE)),
        ("filters[parkCampingTypes][id][$notNull]", "true"),
        ("filters[legalStatus][$eq]", "Active"),
        ("filters[isDisplayed][$eq]", "true"),
    ]
    for p in POPULATE:
        params.append(("populate[]", p))
    return f"{API}?{urllib.parse.urlencode(params)}"


def main() -> int:
    ts = utc_ts()
    page = 1
    pages_written = 0
    total_records = 0
    page_count = 1
    while page <= page_count:
        url = build_url(page)
        err(f"  bcparks-strapi page {page}/{page_count}…")
        try:
            status, headers, body = http_get_text(url, timeout=60)
        except Exception as e:  # noqa: BLE001
            err(f"  page {page} failed: {e}")
            return 1
        payload = parse_payload(headers.get("content-type", ""), body)
        write_envelope(
            source="bcparks-strapi",
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={},
            response_status=status,
            response_headers=headers,
            payload=payload,
            ts=ts,
            part=f"page-{page:03d}",
        )
        pages_written += 1
        if isinstance(payload, dict):
            total_records += len(payload.get("data") or [])
            meta = (payload.get("meta") or {}).get("pagination") or {}
            page_count = max(page_count, int(meta.get("pageCount", page)))
        page += 1
        time.sleep(0.2)

    err(f"  bcparks-strapi: {pages_written} pages, {total_records} records (ts={ts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
