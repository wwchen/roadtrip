#!/usr/bin/env python3
"""Merge the hand-curated Parks Canada BC campground list into campgrounds.geojson.

data/parks-canada-bc.json is the authoritative source here — Parks Canada
does not publish a clean points dataset for campgrounds, and the reservation
API is Azure-WAF-gated for scripted access. Coordinates are either OSM-
verified or approximate park-area centers; data_accuracy review welcome.

Features are tagged:
  category      = 'federal'           (consistent with US federal campgrounds)
  state         = 'BC'
  country       = 'CA'                (new field; US federal sites omit it)
  parks_canada_url = the park's /pn-np/bc/<slug> URL for direct popup link
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
SOURCE = ROOT / "data" / "parks-canada-bc.json"
OUT = ROOT / "data" / "campgrounds.geojson"


def to_feature(entry: dict) -> dict:
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [entry["lon"], entry["lat"]]},
        "properties": {
            "code": f"PC-{entry['name'].replace(' ', '-').replace(chr(39), '')}",
            "name": entry["name"],
            "type": "NP",
            "typeLabel": entry["park"],
            "category": "federal",
            "state": "BC",
            "country": "CA",
            "phone": "",
            "season": entry.get("season", ""),
            "sites": None,
            "amenities": [],
            "near": "",
            "parent_name": entry["park"],
            "parks_canada_url": entry.get("park_url", ""),
            "reservable": entry.get("reservable"),
        },
    }


def main():
    if not SOURCE.exists():
        print(f"error: {SOURCE} missing", file=sys.stderr)
        sys.exit(1)
    source = json.loads(SOURCE.read_text())
    features = [to_feature(e) for e in source["campgrounds"]]
    print(f"Parks Canada BC campgrounds: {len(features)}", file=sys.stderr)

    # Replace any prior PC entries, keep everything else.
    existing = []
    if OUT.exists():
        doc = json.loads(OUT.read_text())
        existing = [f for f in doc.get("features", [])
                    if not str(f.get("properties", {}).get("code", "")).startswith("PC-")]
    merged = existing + features
    OUT.write_text(json.dumps({"type": "FeatureCollection", "features": merged}))
    print(f"wrote {len(merged)} total features to {OUT} "
          f"(non-PC: {len(existing)}, PC-BC: {len(features)})", file=sys.stderr)


if __name__ == "__main__":
    main()
