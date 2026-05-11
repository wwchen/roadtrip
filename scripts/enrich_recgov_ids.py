#!/usr/bin/env python3
"""Look up recreation.gov entity_id for each federal campground in the GeoJSON.

Hits www.recreation.gov/api/search/suggest (the autocomplete behind the site's
search box) with the campground name, filters to entity_type=campground, then
picks the closest match within MATCH_RADIUS_MI of the source coordinate. Writes
`recgov_id` back into the feature's properties.

Incremental by design: if a feature already has `recgov_id` set (success) or
`recgov_id: null` (explicitly not-found after a previous run), we skip it. Pass
--refresh to re-query everything.

Runs multiple queries concurrently; the suggest API tolerates ~8 in-flight.
"""
from __future__ import annotations
import argparse
import asyncio
import json
import math
import sys
import urllib.parse
from pathlib import Path

try:
    import aiohttp
except ImportError:
    print("this script needs aiohttp: pip install aiohttp", file=sys.stderr)
    sys.exit(1)

DATA = Path(__file__).parent.parent / "data" / "campgrounds.geojson"
SUGGEST_URL = "https://www.recreation.gov/api/search/suggest"
CONCURRENCY = 4  # rec.gov starts 429ing after ~3000 requests at 8 concurrent
MATCH_RADIUS_MI = 5.0  # reject suggestions farther than this from source coord
REQUEST_TIMEOUT = 20.0
RETRY_ON_429 = 5  # retry attempts for 429 before giving up

# state_code in the API response is the full name. We compare against the
# 2-letter codes in our dataset, so build a full→abbrev map.
STATE_ABBREV = {
    "Alabama": "AL", "Alaska": "AK", "Arizona": "AZ", "Arkansas": "AR",
    "California": "CA", "Colorado": "CO", "Connecticut": "CT", "Delaware": "DE",
    "Florida": "FL", "Georgia": "GA", "Hawaii": "HI", "Idaho": "ID",
    "Illinois": "IL", "Indiana": "IN", "Iowa": "IA", "Kansas": "KS",
    "Kentucky": "KY", "Louisiana": "LA", "Maine": "ME", "Maryland": "MD",
    "Massachusetts": "MA", "Michigan": "MI", "Minnesota": "MN", "Mississippi": "MS",
    "Missouri": "MO", "Montana": "MT", "Nebraska": "NE", "Nevada": "NV",
    "New Hampshire": "NH", "New Jersey": "NJ", "New Mexico": "NM", "New York": "NY",
    "North Carolina": "NC", "North Dakota": "ND", "Ohio": "OH", "Oklahoma": "OK",
    "Oregon": "OR", "Pennsylvania": "PA", "Rhode Island": "RI", "South Carolina": "SC",
    "South Dakota": "SD", "Tennessee": "TN", "Texas": "TX", "Utah": "UT",
    "Vermont": "VT", "Virginia": "VA", "Washington": "WA", "West Virginia": "WV",
    "Wisconsin": "WI", "Wyoming": "WY", "District of Columbia": "DC",
    "Puerto Rico": "PR", "US Virgin Islands": "VI", "Guam": "GU",
}


def haversine_mi(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 3958.8  # earth radius, miles
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def query_variants(name: str) -> list[str]:
    """Names in uscampgrounds.info vs rec.gov aren't identical. Try a few
    stripped variants before giving up. Order matters — most-specific first."""
    variants = [name]
    # Strip a trailing qualifier after " - " (e.g. "Marion Creek - Dalton Hwy")
    if " - " in name:
        variants.append(name.split(" - ", 1)[0].strip())
    # Strip trailing " Campground/CG/NF/etc." suffixes
    for suf in (" Campground", " CG", " NF", " NP", " BLM"):
        if name.endswith(suf):
            variants.append(name[: -len(suf)].strip())
    # If name has parens, drop them: "Elk Creek (Upper)" → "Elk Creek"
    if "(" in name:
        import re as _re
        variants.append(_re.sub(r"\s*\([^)]*\)\s*", " ", name).strip())
    # Dedupe, preserve order
    seen = set()
    out = []
    for v in variants:
        if v and v not in seen:
            seen.add(v)
            out.append(v)
    return out


async def _query(session, q: str):
    """Fetch suggest results; retry 429 with exponential backoff."""
    qs = urllib.parse.urlencode({"q": q})
    wait = 2.0
    for attempt in range(RETRY_ON_429 + 1):
        async with session.get(f"{SUGGEST_URL}?{qs}", timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT)) as r:
            if r.status == 429:
                if attempt == RETRY_ON_429:
                    raise RuntimeError(f"http 429 after {RETRY_ON_429} retries")
                await asyncio.sleep(wait)
                wait = min(wait * 2, 60)
                continue
            if r.status != 200:
                raise RuntimeError(f"http {r.status}")
            return await r.json()


