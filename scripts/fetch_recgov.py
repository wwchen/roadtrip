#!/usr/bin/env python3
"""Capture federal campgrounds from RIDB (recreation.gov backend).

One data_source per agency. The agency is passed in via --agency
(args.agency in poi-registry.yaml). The fetcher resolves the agency
abbreviation (NPS, FS, BLM, USACE, FWS, BOR, TVA) to RIDB's orgId
once at startup, then walks GET /organizations/<orgId>/facilities
filtered to camping facilities.

Multi-part output: one envelope per page under
  data/raw/<slug>/<UTC-ts>/page-NNN.json

Auth: RIDB_API_KEY env var. Free key, register at
https://ridb.recreation.gov.

Run:
  python3 scripts/fetch_recgov.py --agency NPS --slug nps-campgrounds
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
FETCHER_VERSION = "1"

# Agency abbreviation → RIDB OrgAbbrevName. The agency arg in
# poi-registry.yaml uses the friendly abbreviation; this map resolves
# what the fetcher actually sends to RIDB on the first /organizations
# call. (USFS is "FS" in RIDB; we accept both for ergonomics.)
AGENCY_ABBREV = {
    "NPS": "NPS",
    "USFS": "FS",
    "FS": "FS",
    "BLM": "BLM",
    "USACE": "USACE",
    "COE": "USACE",
    "FWS": "FWS",
    "USFW": "FWS",
    "BOR": "BOR",
    "TVA": "TVA",
}


def resolve_org_id(api_key: str, agency_abbrev: str) -> int:
    url = f"{API_BASE}/organizations?limit=50"
    headers = {"apikey": api_key}
    status, _, body = http_get_text(url, timeout=30, headers=headers)
    if status != 200:
        raise RuntimeError(f"RIDB /organizations HTTP {status}: {body[:200]}")
    payload = parse_payload("application/json", body)
    for org in payload.get("RECDATA") or []:
        if org.get("OrgAbbrevName") == agency_abbrev:
            return int(org["OrgID"])
    raise RuntimeError(f"agency abbreviation {agency_abbrev!r} not found in RIDB /organizations")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agency", required=True, help="agency abbreviation (NPS, FS, BLM, USACE, FWS, BOR, TVA)")
    parser.add_argument("--slug", help="data_source slug; defaults to RIDB-derived")
    args = parser.parse_args()

    api_key = os.environ.get("RIDB_API_KEY", "").strip()
    if not api_key:
        err("RIDB_API_KEY env var not set; can't talk to RIDB")
        return 1

    agency = AGENCY_ABBREV.get(args.agency.upper())
    if agency is None:
        err(f"unknown --agency={args.agency!r}; expected one of {sorted(AGENCY_ABBREV.keys())}")
        return 1

    slug = args.slug or f"{args.agency.lower()}-campgrounds"
    src = load_source(slug)

    err(f"resolving orgId for agency={agency}…")
    org_id = resolve_org_id(api_key, agency)
    err(f"  orgId={org_id} agency={agency}")

    ts = utc_ts()
    pages_written = 0
    total_seen = 0
    offset = 0
    headers = {"apikey": api_key}

    while True:
        params = {"activity": "CAMPING", "limit": PAGE_SIZE, "offset": offset}
        url = f"{API_BASE}/organizations/{org_id}/facilities?{urllib.parse.urlencode(params)}"
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

    err(f"  {slug}: {pages_written} pages, {total_seen}/{total} facilities (ts={ts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
