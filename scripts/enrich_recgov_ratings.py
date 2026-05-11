#!/usr/bin/env python3
"""Scrape aggregate rating + review count from each campground's detail page.

rec.gov renders AggregateRating in a JSON-LD <script> block — standard
structured data, stable to parse. Missing ratings mean the campground has
zero reviews on rec.gov; we record rating=None to skip on reruns.

Resume-capable: skips features that already have a rating_reviews field
(list [rating, count] on hit, None on confirmed-no-reviews). Pass --refresh
to re-query everything.
"""
from __future__ import annotations
import argparse
import asyncio
import json
import re
import sys
from pathlib import Path

try:
    import aiohttp
except ImportError:
    print("pip install aiohttp", file=sys.stderr)
    sys.exit(1)

DATA = Path(__file__).parent.parent / "data" / "campgrounds.geojson"
CAMPGROUND_URL = "https://www.recreation.gov/camping/campgrounds/{id}"
CONCURRENCY = 4
REQUEST_TIMEOUT = 30.0
RETRY_ON_429 = 5

LD_RE = re.compile(r'<script type="application/ld\+json">(.*?)</script>', re.DOTALL)


def extract_rating(html: str) -> tuple[float, int] | None:
    for m in LD_RE.finditer(html):
        try:
            d = json.loads(m.group(1))
        except Exception:
            continue
        ar = d.get("aggregateRating") if isinstance(d, dict) else None
        if not ar:
            continue
        try:
            return float(ar["ratingValue"]), int(ar["reviewCount"])
        except (KeyError, TypeError, ValueError):
            continue
    return None


async def fetch(session, recgov_id: str) -> tuple[float, int] | None | str:
    """Return (rating, count), None if no rating, or error string on transient failure."""
    url = CAMPGROUND_URL.format(id=recgov_id)
    wait = 2.0
    for attempt in range(RETRY_ON_429 + 1):
        try:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT)) as r:
                if r.status == 429:
                    if attempt == RETRY_ON_429:
                        return f"http 429 after {RETRY_ON_429} retries"
                    await asyncio.sleep(wait)
                    wait = min(wait * 2, 60)
                    continue
                if r.status == 404:
                    return None  # campground page gone; no rating
                if r.status != 200:
                    return f"http {r.status}"
                html = await r.text()
        except Exception as e:
            return f"net: {e.__class__.__name__}"
        return extract_rating(html)


async def worker(session, sem, idx: int, recgov_id: str):
    async with sem:
        return idx, await fetch(session, recgov_id)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true", help="re-scrape features that already have a rating_reviews field")
    ap.add_argument("--limit", type=int, help="only process first N features")
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
    hits = no_rating = errors = 0
    async with aiohttp.ClientSession(headers={"User-Agent": "Mozilla/5.0 (roadtrip-map enricher)"}) as session:
        tasks = [worker(session, sem, i, data["features"][i]["properties"]["recgov_id"]) for i in todo]
        for n, coro in enumerate(asyncio.as_completed(tasks), 1):
            idx, result = await coro
            props = data["features"][idx]["properties"]
            if isinstance(result, tuple):
                props["rating_reviews"] = [round(result[0], 2), result[1]]
                hits += 1
                tag = f"{result[0]:.1f}★ ({result[1]})"
            elif result is None:
                props["rating_reviews"] = None
                no_rating += 1
                tag = "no rating"
            else:
                errors += 1
                tag = result  # error message; don't persist, so rerun retries
            if n % 100 == 0 or n == len(todo):
                print(f"  [{n}/{len(todo)}] hits={hits} no_rating={no_rating} errs={errors}  last: {props['name']} → {tag}", file=sys.stderr)

    DATA.write_text(json.dumps(data))
    print(f"done. hits={hits} no_rating={no_rating} errors={errors}", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())
