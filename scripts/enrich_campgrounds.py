#!/usr/bin/env python3
"""Enrich federal campgrounds with data from recreation.gov.

Two APIs, one pass:

  1. GET /api/search?lat=..&lng=..&radius=..&entity_type=campground&inventory_type=camping
     Rec.gov's own map search. Geographic query is far more reliable than
     name-based autocomplete — it finds every campground in rec.gov's index
     within N miles and returns full metadata in one response.

     Writes:  recgov_id, parent_name, parent_type, photo_url, activities,
              rating_reviews ([avg, count])

  2. GET /api/ratingreview/aggregate?location_id=<id>&location_type=Campground
     Returns per-carrier cell coverage ratings on rec.gov's 0-4 scale
     (0 none, 1 major issues, 2 some, 3 good, 4 excellent).

     Writes:  cell_coverage ({verizon: [avg, count], ...})

Resume-capable: skips features already carrying `enriched: true`. Pass
--refresh to re-query every federal campground.

Rate-limiting: rec.gov starts 429ing after ~3000 requests at high concurrency;
we stay at 4 concurrent with exponential backoff and the script is incremental
so interruptions resume cleanly.
"""
from __future__ import annotations
import argparse
import asyncio
import datetime as dt
import json
import math
import sys
from pathlib import Path

try:
    import aiohttp
except ImportError:
    print("pip install aiohttp", file=sys.stderr)
    sys.exit(1)

DATA = Path(__file__).parent.parent / "data" / "campgrounds.geojson"
SEARCH_URL = "https://www.recreation.gov/api/search"
AGGREGATE_URL = "https://www.recreation.gov/api/ratingreview/aggregate"
CONCURRENCY = 4
SEARCH_RADIUS_MI = 2.0
REQUEST_TIMEOUT = 30.0
RETRY_ON_429 = 5
USER_AGENT = "Mozilla/5.0 (roadtrip-map enricher)"

CARRIER_SLUG = {"Verizon": "verizon", "AT&T": "att", "T-Mobile": "tmobile", "Sprint": "sprint"}

# Fields we write (and therefore strip before a --refresh). Listed here so a
# reader can see at a glance what this script controls. `ridb_enriched` was
# written by a previous per-RIDB enricher; listed so --refresh cleans it up.
MANAGED_FIELDS = (
    "enriched", "recgov_id",
    "parent_name", "parent_type",
    "photo_url", "activities",
    "rating_reviews", "cell_coverage",
    "ridb_enriched",  # legacy, remove on refresh
)


def haversine_mi(lat1, lon1, lat2, lon2):
    r = 3958.8
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def normalize_activity(name: str) -> str:
    return " ".join(w.capitalize() for w in name.replace("&", "and").split())


def pick_best(results, src_lat, src_lon, src_name):
    """Closest campground within radius; name-overlap tiebreaker within 0.5 mi."""
    best = None
    best_dist = float("inf")
    for s in results or []:
        if s.get("entity_type") != "campground":
            continue
        try:
            slat = float(s.get("latitude"))
            slon = float(s.get("longitude"))
        except (TypeError, ValueError):
            continue
        dist = haversine_mi(src_lat, src_lon, slat, slon)
        if dist > SEARCH_RADIUS_MI:
            continue
        if dist < best_dist - 0.5:
            best, best_dist = s, dist
        elif abs(dist - best_dist) <= 0.5 and best is not None:
            src_words = set(src_name.lower().split())
            s_words = set((s.get("name") or "").lower().split())
            b_words = set((best.get("name") or "").lower().split())
            if len(s_words & src_words) > len(b_words & src_words):
                best, best_dist = s, dist
    return best, best_dist


async def _get(session, url, params):
    """Fetch with 429 exponential backoff. Raises on persistent failure."""
    wait = 2.0
    for attempt in range(RETRY_ON_429 + 1):
        async with session.get(url, params=params, timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT)) as r:
            if r.status == 429:
                if attempt == RETRY_ON_429:
                    raise RuntimeError(f"429 after {RETRY_ON_429} retries")
                await asyncio.sleep(wait)
                wait = min(wait * 2, 60)
                continue
            if r.status == 404:
                return None
            if r.status != 200:
                raise RuntimeError(f"http {r.status}")
            return await r.json()


