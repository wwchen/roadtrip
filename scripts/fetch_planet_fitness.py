#!/usr/bin/env python3
"""Capture Planet Fitness US locations from OSM Overpass.

Thin fetch-only per RFC 0007. Writes one envelope-wrapped raw capture
under data/raw/osm-pf/<UTC-ts>.json. Kotlin ETL parses + transforms.

Run:
  python3 scripts/fetch_planet_fitness.py
"""
from __future__ import annotations

import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    err,
    http_post_text,
    load_source,
    parse_payload,
    write_envelope,
)

SLUG = "osm-pf"
SOURCE = load_source(SLUG)

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
BBOX = "24.5,-125.0,49.5,-66.5"  # continental US + a bit
QUERY = f"""
[out:json][timeout:90];
nwr["brand"="Planet Fitness"]({BBOX});
out center tags;
"""

UA = "roadtrip-map/0.1 (personal)"
FETCHER = "fetch_planet_fitness"
FETCHER_VERSION = "2"  # v1 was the merge-into-geojson era


def main() -> int:
    err("fetching OSM Overpass…")
    headers = {"User-Agent": UA}
    body_bytes = urllib.parse.urlencode({"data": QUERY}).encode()
    try:
        status, resp_headers, body = http_post_text(
            OVERPASS_URL, data=body_bytes, headers=headers, timeout=120
        )
    except Exception as e:  # noqa: BLE001
        err(f"  fetch failed: {e}")
        return 1

    payload = parse_payload(resp_headers.get("content-type", ""), body)
    out = write_envelope(
        source_obj=SOURCE,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=OVERPASS_URL,
        request_method="POST",
        request_headers=headers,
        response_status=status,
        response_headers=resp_headers,
        payload=payload,
    )
    n = len(payload.get("elements", [])) if isinstance(payload, dict) else 0
    err(f"  wrote {out} ({n} elements)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
