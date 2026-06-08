#!/usr/bin/env python3
"""Generic ArcGIS FeatureServer paginator.

Used by data_sources whose upstream is an ArcGIS REST FeatureServer layer
(Parks Canada APCA, BC Gov GIS, etc). Pages results 2000 at a time using
resultOffset / resultRecordCount until the server returns fewer records
than asked for.

Args (all from the data_source's fetcher.args in poi-registry.yaml):
  --slug              data_source slug (required; resolves output_dir_prefix)
  --url               base FeatureServer/<n> URL (required; we append /query)
  --where             optional ArcGIS WHERE clause; defaults to '1=1'
  --return_centroid   when true, requests centroids only (returnCentroid=
                      true&returnGeometry=false&outSR=4326). Use for big
                      polygon layers that overflow the 16MB response cap.

Multi-part output: data/raw/<slug>/<UTC-ts>/page-NNN.json. Each page is
the envelope-wrapped ArcGIS query response (geojson features when geometry
is requested, or attribute+centroid records when centroid mode is on).

Run:
  python3 scripts/fetch_arcgis_layer.py --slug apca-accommodation \\
    --url 'https://services2.arcgis.com/.../FeatureServer/0' \\
    --where "Accommodation_Type='Camping'"
"""
from __future__ import annotations

import argparse
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import (  # noqa: E402
    err,
    http_get_text,
    load_source,
    parse_payload,
    utc_ts,
    write_envelope,
)

PAGE_SIZE = 2000  # ArcGIS server default cap
FETCHER = "fetch_arcgis_layer"
FETCHER_VERSION = "1"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slug", required=True, help="data_source slug from poi-registry.yaml")
    parser.add_argument("--url", required=True, help="ArcGIS FeatureServer/<n> base URL")
    parser.add_argument("--where", default="1=1", help="ArcGIS WHERE clause; defaults to 1=1")
    parser.add_argument(
        "--return_centroid",
        default="false",
        help="When 'true', request centroids only (centroid + attributes, no full geometry)",
    )
    args = parser.parse_args()

    src = load_source(args.slug)
    centroid = args.return_centroid.lower() in ("true", "1", "yes")

    base_params = {
        "where": args.where,
        "outFields": "*",
        "f": "geojson" if not centroid else "json",
        "outSR": "4326",
        "resultRecordCount": str(PAGE_SIZE),
    }
    if centroid:
        # Centroid mode is the workaround for layers whose full polygon
        # geometry overflows the 16MB ArcGIS response cap. The response
        # carries `centroid: {x, y}` per feature in the f=json envelope.
        base_params["returnCentroid"] = "true"
        base_params["returnGeometry"] = "false"

    ts = utc_ts()
    pages_written = 0
    total_seen = 0
    offset = 0

    while True:
        params = dict(base_params)
        params["resultOffset"] = str(offset)
        url = f"{args.url.rstrip('/')}/query?{urllib.parse.urlencode(params)}"
        err(f"  page {pages_written + 1}: offset={offset}")
        try:
            status, resp_headers, body = http_get_text(url, timeout=120)
        except Exception as e:  # noqa: BLE001
            err(f"  page failed: {e}")
            return 1
        if status != 200:
            err(f"  HTTP {status}: {body[:200]}")
            return 1

        payload = parse_payload(resp_headers.get("content-type", ""), body)
        # geojson:  payload['features'] is the list
        # f=json:   payload['features'] is also the list
        records = payload.get("features") or []

        pages_written += 1
        write_envelope(
            source_obj=src,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=url,
            request_method="GET",
            request_headers={},
            response_status=status,
            response_headers=resp_headers,
            payload=payload,
            ts=ts,
            part=f"page-{pages_written:03d}",
        )

        total_seen += len(records)
        if len(records) < PAGE_SIZE:
            break
        offset += PAGE_SIZE

    err(f"  {args.slug}: {pages_written} pages, {total_seen} features (ts={ts})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
