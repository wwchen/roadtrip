#!/usr/bin/env python3
"""Fetch National Park + State Park polygons from USGS PAD-US FeatureServer.

ArcGIS REST has a per-query record cap (usually 2000), so we paginate with
resultOffset. We also request simplified geometry (maxAllowableOffset in degrees)
to keep file sizes down — parks at continental-US zoom don't need cm precision.
"""
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

SVC = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/Manager_Name_PADUS/FeatureServer/0"
OUT_DIR = Path(__file__).parent.parent / "data"
FIELDS = "Unit_Nm,Loc_Nm,State_Nm,Mang_Name,Des_Tp,GIS_Acres"

# maxAllowableOffset in degrees (WGS84). 0.001° ≈ 111m at equator. Good enough for a map
# that rarely zooms below city level; drop to 0.0005 if outlines look too blocky.
SIMPLIFY = 0.001


def query_all(where: str, out_path: Path, page: int = 1000):
    all_features = []
    offset = 0
    while True:
        params = {
            "where": where,
            "outFields": FIELDS,
            "f": "geojson",
            "returnGeometry": "true",
            "geometryPrecision": 5,
            "maxAllowableOffset": SIMPLIFY,
            "outSR": 4326,
            "resultOffset": offset,
            "resultRecordCount": page,
        }
        url = SVC + "/query?" + urllib.parse.urlencode(params)
        print(f"  fetch offset={offset}", file=sys.stderr)
        with urllib.request.urlopen(url, timeout=120) as resp:
            data = json.loads(resp.read())
        feats = data.get("features", [])
        if not feats:
            break
        all_features.extend(feats)
        if len(feats) < page:
            break
        offset += page
        time.sleep(0.2)

    fc = {"type": "FeatureCollection", "features": all_features}
    out_path.write_text(json.dumps(fc))
    print(f"  wrote {len(all_features)} features to {out_path.name}", file=sys.stderr)
    return len(all_features)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    print("National Parks (Des_Tp='NP')…", file=sys.stderr)
    query_all("Des_Tp='NP'", OUT_DIR / "national-parks.geojson")

    print("State Parks (Des_Tp='SP' AND Mang_Type='STAT')…", file=sys.stderr)
    query_all("Des_Tp='SP' AND Mang_Type='STAT'", OUT_DIR / "state-parks.geojson")


if __name__ == "__main__":
    main()
