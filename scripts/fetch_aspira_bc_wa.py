#!/usr/bin/env python3
"""One-shot: stamp `aspira` blocks onto BC provincial + WA state-park features
in data/campgrounds.geojson, mirroring scripts/fetch_parks_canada_aspira.py
for Parks Canada.

BC's reservation site (discovercamping.ca) redirects to camping.bcparks.ca,
which is the same Aspira NextGen `/api/maps` shape Parks Canada uses. WA's
washington.goingtocamp.com is the same. Both APIs are public (no auth).

The trees are nested: each region (`mapType=1`) holds `mapLinks` for the
parks within it, and only entries whose `transactionLocationId != null` are
real bookable destinations. We walk both maps endpoints, build a
{normalized_park_name: (transactionLocationId, mapId, title)} index, then
match against the in-place geojson.

Run:
  python3 scripts/fetch_aspira_bc_wa.py

Re-run only when Aspira renumbers (rare). The augmented geojson ships with
the repo; nothing else has to change.
"""
from __future__ import annotations
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).parent.parent
GEOJSON = ROOT / "data" / "campgrounds.geojson"

UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)

# Each source: (host, geojson_filter, label).
# `host` is the Aspira-deployed booking domain; the FE writes it to
# `aspira.host` so the backend route can target the right WAF.
# `geojson_filter` selects the campground features eligible for stamping.
SOURCES = [
    {
        "host": "camping.bcparks.ca",
        "label": "BC provincial",
        "predicate": lambda p: p.get("category") == "provincial" and p.get("state") == "BC",
    },
    {
        "host": "washington.goingtocamp.com",
        "label": "WA state",
        "predicate": lambda p: p.get("category") == "state" and p.get("state") == "WA",
    },
]


def fetch_maps(host: str) -> list:
    url = f"https://{host}/api/maps"
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "application/json",
            "Referer": f"https://{host}/",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def en_title(node) -> str:
    """Pull en-* title from a top-level node (localizedValues) or mapLink (localizations)."""
    for key in ("localizedValues", "localizations"):
        for entry in node.get(key) or []:
            if (entry.get("cultureName") or "").lower().startswith("en"):
                return entry.get("title") or ""
    return ""


def normalize(name: str) -> str:
    """Reduce 'Garibaldi Provincial Park' / 'Garibaldi' / 'Mt. Robson' to a stable
    key. Drops Provincial/State Park / Recreation Area / Marine suffixes,
    Mt./Mount alternation, lowercases, strips punctuation.
    """
    s = name.lower()
    # Replace common Mt./Mount variants so 'Mt. Robson' matches 'Mount Robson'.
    s = s.replace("mt.", "mount").replace("mt ", "mount ")
    for suffix in [
        " provincial park",
        " state park",
        " recreation area",
        " conservation area",
        " marine park",
        " park",  # last so it doesn't shadow the others
    ]:
        if s.endswith(suffix):
            s = s[: -len(suffix)]
    return "".join(c for c in s if c.isalnum())


def park_index(maps: list) -> dict[str, dict]:
    """{normalized_name: {transactionLocationId, mapId, title}}.
    We walk every node's `mapLinks` and only keep entries with a non-null
    `transactionLocationId` — those are actual bookable parks (regions are
    transactionLocationId=null).
    """
    idx: dict[str, dict] = {}
    for node in maps:
        for link in node.get("mapLinks") or []:
            if link.get("transactionLocationId") is None:
                continue
            title = en_title(link)
            if not title:
                continue
            key = normalize(title)
            if not key:
                continue
            existing = idx.get(key)
            if existing is None or len(title) < len(existing["title"]):
                idx[key] = {
                    "title": title,
                    "transactionLocationId": link["transactionLocationId"],
                    "mapId": link["childMapId"],
                    # Aspira's deeplink is much sturdier when this is set: WA's
                    # site's results-page redirect logic checks for it.
                    "resourceLocationId": link.get("resourceLocationId"),
                }
    return idx


def stamp_features(features: list, source: dict, idx: dict[str, dict]) -> tuple[int, int, list[str]]:
    matched = 0
    eligible = 0
    missed: list[str] = []
    for f in features:
        p = f.get("properties") or {}
        if not source["predicate"](p):
            continue
        eligible += 1
        name = p.get("name") or ""
        key = normalize(name)
        hit = idx.get(key)
        if hit is None:
            missed.append(name)
            continue
        p["aspira"] = {
            "host": source["host"],
            "transactionLocationId": hit["transactionLocationId"],
            "mapId": hit["mapId"],
            "park_title": hit["title"],
        }
        if hit.get("resourceLocationId") is not None:
            p["aspira"]["resourceLocationId"] = hit["resourceLocationId"]
        matched += 1
    return matched, eligible, missed


def main():
    if not GEOJSON.exists():
        print(f"error: {GEOJSON} missing", file=sys.stderr)
        sys.exit(1)
    doc = json.loads(GEOJSON.read_text())
    feats = doc.get("features") or []

    for source in SOURCES:
        print(f"fetching https://{source['host']}/api/maps…", file=sys.stderr)
        maps = fetch_maps(source["host"])
        idx = park_index(maps)
        matched, eligible, missed = stamp_features(feats, source, idx)
        print(
            f"  {source['label']}: {matched}/{eligible} matched (index size: {len(idx)})",
            file=sys.stderr,
        )
        if missed:
            sample = sorted(set(missed))[:8]
            print(f"  ⚠ unmatched (showing 8/{len(set(missed))}): {sample}", file=sys.stderr)

    GEOJSON.write_text(json.dumps(doc))
    print(f"wrote {GEOJSON}", file=sys.stderr)


if __name__ == "__main__":
    main()
