#!/usr/bin/env python3
"""Capture federal campgrounds from RIDB (recreation.gov backend).

One data_source for every agency at once. RIDB's /facilities endpoint
(no orgId scope) returns the union — NPS + USFS + BLM + USACE + FWS +
BOR + TVA + everyone else publishing to RIDB. The per-row agency lands
on Poi.Campground.agency at transform time, sourced from the inline
ORGANIZATION[0].OrgAbbrevName that ?full=true ships with each record.

Multi-part output: one envelope per page under
  data/raw/<slug>/<UTC-ts>/page-NNN.json

Auth: RIDB_API_KEY env var. Free key, register at
https://ridb.recreation.gov.

Run:
  python3 scripts/fetch_recgov.py --slug recgov-campgrounds-raw
"""
from __future__ import annotations

import argparse
import os
import sys
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

API_BASE = "https://ridb.recreation.gov/api/v1"
PAGE_SIZE = 50  # RIDB cap

FETCHER = "fetch_recgov"
# v3: agency-agnostic — hits /facilities directly, no orgId scoping.
FETCHER_VERSION = "3"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slug", required=True, help="data_source slug from poi-registry.yaml")
    args = parser.parse_args()

    api_key = os.environ.get("RIDB_API_KEY", "").strip()
    if not api_key:
        err("RIDB_API_KEY env var not set; can't talk to RIDB")
        return 1

    src = load_source(args.slug)

    ts = utc_ts()
    pages_written = 0
    total_seen = 0
    offset = 0
    headers = {"apikey": api_key}

    while True:
        # full=true expands the inline RECAREA + ORGANIZATION arrays so each
        # facility row carries the parent park's name + the managing agency
        # (NPS, FS, BLM, USACE, …). The ETL reads ORGANIZATION[0].OrgAbbrevName
        # into Poi.Campground.agency so the FE can label/colour by agency
        # without us splitting the dataset upstream.
        params = {"activity": "CAMPING", "full": "true", "limit": PAGE_SIZE, "offset": offset}
        url = f"{API_BASE}/facilities?{urllib.parse.urlencode(params)}"
        err(f"  page {pages_written + 1}: offset={offset}")
        try:
            status, resp_headers, body = http_get_text(url, timeout=60, headers=headers)
        except Exception as e:  # noqa: BLE001
            err(f"  page failed: {e}")
            return 1
        if status != 200:
            err(f"  RIDB HTTP {status}: {body[:200]}")
            return 1

        payload = parse_payload(resp_headers.get("content-type", ""), body)
        records = payload.get("RECDATA") or []
        meta = payload.get("METADATA") or {}
        total = (meta.get("RESULTS") or {}).get("TOTAL_COUNT", 0)

        pages_written += 1
        write_envelope(
            source_obj=src,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={"apikey": "***redacted***"},
            response_status=status,
            response_headers=resp_headers,
            payload=payload,
            ts=ts,
            part=f"page-{pages_written:03d}",
        )

        total_seen += len(records)
        if len(records) < PAGE_SIZE or total_seen >= total:
            break
        offset += PAGE_SIZE

    err(f"  {args.slug}: {pages_written} pages, {total_seen}/{total} facilities (ts={ts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
