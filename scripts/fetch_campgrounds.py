#!/usr/bin/env python3
"""Capture US public campground CSVs from uscampgrounds.info.

Thin fetch-only per RFC 0007. One capture per run; 5 CSVs (one per
region) live under one timestamp directory:

  data/raw/uscampgrounds/<UTC-ts>/west.json
  data/raw/uscampgrounds/<UTC-ts>/southwest.json
  data/raw/uscampgrounds/<UTC-ts>/midwest.json
  data/raw/uscampgrounds/<UTC-ts>/northeast.json
  data/raw/uscampgrounds/<UTC-ts>/south.json

Each file is an envelope whose `payload` is the verbatim CSV body as a
string. The Kotlin ETL parses CSV (TYPE_LABELS, amenity codes, dedupe by
rounded lat/lon) at import time.

Source: https://uscampgrounds.info/takeit.html — CC-BY licensed CSVs.

Run:
  python3 scripts/fetch_campgrounds.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    err,
    http_get_text,
    load_source,
    parse_payload,
    utc_ts,
    write_envelope,
)

SLUG = "uscampgrounds"
SOURCE = load_source(SLUG)

REGIONS = [
    ("west", "WestCamp"),
    ("southwest", "SouthwestCamp"),
    ("midwest", "MidwestCamp"),
    ("northeast", "NortheastCamp"),
    ("south", "SouthCamp"),
]

FETCHER = "fetch_campgrounds"
FETCHER_VERSION = "2"


def main() -> int:
    ts = utc_ts()
    pages = 0
    bytes_total = 0
    for part, region in REGIONS:
        url = f"https://uscampgrounds.info/POI/{region}.csv"
        err(f"  fetching {region}…")
        try:
            status, headers, body = http_get_text(url, timeout=60)
        except Exception as e:  # noqa: BLE001
            err(f"  {region} failed: {e}")
            return 1
        # CSV is text/csv, not JSON — payload stays as a string.
        payload = parse_payload(headers.get("content-type", ""), body)
        write_envelope(
            source_obj=SOURCE,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={},
            response_status=status,
            response_headers=headers,
            payload=payload,
            ts=ts,
            part=part,
        )
        pages += 1
        bytes_total += len(body)
    err(f"  uscampgrounds: {pages} regions, {bytes_total // 1024} KB total (ts={ts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
