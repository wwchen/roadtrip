#!/usr/bin/env python3
"""Capture per-campsite catalog data from rec.gov's monthly availability API.

Reads the existing recgov-campgrounds capture (RIDB facilities) for
FacilityID values, then walks each one calling
  /api/camps/availability/campground/{id}/month?start_date=<current-month>

…to harvest the campsite-level catalog (id, site, loop, campsite_type,
equipment_types, attributes). The same response also carries per-day
availability, but we don't store that here — request-time availability
goes through CachedAvailability. We only persist the catalog half.

Multi-part output: one envelope per facility under
  data/raw/recgov-campsites/<UTC-ts>/facility-<FacilityID>.json

Rate limits: rec.gov's 429s are aggressive. We hold a 1.5s gap between
calls (mirroring the existing AvailabilityClient mutex) and back off
3s/6s/12s on 429.

Run:
  python3 scripts/fetch_recgov_campsites.py --slug recgov-campsites
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
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

API_BASE = "https://www.recreation.gov/api/camps/availability/campground"

FETCHER = "fetch_recgov_campsites"
FETCHER_VERSION = "1"

# Match the existing rec.gov rate-limit shape (see AvailabilityClient.kt).
# 1.5s minimum gap between calls, exponential backoff on 429.
MIN_GAP_S = 1.5
RETRY_DELAYS_S = (3.0, 6.0, 12.0)
TIMEOUT_S = 30


def _facility_ids_from_recgov_campgrounds() -> list[str]:
    """Walk the newest recgov-campgrounds capture, collect every FacilityID.

    The capture is the multi-part RIDB /facilities pages this slug's sibling
    fetcher already wrote. We assume that fetcher has run; if its capture
    dir is empty, this script returns an empty list (and logs a clear
    error).
    """
    upstream_slug = "recgov-campgrounds"
    upstream = load_source(upstream_slug)
    capture_root: Path = upstream.output_dir_prefix
    if not capture_root.is_dir():
        err(f"no capture for {upstream_slug} at {capture_root}; run fetch_recgov.py first")
        return []
    # Newest dated directory wins. The directory's contents are page-NNN.json.
    dated = sorted(p for p in capture_root.iterdir() if p.is_dir())
    if not dated:
        err(f"no dated dirs under {capture_root}; run fetch_recgov.py first")
        return []
    newest = dated[-1]

    ids: list[str] = []
    seen: set[str] = set()
    for page_path in sorted(newest.glob("page-*.json")):
        try:
            envelope = json.loads(page_path.read_text())
        except json.JSONDecodeError as e:
            err(f"  could not parse {page_path}: {e}")
            continue
        records = (envelope.get("payload") or {}).get("RECDATA") or []
        for rec in records:
            fid = rec.get("FacilityID")
            if fid is None:
                continue
            sid = str(fid)
            if sid in seen:
                continue
            seen.add(sid)
            ids.append(sid)
    err(f"  walked {newest.name}: {len(ids)} unique FacilityIDs")
    return ids


def _current_month_iso() -> str:
    """First day of the current UTC month, ISO 8601 with offset, URL-encoded.

    Matches the format AvailabilityClient.kt uses:
      2026-06-01T00:00:00.000Z
    Pass through urlencode at the call site.
    """
    now = dt.datetime.now(dt.timezone.utc)
    first = dt.datetime(now.year, now.month, 1, tzinfo=dt.timezone.utc)
    return first.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _fetch_with_backoff(url: str) -> tuple[int, dict, str] | None:
    """Get url, retrying on 429 with the configured delays. None on terminal failure."""
    delays = (0.0,) + RETRY_DELAYS_S
    for attempt, delay in enumerate(delays):
        if delay:
            time.sleep(delay)
        try:
            status, headers, body = http_get_text(url, timeout=TIMEOUT_S)
        except Exception as e:  # noqa: BLE001
            err(f"    attempt {attempt + 1}/{len(delays)}: transport error: {e}")
            continue
        if status == 200:
            return status, headers, body
        if status == 429:
            err(f"    attempt {attempt + 1}/{len(delays)}: 429 rate-limited")
            continue
        # Non-200, non-429: stop. Body might explain why.
        err(f"    attempt {attempt + 1}/{len(delays)}: HTTP {status}: {body[:200]}")
        return status, headers, body
    err(f"    giving up after {len(delays)} attempts")
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slug", required=True, help="data_source slug from poi-registry.yaml")
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="cap on facilities to fetch (default: all). Useful for partial backfills.",
    )
    args = parser.parse_args()

    src = load_source(args.slug)
    facility_ids = _facility_ids_from_recgov_campgrounds()
    if args.limit:
        facility_ids = facility_ids[: args.limit]

    if not facility_ids:
        err("nothing to fetch; aborting")
        return 1

    ts = utc_ts()
    iso_month = _current_month_iso()
    encoded_month = urllib.parse.quote(iso_month, safe="")

    last_call_at = 0.0
    written = 0
    skipped = 0
    for i, fid in enumerate(facility_ids, start=1):
        # Honor the global gap. Sleeps if we're under the minimum since
        # the last fetch; no-op otherwise.
        gap = time.monotonic() - last_call_at
        if gap < MIN_GAP_S:
            time.sleep(MIN_GAP_S - gap)

        url = f"{API_BASE}/{fid}/month?start_date={encoded_month}"
        err(f"  [{i}/{len(facility_ids)}] facility={fid}")
        result = _fetch_with_backoff(url)
        last_call_at = time.monotonic()
        if result is None:
            skipped += 1
            continue
        status, resp_headers, body = result
        if status != 200:
            skipped += 1
            continue

        payload = parse_payload(resp_headers.get("content-type", ""), body)
        write_envelope(
            source_obj=src,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={},
            response_status=status,
            response_headers=resp_headers,
            payload=payload,
            ts=ts,
            part=f"facility-{fid}",
        )
        written += 1

    err(f"  {args.slug}: wrote {written} envelopes, skipped {skipped}, ts={ts}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
