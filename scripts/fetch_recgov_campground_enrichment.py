#!/usr/bin/env python3
"""Capture rec.gov campground rating and cell coverage aggregates.

Reads the newest recgov-campgrounds RIDB capture for FacilityID values,
then calls the Recreation.gov browser aggregate endpoint:

  /api/ratingreview/aggregate?location_id=<FacilityID>&location_type=Campground

Multi-part output: one envelope per facility under
  data/raw/recgov-campground-enrichment/<UTC-ts>/facility-<FacilityID>.json

Run:
  python3 scripts/fetch_recgov_campground_enrichment.py --slug recgov-campground-enrichment
"""
from __future__ import annotations

import argparse
import datetime as dt
import email.utils
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

API_URL = "https://www.recreation.gov/api/ratingreview/aggregate"
FETCHER = "fetch_recgov_campground_enrichment"
FETCHER_VERSION = "1"
DEFAULT_MIN_GAP_S = 1.0
RETRY_DELAYS_S = (15.0, 60.0, 180.0, 300.0)
TIMEOUT_S = 30
USER_AGENT = "Mozilla/5.0 (roadtrip-map recgov campground enrichment fetcher)"


def _is_reservable(rec: dict) -> bool:
    raw = rec.get("Reservable")
    return raw is True or (isinstance(raw, str) and raw.lower() == "true")


def _facility_ids_from_recgov_campgrounds(*, reservable_only: bool) -> list[str]:
    upstream_slug = "recgov-campgrounds"
    upstream = load_source(upstream_slug)
    capture_root: Path = upstream.output_dir_prefix
    if not capture_root.is_dir():
        err(f"no capture for {upstream_slug} at {capture_root}; run fetch_recgov.py first")
        return []
    dated = sorted(p for p in capture_root.iterdir() if p.is_dir())
    if not dated:
        err(f"no dated dirs under {capture_root}; run fetch_recgov.py first")
        return []
    newest = dated[-1]

    ids: list[str] = []
    seen: set[str] = set()
    ignored_non_reservable = 0
    for page_path in sorted(newest.glob("page-*.json")):
        try:
            envelope = json.loads(page_path.read_text())
        except json.JSONDecodeError as e:
            err(f"  could not parse {page_path}: {e}")
            continue
        records = (envelope.get("payload") or {}).get("RECDATA") or []
        for rec in records:
            if reservable_only and not _is_reservable(rec):
                ignored_non_reservable += 1
                continue
            fid = rec.get("FacilityID")
            if fid is None:
                continue
            sid = str(fid)
            if sid in seen:
                continue
            seen.add(sid)
            ids.append(sid)
    suffix = ""
    if reservable_only:
        suffix = f", ignored {ignored_non_reservable} non-reservable facilities"
    err(f"  walked {newest.name}: {len(ids)} unique FacilityIDs{suffix}")
    return ids


def _retry_after_seconds(headers: dict) -> float | None:
    raw = headers.get("retry-after")
    if not raw:
        return None
    try:
        return max(0.0, float(raw))
    except ValueError:
        pass

    try:
        when = email.utils.parsedate_to_datetime(raw)
    except (TypeError, ValueError):
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=dt.timezone.utc)
    return max(0.0, (when - dt.datetime.now(dt.timezone.utc)).total_seconds())


def _fetch_with_backoff(url: str) -> tuple[int, dict, str] | None:
    attempts = 1 + len(RETRY_DELAYS_S)
    headers = {"User-Agent": USER_AGENT}
    for attempt in range(attempts):
        try:
            status, resp_headers, body = http_get_text(
                url,
                headers=headers,
                timeout=TIMEOUT_S,
            )
        except Exception as e:  # noqa: BLE001
            err(f"    attempt {attempt + 1}/{attempts}: transport error: {e}")
            if attempt < attempts - 1:
                time.sleep(RETRY_DELAYS_S[attempt])
            continue
        if status == 429:
            if attempt >= attempts - 1:
                err(f"    attempt {attempt + 1}/{attempts}: 429 rate-limited")
                break
            retry_after = _retry_after_seconds(resp_headers)
            delay = RETRY_DELAYS_S[attempt]
            if retry_after is not None:
                delay = max(delay, retry_after)
            err(
                f"    attempt {attempt + 1}/{attempts}: "
                f"429 rate-limited; sleeping {delay:.0f}s"
            )
            time.sleep(delay)
            continue
        return status, resp_headers, body
    err(f"    giving up after {attempts} attempts")
    return None


