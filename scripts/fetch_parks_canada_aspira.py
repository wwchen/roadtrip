#!/usr/bin/env python3
"""One-shot: fetch reservation.pc.gc.ca's /api/maps and stamp the per-park
Aspira IDs into data/parks-canada-{bc,ab}.json.

The Reserve button on Parks Canada campground pins (web/campground-card.js)
deeplinks to https://reservation.pc.gc.ca/create-booking/results?... which
needs `transactionLocationId` and `mapId` per park. The /api/maps endpoint
is public (no auth, no session) and returns the full hierarchy; we walk
the top-level mapType=2 entries (parks) and join on title.

After running once, re-run only when Aspira renumbers (rare; IDs are
stable across years per RFC 0006). The augmented JSON ships with the repo;
the frontend reads it through the existing /api/pois pipeline.

Run:
  python3 scripts/fetch_parks_canada_aspira.py
"""
from __future__ import annotations
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).parent.parent
MAPS_URL = "https://reservation.pc.gc.ca/api/maps"
DATA_FILES = [ROOT / "data" / "parks-canada-bc.json", ROOT / "data" / "parks-canada-ab.json"]
HOST = "reservation.pc.gc.ca"

# Aspira /api/maps is fronted by an Akamai-style WAF that rejects bare
# urllib's User-Agent. Use a stock Chrome UA.
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"


def en_title(node) -> str:
    """Pull en-CA title from a top-level node (localizedValues) or mapLink (localizations)."""
    for key in ("localizedValues", "localizations"):
        for entry in node.get(key) or []:
            if (entry.get("cultureName") or "").lower().startswith("en"):
                return entry.get("title") or ""
    return ""


def normalize(name: str) -> str:
    """Reduce 'Banff National Park' / 'Banff' / 'Mount Revelstoke' to a stable key.
    Drops National Park / Provincial Park / NPR suffixes; lowercases; strips
    spaces and punctuation that vary between curated files and Aspira titles.
    """
    s = name.lower()
    for suffix in [
        " national park reserve",
        " national park",
        " national historic site",
        " provincial park",
    ]:
        if s.endswith(suffix):
            s = s[: -len(suffix)]
    return "".join(c for c in s if c.isalnum())


def fetch_maps() -> list:
    req = urllib.request.Request(MAPS_URL, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def park_index(maps: list) -> dict[str, dict]:
    """Build {normalized_name: {transactionLocationId, mapId, title}}.
    mapType=2 entries are individual parks; mapType=1 are regional groupings
    we skip (they aren't bookable destinations).
    """
    idx: dict[str, dict] = {}
    for node in maps:
        if node.get("mapType") != 2:
            continue
        title = en_title(node)
        if not title:
            continue
        key = normalize(title)
        if not key:
            continue
        # Some parks appear twice (e.g. Banff vs Banff - Lake Louise). Prefer
        # the shorter title for ambiguity — it matches our curated park names.
        existing = idx.get(key)
        if existing is None or len(title) < len(existing["title"]):
            idx[key] = {
                "title": title,
                "transactionLocationId": node.get("transactionLocationId"),
                "mapId": node.get("mapId"),
            }
    return idx


def augment_file(path: Path, idx: dict[str, dict]) -> tuple[int, int]:
    doc = json.loads(path.read_text())
    cgs = doc.get("campgrounds") or []
    matched = 0
    missed: list[str] = []
    for cg in cgs:
        park = cg.get("park") or ""
        key = normalize(park)
        hit = idx.get(key)
        if hit and hit.get("transactionLocationId") is not None and hit.get("mapId") is not None:
            cg["aspira"] = {
                "host": HOST,
                "transactionLocationId": hit["transactionLocationId"],
                "mapId": hit["mapId"],
                "park_title": hit["title"],
            }
            matched += 1
        else:
            missed.append(park)
    if missed:
        print(f"  ⚠ unmatched parks in {path.name}: {sorted(set(missed))}", file=sys.stderr)
    path.write_text(json.dumps(doc, indent=2) + "\n")
    return matched, len(cgs)


def main():
    print(f"fetching {MAPS_URL}…", file=sys.stderr)
    maps = fetch_maps()
    idx = park_index(maps)
    print(f"  parks indexed: {len(idx)} (sample: {sorted(idx.keys())[:5]})", file=sys.stderr)
    for path in DATA_FILES:
        matched, total = augment_file(path, idx)
        print(f"  {path.name}: {matched}/{total} campgrounds matched", file=sys.stderr)


if __name__ == "__main__":
    main()
