#!/usr/bin/env python3
"""Build data/tesla-superchargers.geojson from Tesla's official feeds.

Two-stage pipeline:
  1. Read the bulk locations file (data/tesla-locations-us.json — Tesla's
     get-locations endpoint dumps the entire global feed in there, despite
     the country=US param). Filter to North America by bbox.
  2. For each pin, hit get-charger-details via the same path server.py uses
     for live pricing. Cache to data/pricing-cache/<slug>.json (30-day TTL,
     resumed across runs). Sleeps SLEEP_SECONDS between calls so Akamai
     doesn't escalate; tune RATE if needed.

The output GeoJSON mirrors the property shape index.html's SC layer expects
(name, status, color, locationId, stallCount, powerKilowatt, address bits)
so the layer's existing color/filter logic stays unchanged.

Run:
  python3 scripts/fetch_tesla_superchargers.py            # full run
  python3 scripts/fetch_tesla_superchargers.py --limit 5  # first 5 only
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))

# Reuse the cookie-loading + curl-impersonate fetch from server.py — same
# Akamai-evasion machinery the live pricing endpoint uses.
import server  # noqa: E402

NA_BBOX = (14, 72, -180, -52)  # min_lat, max_lat, min_lng, max_lng — NA + Caribbean
LOCATIONS_FILE = ROOT / "data" / "tesla-locations-us.json"
LOCATIONS_TTL_SECONDS = 7 * 24 * 3600  # 1 week
OUT_FILE = ROOT / "data" / "tesla-superchargers.geojson"
SLEEP_SECONDS = 1.0
# When the cache is fresh and complete, reuse it. Network only fills gaps.

# Map Tesla site_status → (legend label, legend dot color). The colors
# match the dots in index.html's legend (#e82127 red for Open, #f5a623
# orange for Construction, #f7d56e yellow for Permit, #bfbfbf grey for
# Planned, #333 dark for Closed). Keep these in sync with the
# .legend-dot styles around index.html line 222.
STATUS_COLOR = {
    "open":         ("OPEN",         "#e82127"),
    "open_winner":  ("OPEN",         "#e82127"),
    "coming_soon":  ("CONSTRUCTION", "#f5a623"),
    "construction": ("CONSTRUCTION", "#f5a623"),
    "permit":       ("PERMIT",       "#f7d56e"),
    "planned":      ("PLAN",         "#bfbfbf"),
    "closed":       ("CLOSED_TEMP",  "#333333"),
}


def fetch_locations_bulk():
    """Refresh data/tesla-locations-us.json from get-locations?country=US&view=map.
    Despite the country=US param, Tesla returns the entire global feed (we
    filter by NA_BBOX after). Uses the same curl-impersonate / Akamai-cookie
    machinery as fetch_tesla_pricing — same rate limit, same cookie burn signal.
    """
    cookies = server.get_tesla_cookies()
    if not cookies:
        raise RuntimeError("No Tesla cookies (cookie-bot and TESLA_COOKIES both empty)")
    curl_bin = os.environ.get("TESLA_CURL", "curl_safari15_5")
    url = "https://www.tesla.com/api/findus/get-locations?country=US&view=map"
    cmd = [
        curl_bin, "-sS", "-w", "\n__HTTP_STATUS__%{http_code}", url,
        "-H", "accept: application/json, text/plain, */*",
        "-b", cookies,
        "-H", "priority: u=1, i",
        "-H", "referer: https://www.tesla.com/findus",
        "-H", "sec-fetch-dest: empty",
        "-H", "sec-fetch-mode: cors",
        "-H", "sec-fetch-site: same-origin",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    out = result.stdout
    marker = "\n__HTTP_STATUS__"
    if marker not in out:
        raise RuntimeError(f"unexpected curl output (no status marker): {out[:300]}")
    body, _, status_str = out.rpartition(marker)
    status = int(status_str.strip())
    if status != 200:
        raise RuntimeError(f"get-locations HTTP {status}: {body[:300]}")
    LOCATIONS_FILE.parent.mkdir(parents=True, exist_ok=True)
    LOCATIONS_FILE.write_text(body)
    size_mb = LOCATIONS_FILE.stat().st_size / 1024 / 1024
    print(f"  refreshed {LOCATIONS_FILE.name} ({size_mb:.1f} MB)", file=sys.stderr)


def load_pins():
    if not LOCATIONS_FILE.exists():
        print("  bulk feed missing, fetching…", file=sys.stderr)
        fetch_locations_bulk()
    else:
        age = time.time() - LOCATIONS_FILE.stat().st_mtime
        if age > LOCATIONS_TTL_SECONDS:
            print(f"  bulk feed stale ({int(age/86400)}d > {LOCATIONS_TTL_SECONDS//86400}d), refreshing…", file=sys.stderr)
            fetch_locations_bulk()
    with LOCATIONS_FILE.open() as f:
        d = json.load(f)
    items = d["data"]["data"]
    pins = []
    for item in items:
        types = item.get("location_type") or []
        if "supercharger" not in types and "coming_soon_supercharger" not in types:
            continue
        lat = item.get("latitude")
        lng = item.get("longitude")
        if lat is None or lng is None:
            continue
        if not (NA_BBOX[0] < lat < NA_BBOX[1] and NA_BBOX[2] < lng < NA_BBOX[3]):
            continue
        slug = item.get("location_url_slug")
        if not slug:
            continue
        sf = item.get("supercharger_function") or {}
        # show_on_find_us=='0' is internal/test data — skip.
        if sf.get("show_on_find_us") == "0":
            continue
        site_status = sf.get("site_status") or ("coming_soon" if "coming_soon_supercharger" in types else "open")
        pins.append({
            "slug": slug,
            "lat": lat,
            "lng": lng,
            "site_status": site_status,
            "uuid": item.get("uuid"),
        })
    return pins


def cached_detail(slug):
    cache_file = server.CACHE_DIR / f"{slug}.json"
    if not cache_file.exists():
        return None
    try:
        return json.loads(cache_file.read_text())
    except json.JSONDecodeError:
        return None


def fetch_detail(slug):
    """Returns dict on success, None on failure (logged). Polite 1 req/sec."""
    cached = cached_detail(slug)
    if cached:
        return cached
    status, data = server.fetch_tesla_pricing(slug)
    if status == 200 and isinstance(data, dict):
        server.CACHE_DIR.mkdir(parents=True, exist_ok=True)
        (server.CACHE_DIR / f"{slug}.json").write_text(json.dumps(data, indent=2))
        return data
    err = data.get("error") if isinstance(data, dict) else str(data)
    print(f"  fail {slug}: HTTP {status} — {err}", file=sys.stderr)
    return None


def detail_to_props(slug, lat, lng, site_status, detail):
    """Project Tesla's get-charger-details payload into the same property
    shape index.html's SC layer expects. supercharge.info gave us
    {name, address.{street,city,state}, status, color, stallCount,
     powerKilowatt, locationId, v2/v3/v4, nacs, tpc, dateOpened, color}.
    Tesla detail gives most of those except the V2/V3/V4 split (only
    maxPowerKw and publicStallCount); we leave plug-type fields blank
    so the popup just hides those rows.
    """
    status_label, color = STATUS_COLOR.get(site_status, ("OPEN", "#e82127"))
    if detail is None:
        return {
            "id": slug,
            "name": slug,
            "locationId": slug,
            "status": status_label,
            "color": color,
            "group": status_label.lower().replace("_temp", "").replace("_perm", ""),
            "street": "",
            "city": "",
            "state": "",
            "facility": "",
            "stallCount": None,
            "powerKilowatt": None,
        }
    inner = (detail.get("data") or {}).get("data") or detail.get("data") or {}
    addr = inner.get("address") or {}
    street_parts = [addr.get("streetNumber"), addr.get("street")]
    street = " ".join(p for p in street_parts if p) or ""
    return {
        "id": slug,
        "name": inner.get("name") or slug,
        "locationId": slug,
        "status": status_label,
        "color": color,
        "group": status_label.lower().replace("_temp", "").replace("_perm", ""),
        "street": street,
        "city": addr.get("city") or "",
        "state": addr.get("state") or "",
        "country": addr.get("country") or "",
        "facility": inner.get("facilityName") or "",
        "stallCount": inner.get("publicStallCount"),
        "powerKilowatt": inner.get("maxPowerKw"),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=None,
                        help="only process first N pins (smoke test)")
    parser.add_argument("--no-fetch", action="store_true",
                        help="cache-only — don't hit Tesla, use whatever's cached + leave the rest unenriched")
    args = parser.parse_args()

    server.load_env()
    pins = load_pins()
    if args.limit:
        pins = pins[:args.limit]
    print(f"loaded {len(pins)} NA Supercharger pins", file=sys.stderr)
    cached_count = sum(1 for p in pins if cached_detail(p["slug"]) is not None)
    print(f"  cache-hit: {cached_count}, to fetch: {len(pins)-cached_count}", file=sys.stderr)

    features = []
    fetched = 0
    failed = 0
    skipped = 0
    started = time.time()
    for i, pin in enumerate(pins, 1):
        slug = pin["slug"]
        had_cache = cached_detail(slug) is not None
        if args.no_fetch and not had_cache:
            detail = None
        else:
            detail = fetch_detail(slug)
        if detail is None and not had_cache:
            failed += 1
        elif not had_cache:
            fetched += 1
            time.sleep(SLEEP_SECONDS)

        # Skip pins we couldn't enrich AND have no cache for. Tesla returns 404
        # for a small set of private/decommissioned slugs; emitting a stub
        # feature for them puts a useless red dot on the map. The bulk feed
        # still has them, so they reappear on next refresh if Tesla revives.
        if detail is None:
            skipped += 1
            continue

        props = detail_to_props(slug, pin["lat"], pin["lng"], pin["site_status"], detail)
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [pin["lng"], pin["lat"]]},
            "properties": props,
        })

        if i % 50 == 0 or i == len(pins):
            elapsed = time.time() - started
            print(f"  {i}/{len(pins)}  fetched={fetched} failed={failed} skipped={skipped} ({elapsed:.0f}s)", file=sys.stderr)

    fc = {"type": "FeatureCollection", "features": features}
    OUT_FILE.write_text(json.dumps(fc))
    size_kb = OUT_FILE.stat().st_size / 1024
    print(f"\nwrote {len(features)} features → {OUT_FILE}  ({size_kb:.1f} KB)", file=sys.stderr)
    print(f"fetched: {fetched}  failed: {failed}  skipped: {skipped}  cache-hit: {cached_count}", file=sys.stderr)


if __name__ == "__main__":
    main()
