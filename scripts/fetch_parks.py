#!/usr/bin/env python3
"""Capture National Park + State Park polygons from USGS PAD-US.

Thin fetch-only per RFC 0007. Two raw captures per run:
  data/raw/padus-np/<UTC-ts>/page-001.json
  data/raw/padus-sp/<UTC-ts>/page-001.json

Each `page-N.json` envelope wraps one ArcGIS REST query (paginated by
resultOffset). Pages live under one timestamp directory so the ETL knows
they're one logical capture. We capture simplified geometry
(maxAllowableOffset=0.001°, ~111m) — full-resolution polygons are huge
and the map never needs sub-city precision.

Run:
  python3 scripts/fetch_parks.py
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
    load_source,
    parse_payload,
    utc_ts,
    write_envelope,
)

SLUG_NP = "padus-national-parks"
SLUG_SP = "padus-state-parks"

SVC = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/Manager_Name_PADUS/FeatureServer/0"
FIELDS = "Unit_Nm,Loc_Nm,State_Nm,Mang_Name,Des_Tp,GIS_Acres"
SIMPLIFY = 0.001  # degrees, ~111m at equator
PAGE_SIZE = 1000

FETCHER = "fetch_parks"
FETCHER_VERSION = "2"


def capture_target(where: str, slug: str) -> int:
    """Paginated capture for one PAD-US filter. All pages in this run share
    the same `ts` directory so the ETL can stitch them as one logical
    capture; later runs land under a new directory.
    """
    source_obj = load_source(slug)
    ts = utc_ts()
    pages_written = 0
    total = 0
    offset = 0
    while True:
        params = {
            "where": where,
            "outFields": FIELDS,
            "f": "geojson",
            "returnGeometry": "true",
            "geometryPrecision": 5,
            "maxAllowableOffset": SIMPLIFY,
            "outSR": 4326,
            "resultOffset": offset,
            "resultRecordCount": PAGE_SIZE,
        }
        url = f"{SVC}/query?{urllib.parse.urlencode(params)}"
        err(f"  {slug} offset={offset}…")
        try:
            status, headers, body = http_get_text(url, timeout=120)
        except Exception as e:  # noqa: BLE001
            err(f"  {slug} page {pages_written + 1} failed: {e}")
            return -1

        payload = parse_payload(headers.get("content-type", ""), body)
        pages_written += 1
        write_envelope(
            source_obj=source_obj,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={},
            response_status=status,
            response_headers=headers,
            payload=payload,
            ts=ts,
            part=f"page-{pages_written:03d}",
        )
        feats = payload.get("features", []) if isinstance(payload, dict) else []
        total += len(feats)
        if len(feats) < PAGE_SIZE:
            break
        offset += PAGE_SIZE
        time.sleep(0.2)

    err(f"  {slug}: {pages_written} pages, {total} features (ts={ts})")
    return total


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--layer",
        choices=["national-parks", "state-parks", "all"],
        default="all",
    )
    args = parser.parse_args()

    rc = 0
    if args.layer in ("national-parks", "all"):
        err(f"{SLUG_NP}: National Parks (Des_Tp='NP')…")
        if capture_target("Des_Tp='NP'", SLUG_NP) < 0:
            rc = 1
    if args.layer in ("state-parks", "all"):
        err(f"{SLUG_SP}: State Parks (Des_Tp='SP' AND Mang_Type='STAT')…")
        if capture_target("Des_Tp='SP' AND Mang_Type='STAT'", SLUG_SP) < 0:
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