async def lookup(session, sem, idx: int, name: str, state: str, lon: float, lat: float) -> tuple[int, str | None, str]:
    """Return (idx, entity_id, reason). idx is threaded back so as_completed
    callers can match the result to the source feature. entity_id is None if
    no confident match."""
    async with sem:
        for variant in query_variants(name):
            try:
                data = await _query(session, variant)
            except RuntimeError as e:
                return idx, None, str(e)
            except Exception as e:
                return idx, None, f"net: {e.__class__.__name__}"

            candidates = []
            for s in data.get("inventory_suggestions") or []:
                if s.get("entity_type") != "campground":
                    continue
                s_state = STATE_ABBREV.get(s.get("state_code") or "")
                if state and s_state and s_state != state:
                    continue
                try:
                    s_lat = float(s["lat"])
                    s_lon = float(s["lng"])
                except (KeyError, TypeError, ValueError):
                    continue
                dist = haversine_mi(lat, lon, s_lat, s_lon)
                if dist <= MATCH_RADIUS_MI:
                    candidates.append((dist, s.get("entity_id"), s.get("name")))

            if candidates:
                candidates.sort()
                return idx, str(candidates[0][1]), f"{candidates[0][2]} @ {candidates[0][0]:.1f} mi"

        return idx, None, f"no campground match within {MATCH_RADIUS_MI} mi"


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true", help="re-query features that already have a recgov_id field")
    ap.add_argument("--limit", type=int, help="only process first N features (for testing)")
    args = ap.parse_args()

    data = json.loads(DATA.read_text())
    todo = []
    for i, f in enumerate(data["features"]):
        props = f["properties"]
        if props.get("category") != "federal":
            continue
        if not args.refresh and "recgov_id" in props:
            continue
        todo.append(i)
    if args.limit:
        todo = todo[: args.limit]
    print(f"to process: {len(todo)} / federal total {sum(1 for f in data['features'] if f['properties']['category'] == 'federal')}", file=sys.stderr)

    sem = asyncio.Semaphore(CONCURRENCY)
    hits = 0
    misses = 0
    errors = 0
    async with aiohttp.ClientSession(headers={"User-Agent": "Mozilla/5.0 (roadtrip-map enricher)"}) as session:
        tasks = []
        for i in todo:
            props = data["features"][i]["properties"]
            lon, lat = data["features"][i]["geometry"]["coordinates"]
            tasks.append(lookup(session, sem, i, props["name"], props.get("state", ""), lon, lat))

        for n, coro in enumerate(asyncio.as_completed(tasks), 1):
            i, eid, reason = await coro
            props = data["features"][i]["properties"]
            if eid:
                props["recgov_id"] = eid
                hits += 1
            elif reason.startswith(("net:", "http ")):
                # don't persist for transient errors — skip so a rerun retries
                errors += 1
            else:
                props["recgov_id"] = None
                misses += 1
            if n % 100 == 0 or n == len(todo):
                print(f"  [{n}/{len(todo)}] hits={hits} misses={misses} errs={errors}  last: {props['name']} → {reason}", file=sys.stderr)

    # Write in place, pretty-print to keep diffs reviewable.
    DATA.write_text(json.dumps(data))
    print(f"done. hits={hits} misses={misses} errors={errors}", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())
