#!/usr/bin/env python3
"""Pull per-facility media (primary image) and activity list from the RIDB API.

RIDB is the federal Recreation Information DataBase — the same dataset that
powers recreation.gov. It has no rating/review data (see
enrich_recgov_ratings.py for that), but it does give us a primary photo URL
and a clean list of activities we can badge on popup cards.

Needs an API key from https://ridb.recreation.gov — apply for one from your
recreation.gov profile, then export RIDB_API_KEY before running.

Resume-capable: skips features that already have `ridb_enriched` = True.
Writes `photo_url` (string | absent) and `activities` (list[str] | absent)
into the feature's properties. Pass --refresh to re-query everything.
"""
from __future__ import annotations
import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

try:
    import aiohttp
except ImportError:
    print("pip install aiohttp", file=sys.stderr)
    sys.exit(1)

DATA = Path(__file__).parent.parent / "data" / "campgrounds.geojson"
FACILITY_URL = "https://ridb.recreation.gov/api/v1/facilities/{id}"
CONCURRENCY = 6
REQUEST_TIMEOUT = 30.0
RETRY_ON_429 = 5


def pick_primary(media: list[dict]) -> str | None:
    """Prefer IsPrimary=True, then IsPreview, then first Image. Returns URL or None."""
    images = [m for m in media if m.get("MediaType") == "Image" and m.get("URL")]
    if not images:
        return None
    for m in images:
        if m.get("IsPrimary"):
            return m["URL"]
    for m in images:
        if m.get("IsPreview"):
            return m["URL"]
    return images[0]["URL"]


def normalize_activity(name: str) -> str:
    """Convert 'WILDLIFE VIEWING' → 'Wildlife Viewing' for friendlier display."""
    return " ".join(w.capitalize() for w in name.replace("&", "and").split())


async def fetch(session, recgov_id: str, api_key: str):
    """Returns (photo_url, activities) on 200, None on 404, error string on transient failure."""
    url = FACILITY_URL.format(id=recgov_id)
    wait = 2.0
    for attempt in range(RETRY_ON_429 + 1):
        try:
            async with session.get(url, params={"full": "true"}, headers={"apikey": api_key},
                                   timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT)) as r:
                if r.status == 429:
                    if attempt == RETRY_ON_429:
                        return f"http 429 after {RETRY_ON_429} retries"
                    await asyncio.sleep(wait)
                    wait = min(wait * 2, 60)
                    continue
                if r.status == 404:
                    return None
                if r.status != 200:
                    return f"http {r.status}"
                d = await r.json()
        except Exception as e:
            return f"net: {e.__class__.__name__}"
        photo = pick_primary(d.get("MEDIA") or [])
        acts = [normalize_activity(a["ActivityName"]) for a in (d.get("ACTIVITY") or []) if a.get("ActivityName")]
        return photo, acts


async def worker(session, sem, idx, recgov_id, api_key):
    async with sem:
        return idx, await fetch(session, recgov_id, api_key)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true")
    ap.add_argument("--limit", type=int)
    args = ap.parse_args()

    api_key = os.environ.get("RIDB_API_KEY", "").strip()
    if not api_key:
        print("error: set RIDB_API_KEY env var (apply at recreation.gov profile)", file=sys.stderr)
        sys.exit(1)

    data = json.loads(DATA.read_text())
    todo = []
    for i, f in enumerate(data["features"]):
        props = f["properties"]
        if not props.get("recgov_id"):
            continue
        if not args.refresh and props.get("ridb_enriched"):
            continue
        todo.append(i)
    if args.limit:
        todo = todo[: args.limit]
    print(f"to process: {len(todo)}", file=sys.stderr)

    sem = asyncio.Semaphore(CONCURRENCY)
    hits = missing = errors = 0
    async with aiohttp.ClientSession() as session:
        tasks = [worker(session, sem, i, data["features"][i]["properties"]["recgov_id"], api_key) for i in todo]
        for n, coro in enumerate(asyncio.as_completed(tasks), 1):
            idx, result = await coro
            props = data["features"][idx]["properties"]
            if isinstance(result, tuple):
                photo, acts = result
                if photo:
                    props["photo_url"] = photo
                if acts:
                    props["activities"] = acts
                props["ridb_enriched"] = True
                hits += 1
                tag = f"photo={'y' if photo else 'n'} acts={len(acts)}"
            elif result is None:
                props["ridb_enriched"] = True
                missing += 1
                tag = "404 (facility gone)"
            else:
                errors += 1
                tag = result
            if n % 100 == 0 or n == len(todo):
                print(f"  [{n}/{len(todo)}] hits={hits} missing={missing} errs={errors}  last: {props['name']} → {tag}", file=sys.stderr)

    DATA.write_text(json.dumps(data))
    print(f"done. hits={hits} missing={missing} errors={errors}", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())