async def enrich_one(session, name, lat, lon):
    """Return dict of fields to merge, or string on transient failure."""
    try:
        search = await _get(session, SEARCH_URL, {
            "lat": f"{lat}", "lng": f"{lon}", "radius": str(SEARCH_RADIUS_MI),
            "entity_type": "campground", "inventory_type": "camping", "size": "20",
        })
    except RuntimeError as e:
        return str(e)
    except Exception as e:
        return f"net: {e.__class__.__name__}"

    out = {"enriched": True, "recgov_id": None}
    if not search:
        return out
    best, _ = pick_best(search.get("results") or [], lat, lon, name)
    if not best:
        return out

    out["recgov_id"] = str(best["entity_id"])
    if best.get("parent_name"):
        out["parent_name"] = best["parent_name"]
    if best.get("parent_type"):
        out["parent_type"] = best["parent_type"]
    if best.get("preview_image_url"):
        out["photo_url"] = best["preview_image_url"]
    acts = [normalize_activity(a["activity_name"])
            for a in (best.get("activities") or [])
            if a.get("activity_name")]
    if acts:
        out["activities"] = acts
    avg = best.get("average_rating")
    n = best.get("number_of_ratings") or 0
    if avg is not None and n > 0:
        out["rating_reviews"] = [round(float(avg), 2), int(n)]
    else:
        out["rating_reviews"] = None

    # Phase 2: per-carrier cell coverage. Only if we have an id.
    try:
        agg = await _get(session, AGGREGATE_URL, {
            "location_id": out["recgov_id"], "location_type": "Campground",
        })
    except Exception:
        agg = None
    if isinstance(agg, dict):
        cell = {}
        for c in agg.get("aggregate_cell_coverage_ratings") or []:
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


async def worker(session, sem, idx, name, lat, lon):
    async with sem:
        return idx, await enrich_one(session, name, lat, lon)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true",
                    help="re-query every federal campground (wipes managed fields first)")
    ap.add_argument("--limit", type=int)
    args = ap.parse_args()

    data = json.loads(DATA.read_text())
    if args.refresh:
        for f in data["features"]:
            for k in MANAGED_FIELDS:
                f["properties"].pop(k, None)

    todo = []
    for i, f in enumerate(data["features"]):
        p = f["properties"]
        if p.get("category") != "federal":
            continue
        if p.get("enriched"):
            continue
        todo.append(i)
    if args.limit:
        todo = todo[: args.limit]
    print(f"to process: {len(todo)} federal campgrounds", file=sys.stderr)

    sem = asyncio.Semaphore(CONCURRENCY)
    hits = misses = errors = 0
    async with aiohttp.ClientSession(headers={"User-Agent": USER_AGENT}) as session:
        tasks = []
        for i in todo:
            p = data["features"][i]["properties"]
            lon, lat = data["features"][i]["geometry"]["coordinates"]
            tasks.append(worker(session, sem, i, p["name"], lat, lon))

        for n, coro in enumerate(asyncio.as_completed(tasks), 1):
            idx, result = await coro
            props = data["features"][idx]["properties"]
            if isinstance(result, dict):
                props.update(result)
                if result.get("recgov_id"):
                    hits += 1
                    tag = f"id={result['recgov_id']} parent={result.get('parent_name','-')[:30]}"
                else:
                    misses += 1
                    tag = "no match"
            else:
                errors += 1
                tag = result  # error string; 'enriched' not set so rerun retries
            if n % 100 == 0 or n == len(todo):
                print(f"  [{n}/{len(todo)}] hits={hits} misses={misses} errs={errors}  last: {props['name']} → {tag}",
                      file=sys.stderr)

    # Bump _fetched_at to reflect the most recent network touch (rec.gov for
    # the federal subset). Preserves the existing key shape so the importer
    # reads one timestamp per file regardless of which script wrote last.
    data["_fetched_at"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    DATA.write_text(json.dumps(data))
    print(f"done. hits={hits} misses={misses} errors={errors}", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())
