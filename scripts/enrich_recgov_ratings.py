#!/usr/bin/env python3
"""Pull aggregate rating + per-carrier cell coverage from recreation.gov.

Endpoint: /api/ratingreview/aggregate?location_id=<id>&location_type=Campground
(capital C required). Powers the cell-coverage pill on the campground page.

Returns, per campground:
  - overall rating (avg stars + count) → rating_reviews = [avg, count]
  - per-carrier cell coverage → cell_coverage = {verizon: [avg, count], att: …}
    average_rating is 0–4 where
      0 = no signal, 1 = major issues, 2 = some coverage,
      3 = good coverage, 4 = excellent coverage
    We only record carriers with number_of_ratings > 0.

Resume-capable. Skips features that already have `rating_reviews` set
(whether a list or null for no-reviews). Pass --refresh to re-query.
"""
from __future__ import annotations
import argparse
import asyncio
import json
import sys
from pathlib import Path

try:
    import aiohttp
except ImportError:
    print("pip install aiohttp", file=sys.stderr)
    sys.exit(1)

DATA = Path(__file__).parent.parent / "data" / "campgrounds.geojson"
AGGREGATE_URL = "https://www.recreation.gov/api/ratingreview/aggregate"
CONCURRENCY = 4
REQUEST_TIMEOUT = 30.0
RETRY_ON_429 = 5

CARRIER_SLUG = {
    "Verizon": "verizon",
    "AT&T": "att",
    "T-Mobile": "tmobile",
    "Sprint": "sprint",
}


async def fetch(session, recgov_id: str):
    """Returns a dict of fields to write, or None on 404, or error string on transient failure."""
    wait = 2.0
    for attempt in range(RETRY_ON_429 + 1):
        try:
            async with session.get(
                AGGREGATE_URL,
                params={"location_id": recgov_id, "location_type": "Campground"},
                timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT),
            ) as r:
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
        break

    out = {}
    n = d.get("number_of_ratings") or 0
    avg = d.get("average_rating")
    if n > 0 and avg is not None:
        out["rating_reviews"] = [round(float(avg), 2), int(n)]
    else:
        out["rating_reviews"] = None

    cell = {}
    for c in d.get("aggregate_cell_coverage_ratings") or []:
        slug = CARRIER_SLUG.get(c.get("carrier") or "")
        if not slug:
            continue
        nc = c.get("number_of_ratings") or 0
        ac = c.get("average_rating")
        if nc > 0 and ac is not None:
            cell[slug] = [round(float(ac), 2), int(nc)]
    if cell:
        out["cell_coverage"] = cell
    return out


async def worker(session, sem, idx, recgov_id):
    async with sem:
        return idx, await fetch(session, recgov_id)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true")
    ap.add_argument("--limit", type=int)
    args = ap.parse_args()

    data = json.loads(DATA.read_text())
    todo = []
    for i, f in enumerate(data["features"]):
        props = f["properties"]
        if not props.get("recgov_id"):
            continue
        if not args.refresh and "rating_reviews" in props:
            continue
        todo.append(i)
    if args.limit:
        todo = todo[: args.limit]
    print(f"to process: {len(todo)}", file=sys.stderr)

    sem = asyncio.Semaphore(CONCURRENCY)
    hits = blanks = errors = 0
    async with aiohttp.ClientSession(headers={"User-Agent": "Mozilla/5.0 (roadtrip-map enricher)"}) as session:
        tasks = [worker(session, sem, i, data["features"][i]["properties"]["recgov_id"]) for i in todo]
        for n, coro in enumerate(asyncio.as_completed(tasks), 1):
            idx, result = await coro
            props = data["features"][idx]["properties"]
            if isinstance(result, dict):
                for k, v in result.items():
                    if v is None and k == "rating_reviews":
                        props[k] = None
                    else:
                        props[k] = v
                if result.get("rating_reviews"):
                    hits += 1
                    rr = result["rating_reviews"]
                    tag = f"{rr[0]:.1f}★ ({rr[1]})"
                    if result.get("cell_coverage"):
                        tag += f" cell={list(result['cell_coverage'].keys())}"
                else:
                    blanks += 1
                    tag = "no reviews"
            elif result is None:
                props["rating_reviews"] = None
                blanks += 1
                tag = "404"
            else:
                errors += 1
                tag = result
            if n % 100 == 0 or n == len(todo):
                print(f"  [{n}/{len(todo)}] hits={hits} blanks={blanks} errs={errors}  last: {props['name']} → {tag}", file=sys.stderr)

    DATA.write_text(json.dumps(data))
    print(f"done. hits={hits} blanks={blanks} errors={errors}", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())
