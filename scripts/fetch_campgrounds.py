#!/usr/bin/env python3
"""Fetch US public campgrounds from uscampgrounds.info, save as GeoJSON.

Source: https://uscampgrounds.info/takeit.html — CC-BY licensed CSVs, no key.
Schema is documented at https://uscampgrounds.info/abbreviations.html
"""
import csv
import datetime as dt
import io
import json
import sys
import urllib.request
from pathlib import Path

OUT = Path(__file__).parent.parent / "data" / "campgrounds.geojson"
REGIONS = ["WestCamp", "SouthwestCamp", "MidwestCamp", "NortheastCamp", "SouthCamp"]

# Agency/type codes → (category, display label). Category drives color/filter.
TYPE_LABELS = {
    "NP":  ("federal",  "National Park"),
    "NF":  ("federal",  "National Forest"),
    "NM":  ("federal",  "National Monument"),
    "NRA": ("federal",  "National Rec Area"),
    "BLM": ("federal",  "BLM"),
    "COE": ("federal",  "Army Corps"),
    "USFW":("federal",  "US Fish & Wildlife"),
    "BOR": ("federal",  "Bureau of Reclamation"),
    "TVA": ("federal",  "TVA"),
    "SP":  ("state",    "State Park"),
    "SRA": ("state",    "State Rec Area"),
    "SF":  ("state",    "State Forest"),
    "SFW": ("state",    "State Fish & Wildlife"),
    "SB":  ("state",    "State Beach"),
    "PP":  ("state",    "Provincial Park"),  # Canadian; harmless to include
    "CP":  ("local",    "County Park"),
    "RP":  ("local",    "Regional Park"),
    "MP":  ("local",    "Municipal Park"),
    "UTIL":("local",    "Utility"),
    "PR":  ("private",  "Private"),
}

# Amenity code → friendly label. Value present in the AMEN: field means "has this".
AMENITIES = {
    "FT": "flush toilets",
    "VT": "vault toilets",
    "PT": "pit toilets",
    "NT": "no toilets",
    "DW": "drinking water",
    "NW": "no water",
    "SH": "showers",
    "DS": "dump station",
    "EL": "electric hookup",
    "WA": "water hookup",
    "SE": "sewer hookup",
    "LA": "laundry",
    "NR": "no reservations",
    "RQ": "reservations required",
    "FE": "fee",
    "NF": "no fee",
    "NH": "no hookups",
    "HA": "handicap access",
}


def parse_amenities(raw: str) -> list[str]:
    tokens = (raw or "").strip().split()
    labels = []
    for t in tokens:
        t = t.strip().rstrip(",")
        if t in AMENITIES:
            labels.append(AMENITIES[t])
        elif t.endswith("ft") and t[:-2].isdigit():
            labels.append(f"max {t} RV")
        elif t.isdigit():
            labels.append(f"${t}")
    return labels


def main():
    features = []
    seen = set()  # (round lat, round lon) to dedupe cross-region overlaps
    for region in REGIONS:
        url = f"https://uscampgrounds.info/POI/{region}.csv"
        print(f"fetching {url}", file=sys.stderr)
        with urllib.request.urlopen(url, timeout=60) as resp:
            text = resp.read().decode("utf-8", errors="replace")
        reader = csv.reader(io.StringIO(text))
        count_before = len(features)
        for row in reader:
            if len(row) < 16:
                continue
            try:
                lon, lat = float(row[0]), float(row[1])
            except ValueError:
                continue
            code = row[3].strip()
            name = row[4].strip()
            type_code = row[5].strip()
            phone = row[6].strip()
            season = row[7].strip() if row[7].strip() else ""
            sites = row[9].strip()
            amen_raw = row[11].strip()
            state = row[12].strip()
            dist = row[13].strip()
            direction = row[14].strip()
            town = row[15].strip()

            key = (round(lat, 4), round(lon, 4))
            if key in seen:
                continue
            seen.add(key)

            cat, label = TYPE_LABELS.get(type_code, ("other", type_code or "Campground"))
            near = ""
            if town:
                parts = []
                if dist:
                    parts.append(f"{dist} mi")
                if direction:
                    parts.append(direction)
                parts.append(f"of {town}")
                near = " ".join(parts)

            features.append({
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": {
                    "code": code,
                    "name": name,
                    "type": type_code,
                    "typeLabel": label,
                    "category": cat,
                    "state": state,
                    "phone": phone,
                    "season": season,
                    "sites": int(sites) if sites.isdigit() else None,
                    "amenities": parse_amenities(amen_raw),
                    "near": near,
                },
            })
        print(f"  +{len(features) - count_before} (total {len(features)})", file=sys.stderr)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    fetched_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    OUT.write_text(json.dumps({"_fetched_at": fetched_at, "type": "FeatureCollection", "features": features}))
    print(f"wrote {len(features)} features to {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
