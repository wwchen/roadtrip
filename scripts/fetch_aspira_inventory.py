#!/usr/bin/env python3
"""Capture per-park resource catalog envelopes from Aspira NextGen.

Aspira's `/api/resourcelocation/resources?resourceLocationId={id}` returns
the *named-site catalog* for one park: every campsite's short
label (e.g. "OFC13"), human description ("C13 Phantom RV Pad"), allowed
equipment, capacity, and attribute IDs. This is the data the booking
SPA shows in its results table — the layer above per-resource
availability — and what we need to populate `campsites.name` /
campsite descriptions.

See `docs/booking-providers/aspira.md` for the full wire shape.

Inputs
------
We need the unique set of `resourceLocationId`s the tenant exposes.
Each leaf in `/api/maps` carries one, but multiple leaves can share a
`resourceLocationId` (a park with several loops). Rather than calling
`/api/maps` again here, we read the existing `aspira-maps-{tenant}`
capture written by `fetch_aspira_maps.py`, walk the tree, and dedup
the `resourceLocationId`s. One call per unique park, not per leaf.

Output
------
  data/raw/aspira-inventory-{tenant}/<UTC-ts>/park-<resourceLocationId>.json

Multi-part: one envelope per park, all under one timestamp directory so
the ETL gets the entire run as a single InputBundle.

Rate limiting
-------------
Aspira's WAF (Azure App Gateway) is volume-from-our-IP. Match the
backend's AspiraAvailabilityClient throttle (1.5s minimum gap). On WAF
challenge HTML or 5xx, back off 3s/6s/12s.

Run:
  python3 scripts/fetch_aspira_inventory.py --slug aspira-inventory-pc \\
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

FETCHER = "fetch_aspira_inventory"
FETCHER_VERSION = "1"

# Match AspiraAvailabilityClient's throttle.
MIN_GAP_S = 1.5
RETRY_DELAYS_S = (3.0, 6.0, 12.0)
TIMEOUT_S = 30


def _walk_resource_location_ids(maps_slug: str) -> list[int]:
    """Walk the newest aspira-maps-{tenant} capture, return every unique
    resourceLocationId.

    A bookable park is a node carrying both `transactionLocationId` and
    `resourceLocationId`. Multiple leaves can share a single
    `resourceLocationId` (Tunnel Mountain Village 2's loops all share one),
    so dedup before the per-park walk.
    """
    upstream = load_source(maps_slug)
    capture_root: Path = upstream.output_dir_prefix
    if not capture_root.is_dir():
        err(f"no capture for {maps_slug} at {capture_root}; run fetch_aspira_maps.py first")
        return []
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

    ids: list[int] = []
    seen: set[int] = set()

    def consider(rec: dict) -> None:
        rid = rec.get("resourceLocationId")
        if rid is None or rid == "" or rid in seen:
            return
        # Bookable nodes carry both ids. Defensive: skip rows missing
        # transactionLocationId, which are non-bookable parents.
        if rec.get("transactionLocationId") in (None, ""):
            return
        seen.add(rid)
        ids.append(rid)

    for node in nodes:
        if not isinstance(node, dict):
            continue
        consider(node)
        for link in node.get("mapLinks") or []:
            if isinstance(link, dict):
                consider(link)

    err(f"  {newest.name}: {len(ids)} unique resourceLocationIds")
    return ids


def _build_url(host: str, resource_location_id: int) -> str:
    return (
        f"https://{host}/api/resourcelocation/resources"
        f"?resourceLocationId={resource_location_id}"
    )


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
        help="data_source slug from poi-registry.yaml (e.g. aspira-inventory-pc)",
    )
    parser.add_argument(
        "--maps-slug",
        required=True,
        help="paired aspira-maps-{tenant} slug we read parks from",
    )
    parser.add_argument("--host", required=True, help="Aspira host (e.g. reservation.pc.gc.ca)")
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="cap on parks to fetch (default: all). Useful for partial backfills.",
    )
    args = parser.parse_args()

    src = load_source(args.slug)
    park_ids = _walk_resource_location_ids(args.maps_slug)
    if args.limit:
        park_ids = park_ids[: args.limit]

    if not park_ids:
        err("nothing to fetch; aborting")
        return 1

    # Resume into the newest capture dir if it exists; otherwise start fresh.
    # A run that crashed mid-fetch (and there are several reasons it might —
    # backend kill, WAF challenge cluster, transient network) leaves a
    # timestamped dir with N/M envelopes. Picking it up vs. starting over is
    # the difference between 30s and 5min per restart.
    capture_root: Path = src.output_dir_prefix
    existing_ts: str | None = None
    if capture_root.is_dir():
        candidates = sorted(p for p in capture_root.iterdir() if p.is_dir())
        if candidates:
            existing_ts = candidates[-1].name
    if existing_ts is not None:
        ts = existing_ts
        already = {p.stem for p in (capture_root / ts).glob("park-*.json")}
        before = len(park_ids)
        park_ids = [rid for rid in park_ids if f"park-{rid}" not in already]
        err(f"  resuming ts={ts}: {len(already)} already captured, {len(park_ids)}/{before} remaining")
        if not park_ids:
            err("  nothing left to fetch; everything already captured")
            return 0
    else:
        ts = utc_ts()
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Referer": f"https://{args.host}/",
    }

    last_call_at = 0.0
    written = 0
    skipped = 0
    for i, rid in enumerate(park_ids, start=1):
        gap = time.monotonic() - last_call_at
        if gap < MIN_GAP_S:
            time.sleep(MIN_GAP_S - gap)

        url = _build_url(args.host, rid)
        err(f"  [{i}/{len(park_ids)}] resourceLocationId={rid}")
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
            part=f"park-{rid}",
        )
        written += 1

    err(f"  {args.slug}: wrote {written} envelopes, skipped {skipped}, ts={ts}")
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
