#!/usr/bin/env python3
"""Merge hand-curated Canadian campground lists into campgrounds.geojson.

Three sources, all hand-curated because no authoritative public points feed
exists for Canadian park campgrounds (Parks Canada's reservation API is
Azure-WAF-gated, Alberta's reserve.albertaparks.ca is a JS-rendered SPA):

  data/parks-canada-bc.json   federal sites in BC          → category=federal
  data/parks-canada-ab.json   federal sites in AB          → category=federal
  data/alberta-provincial.json AB provincial campgrounds   → category=provincial

Features carry:
  category  = 'federal' | 'provincial' (mirrors the US federal/state split)
  state     = province code (BC, AB)
  country   = 'CA'                    (US sites omit this; popup uses it to
                                       choose Parks Canada vs Recreation.gov)
  parks_canada_url / parks_alberta_url = direct link target for popup CTA

Code prefix per source so the merge can replace stale entries cleanly across
re-runs:  PC-BC-, PC-AB-, AP-AB-.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
OUT = ROOT / "data" / "campgrounds.geojson"

SOURCES = [
    {
        "path": ROOT / "data" / "parks-canada-bc.json",
        "code_prefix": "PC-BC-",
        "category": "federal",
        "state": "BC",
        "url_field": "parks_canada_url",
    },
    {
        "path": ROOT / "data" / "parks-canada-ab.json",
        "code_prefix": "PC-AB-",
        "category": "federal",
        "state": "AB",
        "url_field": "parks_canada_url",
    },
    {
        "path": ROOT / "data" / "alberta-provincial.json",
        "code_prefix": "AP-AB-",
        "category": "provincial",
        "state": "AB",
        "url_field": "parks_alberta_url",
    },
]


def to_feature(entry: dict, source: dict) -> dict:
    safe = entry["name"].replace(" ", "-").replace("'", "").replace(".", "")
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [entry["lon"], entry["lat"]]},
        "properties": {
            "code": f"{source['code_prefix']}{safe}",
            "name": entry["name"],
            "type": "NP" if source["category"] == "federal" else "SP",
            "typeLabel": entry["park"],
            "category": source["category"],
            "state": source["state"],
            "country": "CA",
            "phone": "",
            "season": entry.get("season", ""),
            "sites": None,
            "amenities": [],
            "near": "",
            "parent_name": entry["park"],
            source["url_field"]: entry.get("park_url", ""),
            "reservable": entry.get("reservable"),
        },
    }


def main():
    all_codes_replaced = []  # any entry whose code starts with one of these prefixes is dropped
    new_features = []
    per_source_counts = []
    for source in SOURCES:
        if not source["path"].exists():
            print(f"warn: {source['path']} missing — skipping", file=sys.stderr)
            continue
        doc = json.loads(source["path"].read_text())
        feats = [to_feature(e, source) for e in doc["campgrounds"]]
        new_features.extend(feats)
        all_codes_replaced.append(source["code_prefix"])
        per_source_counts.append((source["path"].name, len(feats)))

    existing = []
    if OUT.exists():
        doc = json.loads(OUT.read_text())
        for f in doc.get("features", []):
            code = str(f.get("properties", {}).get("code", ""))
            # Keep features whose code prefix is NOT being re-imported this run.
            if not any(code.startswith(prefix) for prefix in all_codes_replaced):
                existing.append(f)

    merged = existing + new_features
    OUT.write_text(json.dumps({"type": "FeatureCollection", "features": merged}))
    for name, count in per_source_counts:
        print(f"  {name}: {count}", file=sys.stderr)
    print(
        f"wrote {len(merged)} total features to {OUT} "
        f"(carried-over: {len(existing)}, curated: {len(new_features)})",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