def _parse_bool(raw: str | None) -> bool:
    if raw is None:
        return True
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "y", "on"}:
        return True
    if normalized in {"0", "false", "no", "n", "off"}:
        return False
    raise argparse.ArgumentTypeError(f"expected boolean, got {raw!r}")


def _latest_capture_ts(capture_root: Path) -> str | None:
    if not capture_root.is_dir():
        return None
    dated = sorted(p.name for p in capture_root.iterdir() if p.is_dir())
    return dated[-1] if dated else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slug", required=True, help="data_source slug from poi-registry.yaml")
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="cap on facilities to fetch (default: all). Useful for partial backfills.",
    )
    parser.add_argument(
        "--ts",
        default="",
        help="capture timestamp directory to write into (default: new UTC timestamp)",
    )
    parser.add_argument(
        "--resume",
        nargs="?",
        const="true",
        default="false",
        type=_parse_bool,
        help=(
            "skip facility files already present in the timestamp directory. "
            "If --ts is omitted, resumes the newest existing capture directory."
        ),
    )
    parser.add_argument(
        "--include-non-reservable",
        action="store_true",
        help="also query RIDB facilities not marked Reservable=true",
    )
    parser.add_argument(
        "--min-gap",
        type=float,
        default=DEFAULT_MIN_GAP_S,
        help=f"minimum seconds between requests (default: {DEFAULT_MIN_GAP_S})",
    )
    args = parser.parse_args()

    src = load_source(args.slug)
    facility_ids = _facility_ids_from_recgov_campgrounds(
        reservable_only=not args.include_non_reservable
    )
    if args.limit:
        facility_ids = facility_ids[: args.limit]
    if not facility_ids:
        err("nothing to fetch; aborting")
        return 1

    if args.resume and not args.ts:
        ts = _latest_capture_ts(src.output_dir_prefix) or utc_ts()
    else:
        ts = args.ts or utc_ts()
    existing: set[str] = set()
    if args.resume:
        out_dir = src.output_dir_prefix / ts
        existing = {
            path.stem.removeprefix("facility-")
            for path in out_dir.glob("facility-*.json")
        }
        err(f"  resume enabled for {ts}: {len(existing)} existing facility envelopes")

    last_call_at = 0.0
    written = 0
    skipped = 0
    resumed = 0
    for i, fid in enumerate(facility_ids, start=1):
        if fid in existing:
            resumed += 1
            continue

        gap = time.monotonic() - last_call_at
        if gap < args.min_gap:
            time.sleep(args.min_gap - gap)

        params = {"location_id": fid, "location_type": "Campground"}
        url = f"{API_URL}?{urllib.parse.urlencode(params)}"
        err(f"  [{i}/{len(facility_ids)}] campground {fid}")
        response = _fetch_with_backoff(url)
        last_call_at = time.monotonic()
        if response is None:
            skipped += 1
            continue

        status, headers, body = response
        parsed = parse_payload(headers.get("content-type", ""), body)
        payload = {"facility_id": fid, "aggregate": parsed if status == 200 else None}
        if status != 200:
            payload["error"] = parsed

        write_envelope(
            source_obj=src,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={"User-Agent": USER_AGENT},
            response_status=status,
            response_headers=headers,
            payload=payload,
            ts=ts,
            part=f"facility-{fid}",
        )
        written += 1

    err(
        f"  {args.slug}: wrote={written} resumed={resumed} skipped={skipped} "
        f"of {len(facility_ids)} facilities (ts={ts})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
