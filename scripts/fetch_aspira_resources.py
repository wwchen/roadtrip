#!/usr/bin/env python3
"""Capture per-resource availability envelopes from Aspira NextGen.

Aspira's `/api/availability/map` returns a `resourceAvailabilities` block
keyed by resourceId — the per-individual-site availability we need to
enumerate the per-tenant resource catalog (RFC 0008's reservable_data
section). Each resource is one bookable site within a leaf campground.

Inputs
------
We need the leaf list per tenant. Rather than calling Aspira's `/api/maps`
again here, we read the existing `aspira-maps-{tenant}` capture written
by `fetch_aspira_maps.py`, walk the tree, and pick out each node with a
`transactionLocationId` set (these are the bookable leaves; the same
predicate AspiraLeavesEtl uses).

Output
------
  data/raw/aspira-resources-{tenant}/<UTC-ts>/leaf-<mapId>.json

Multi-part: one envelope per leaf, all under one timestamp directory so
the ETL gets the entire run as a single InputBundle.

Rate limiting
-------------
Aspira's WAF (Azure App Gateway) is volume-from-our-IP. Match the
backend's AspiraAvailabilityClient throttle (1.5s minimum gap, mutex
serialized). On WAF challenge HTML or 5xx, back off 3s/6s/12s.

Run:
  python3 scripts/fetch_aspira_resources.py --slug aspira-resources-pc \
      --maps-slug aspira-maps-pc --host reservation.pc.gc.ca

Adding a fourth tenant is one YAML row; this fetcher takes everything as
flags so it stays vendor-shaped not host-shaped.
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

UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)

FETCHER = "fetch_aspira_resources"
FETCHER_VERSION = "1"

# Match AspiraAvailabilityClient's throttle.
MIN_GAP_S = 1.5
RETRY_DELAYS_S = (3.0, 6.0, 12.0)
TIMEOUT_S = 30

# Aspira "any equipment" sentinel; matches AspiraAvailabilityClient.
EQUIPMENT_ANY = "-32768"


def _walk_leaf_map_ids(maps_slug: str) -> list[int]:
    """Walk the newest aspira-maps-{tenant} capture, return every leaf mapId.

    Mirrors AspiraLeavesWalk.walk in the Kotlin ETL, but stripped to the
    only thing the fetcher needs: the set of mapIds with a non-null
    transactionLocationId. We do NOT need titles or parent context here —
    that's the ETL's job.
    """
    upstream = load_source(maps_slug)
    capture_root: Path = upstream.output_dir_prefix
    if not capture_root.is_dir():
        err(f"no capture for {maps_slug} at {capture_root}; run fetch_aspira_maps.py first")
        return []
    # Capture shape: data/raw/{maps-slug}/<ts>.json (single envelope per
    # tenant). Newest by lex order.
    candidates = sorted(p for p in capture_root.iterdir() if p.suffix == ".json" and p.is_file())
    if not candidates:
        err(f"no .json captures under {capture_root}; run fetch_aspira_maps.py first")
        return []
    newest = candidates[-1]

    try:
        envelope = json.loads(newest.read_text())
    except json.JSONDecodeError as e:
        err(f"could not parse {newest}: {e}")
        return []
    nodes = envelope.get("payload") or []
    if not isinstance(nodes, list):
        err(f"unexpected payload shape in {newest}: not a list")
        return []

    leaf_ids: list[int] = []
    seen: set[int] = set()
    # First pass: direct nodes.
    for node in nodes:
        if not isinstance(node, dict):
            continue
        txn = node.get("transactionLocationId")
        if txn is None or txn == "":
            continue
        mid = node.get("mapId")
        if mid is None or mid in seen:
            continue
        seen.add(mid)
        leaf_ids.append(mid)
    # Second pass: leaves only declared via parent.mapLinks entries.
    for node in nodes:
        if not isinstance(node, dict):
            continue
        for link in node.get("mapLinks") or []:
            if not isinstance(link, dict):
                continue
            txn = link.get("transactionLocationId")
            if txn is None or txn == "":
                continue
            child = link.get("childMapId")
            if child is None or child in seen:
                continue
            seen.add(child)
            leaf_ids.append(child)

    err(f"  {newest.name}: {len(leaf_ids)} leaf mapIds")
    return leaf_ids


def _today_window_iso() -> tuple[str, str]:
    """Use a 1-day window starting today (UTC). The fetcher only needs

    Aspira to populate `resourceAvailabilities` — it doesn't matter which
    day we ask about. Smallest window minimizes WAF surface.
    """
    today = dt.datetime.now(dt.timezone.utc).date()
    tomorrow = today + dt.timedelta(days=1)
    return today.isoformat(), tomorrow.isoformat()


def _build_url(host: str, map_id: int, start: str, end: str) -> str:
    return (
        f"https://{host}/api/availability/map"
        f"?mapId={map_id}"
        f"&bookingCategoryId=0"
        f"&startDate={start}"
        f"&endDate={end}"
        f"&isReserving=true"
        f"&getDailyAvailability=true"
        f"&partySize=1"
        f"&equipmentCategoryId={EQUIPMENT_ANY}"
        f"&subEquipmentCategoryId={EQUIPMENT_ANY}"
    )


def _fetch_with_backoff(
    url: str, headers: dict
) -> tuple[int, dict, str] | None:
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
        # Aspira's WAF returns HTML 200s; treat like a soft failure.
        if body.lstrip().startswith("<"):
            err(f"    attempt {attempt + 1}/{len(delays)}: WAF challenge HTML")
            continue
        if status == 200:
            return status, resp_headers, body
        if status >= 500 or status == 429:
            err(f"    attempt {attempt + 1}/{len(delays)}: HTTP {status}")
            continue
        # Other client errors: stop, return what we got so the caller can log.
        err(f"    attempt {attempt + 1}/{len(delays)}: HTTP {status}: {body[:200]}")
        return status, resp_headers, body
    err(f"    giving up after {len(delays)} attempts")
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--slug",
        required=True,
        help="data_source slug from poi-registry.yaml (e.g. aspira-resources-pc)",
    )
    parser.add_argument(
        "--maps-slug",
        required=True,
        help="paired aspira-maps-{tenant} slug we read leaves from",
    )
    parser.add_argument("--host", required=True, help="Aspira host (e.g. reservation.pc.gc.ca)")
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="cap on leaves to fetch (default: all). Useful for partial backfills.",
    )
    args = parser.parse_args()

    src = load_source(args.slug)
    leaf_ids = _walk_leaf_map_ids(args.maps_slug)
    if args.limit:
        leaf_ids = leaf_ids[: args.limit]

    if not leaf_ids:
        err("nothing to fetch; aborting")
        return 1

    ts = utc_ts()
    start, end = _today_window_iso()
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Referer": f"https://{args.host}/",
    }

    last_call_at = 0.0
    written = 0
    skipped = 0
    for i, mid in enumerate(leaf_ids, start=1):
        gap = time.monotonic() - last_call_at
        if gap < MIN_GAP_S:
            time.sleep(MIN_GAP_S - gap)

        url = _build_url(args.host, mid, start, end)
        err(f"  [{i}/{len(leaf_ids)}] mapId={mid}")
        result = _fetch_with_backoff(url, headers)
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
            request_headers=headers,
            response_status=status,
            response_headers=resp_headers,
            payload=payload,
            ts=ts,
            part=f"leaf-{mid}",
        )
        written += 1

    err(f"  {args.slug}: wrote {written} envelopes, skipped {skipped}, ts={ts}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
