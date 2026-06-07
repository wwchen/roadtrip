#!/usr/bin/env python3
"""Fetch Planet Fitness US locations from OpenStreetMap Overpass API, save as GeoJSON."""
import datetime as dt
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).parent.parent / "data" / "planet-fitness.geojson"
BBOX = "24.5,-125.0,49.5,-66.5"  # continental US + a bit

QUERY = f"""
[out:json][timeout:90];
nwr["brand"="Planet Fitness"]({BBOX});
out center tags;
"""


def main():
    url = "https://overpass-api.de/api/interpreter"
    data = urllib.parse.urlencode({"data": QUERY}).encode()
    req = urllib.request.Request(url, data=data, headers={
        "User-Agent": "roadtrip-map/0.1 (personal)",
    })
    print(f"fetching Overpass…", file=sys.stderr)
    with urllib.request.urlopen(req, timeout=120) as resp:
        payload = json.loads(resp.read())

    elements = payload.get("elements", [])
    print(f"  got {len(elements)} elements", file=sys.stderr)

    features = []
    for el in elements:
        if el.get("type") == "node":
            lon, lat = el.get("lon"), el.get("lat")
        else:
            c = el.get("center") or {}
            lon, lat = c.get("lon"), c.get("lat")
        if lon is None or lat is None:
            continue
        t = el.get("tags") or {}
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": {
                "osm_id": f"{el['type']}/{el['id']}",
                "name": t.get("name", "Planet Fitness"),
                "street": " ".join(x for x in [t.get("addr:housenumber"), t.get("addr:street")] if x),
                "city": t.get("addr:city", ""),
                "state": t.get("addr:state", ""),
                "postcode": t.get("addr:postcode", ""),
                "phone": t.get("phone", ""),
                "website": t.get("website", ""),
                "opening_hours": t.get("opening_hours", ""),
            },
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    # Stamp upstream-fetch time so the importer's pois.fetched_at reflects when
    # we actually called Overpass, not when this file was last touched on disk.
    fetched_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    OUT.write_text(json.dumps({"_fetched_at": fetched_at, "type": "FeatureCollection", "features": features}))
    print(f"wrote {len(features)} features to {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
