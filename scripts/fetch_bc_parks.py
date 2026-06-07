#!/usr/bin/env python3
"""Fetch BC Parks (British Columbia provincial parks) that have camping,
merging them into data/campgrounds.geojson alongside the USCampgrounds.info
seed. Uses the public BC Parks Strapi API at bcparks.api.gov.bc.ca (no key).

One feature per park (at the park's lat/lng). We don't unpack sub-areas into
separate features because they share the park's coordinate — a single dot on
the map is the right UX, and the sub-area list is available via the bcparks.ca
link in the popup.

Fields written per feature (matches the main campground schema):
  code              — orcs number (park-unique id)
  name              — protectedAreaName
  type              — typeCode (PK, ER, CP, RA, ...)
  typeLabel         — "Provincial Park", "Ecological Reserve", ...
  category          — "provincial"  (complements federal/state/local)
  state             — "BC"
  phone             — '' (not available)
  season            — '' (varies by sub-area; link shows details)
  sites             — None (not a single number per park)
  amenities         — list[str] from parkFacilities
  activities        — list[str] from parkActivities
  near              — ''
  recgov_id         — None (Canadian)
  parent_name       — ''  (BC Parks is flat)
  photo_url         — first featured or first active parkPhoto imageUrl
  bcparks_url       — https://bcparks.ca/<slug>/  (popup link)

Reservations in BC go through Discover Camping / Parks Canada, a separate
system; we just link to the park page and let users click through.
"""
from __future__ import annotations
import datetime as dt
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).parent.parent / "data" / "campgrounds.geojson"

API = "https://bcparks.api.gov.bc.ca/api/protected-areas"
PAGE_SIZE = 100
# Ask Strapi to populate the related tables we need so we don't do N+1 fetches.
POPULATE = [
    "parkPhotos",
    "parkActivities.activityType",
    "parkCampingTypes.campingType",
    "parkFacilities.facilityType",
]

# BC Parks' typeCode → human label (inferred from the catalogue; extend if new codes appear).
TYPE_LABELS = {
    "PK": "Provincial Park",
    "ER": "Ecological Reserve",
    "CP": "Conservancy",
    "RA": "Recreation Area",
    "PP": "Protected Area",
}


def build_url(page: int) -> str:
    params = [
        ("pagination[page]", str(page)),
        ("pagination[pageSize]", str(PAGE_SIZE)),
        # Only include parks that actually have camping declared.
        ("filters[parkCampingTypes][id][$notNull]", "true"),
        ("filters[legalStatus][$eq]", "Active"),
        ("filters[isDisplayed][$eq]", "true"),
    ]
    for p in POPULATE:
        params.append(("populate[]", p))
    return f"{API}?{urllib.parse.urlencode(params)}"


def fetch_page(page: int) -> dict:
    url = build_url(page)
    print(f"  fetching page {page}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.loads(r.read().decode())


def pick_photo(photos: list) -> str | None:
    photos = [p for p in (photos or []) if p.get("isActive") and p.get("imageUrl")]
    if not photos:
        return None
    featured = [p for p in photos if p.get("isFeatured")]
    chosen = featured[0] if featured else photos[0]
    return chosen["imageUrl"]


def to_feature(p: dict) -> dict | None:
    lat = p.get("latitude")
    lon = p.get("longitude")
    if lat is None or lon is None:
        return None

    acts = sorted({
        (a.get("activityType") or {}).get("activityName")
        for a in (p.get("parkActivities") or [])
        if a.get("activityType")
    } - {None})
    amens = sorted({
        (f.get("facilityType") or {}).get("facilityName")
        for f in (p.get("parkFacilities") or [])
        if f.get("facilityType")
    } - {None})

    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [lon, lat]},
        "properties": {
            "code": f"BC-{p.get('orcs')}",
            "name": p.get("protectedAreaName") or "Unnamed",
            "type": p.get("typeCode") or "",
            "typeLabel": TYPE_LABELS.get(p.get("typeCode") or "", "Provincial Park"),
            "category": "provincial",
            "state": "BC",
            "phone": "",
            "season": "",
            "sites": None,
            "amenities": amens,
            "activities": acts or None,
            "near": "",
            "photo_url": pick_photo(p.get("parkPhotos")),
            "bcparks_url": p.get("url") or "",
        },
    }


def main():
    features = []
    page = 1
    while True:
        d = fetch_page(page)
        for p in d.get("data", []):
            feat = to_feature(p)
            if feat:
                features.append(feat)
        meta = (d.get("meta") or {}).get("pagination") or {}
        if page >= meta.get("pageCount", page):
            break
        page += 1
    print(f"BC Parks with camping: {len(features)}", file=sys.stderr)

    # Merge: replace any prior BC entries (by properties.code prefix) and keep
    # everything else. This lets the script be rerun without losing US data.
    existing = []
    if OUT.exists():
        doc = json.loads(OUT.read_text())
        existing = [f for f in doc.get("features", [])
                    if not str(f.get("properties", {}).get("code", "")).startswith("BC-")]

    merged = existing + features
    OUT.parent.mkdir(parents=True, exist_ok=True)
    # Stamp our just-fetched-from-BC-Parks time on the merged file. The
    # importer reads this for pois.fetched_at; the timestamp documents when
    # THIS script's data was pulled, even if US-side rows in the same file
    # are older.
    fetched_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    OUT.write_text(json.dumps({"_fetched_at": fetched_at, "type": "FeatureCollection", "features": merged}))
    print(f"wrote {len(merged)} total features to {OUT} "
          f"(US: {len(existing)}, BC: {len(features)})", file=sys.stderr)


if __name__ == "__main__":
    main()
