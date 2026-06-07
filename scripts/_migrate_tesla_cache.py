#!/usr/bin/env python3
"""One-shot migration: convert data/pricing-cache/<slug>.json files into
envelope-wrapped raw captures under data/raw/tesla-locations/<slug>/<ts>.json.

Idempotent: skips slugs that already have at least one capture under the new
layout. Source files are NOT deleted — we keep the old cache in place until
the new pipeline is proven, then a follow-up PR reaps it.

Run once before fetch_tesla_locations.py supersedes fetch_tesla_superchargers.py.
"""
from __future__ import annotations

import datetime as dt
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import RAW_ROOT  # noqa: E402

ROOT = Path(__file__).parent.parent
OLD_CACHE = ROOT / "data" / "pricing-cache"
NEW_DIR = RAW_ROOT / "tesla-locations"
OLD_INDEX = ROOT / "data" / "tesla-locations-us.json"
NEW_INDEX_DIR = RAW_ROOT / "tesla-index"


def utc_from_mtime(p: Path) -> str:
    """Filesystem-safe UTC ts derived from mtime."""
    t = dt.datetime.fromtimestamp(p.stat().st_mtime, tz=dt.timezone.utc)
    return t.strftime("%Y-%m-%dT%H-%M-%SZ")


def migrate_index() -> int:
    if not OLD_INDEX.exists():
        return 0
    NEW_INDEX_DIR.mkdir(parents=True, exist_ok=True)
    if any(NEW_INDEX_DIR.glob("*.json")):
        print(f"  tesla-index already has captures; skipping bulk-feed migration",
              file=sys.stderr)
        return 0
    try:
        old = json.loads(OLD_INDEX.read_text())
    except json.JSONDecodeError as e:
        print(f"  bad JSON in {OLD_INDEX.name}: {e}", file=sys.stderr)
        return 1
    ts = utc_from_mtime(OLD_INDEX)
    envelope = {
        "fetcher": "fetch_tesla_index",
        "fetcher_version": "0-migrated",
        "fetched_at": dt.datetime.fromtimestamp(
            OLD_INDEX.stat().st_mtime, tz=dt.timezone.utc
        ).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "request": {
            "url": "https://www.tesla.com/api/findus/get-locations?country=US&view=map",
            "method": "GET",
            "headers": {},
        },
        "response": {"status": 200, "headers": {}},
        "poller_run_id": None,
        "payload": old,
        "migrated_from": str(OLD_INDEX.relative_to(ROOT)),
    }
    (NEW_INDEX_DIR / f"{ts}.json").write_text(json.dumps(envelope, ensure_ascii=False))
    print(f"  migrated bulk index → tesla-index/{ts}.json", file=sys.stderr)
    return 0


def main() -> int:
    print("tesla bulk index:", file=sys.stderr)
    migrate_index()

    print("tesla per-slug cache:", file=sys.stderr)
    if not OLD_CACHE.exists():
        print(f"  no old cache at {OLD_CACHE}; nothing to migrate", file=sys.stderr)
        return 0
    NEW_DIR.mkdir(parents=True, exist_ok=True)

    migrated = 0
    skipped = 0
    bad = 0
    for f in sorted(OLD_CACHE.glob("*.json")):
        slug = f.stem
        out_dir = NEW_DIR / slug
        if out_dir.exists() and any(out_dir.iterdir()):
            skipped += 1
            continue
        try:
            old = json.loads(f.read_text())
        except json.JSONDecodeError:
            bad += 1
            print(f"  bad JSON in {f.name}; skipping", file=sys.stderr)
            continue

        ts = utc_from_mtime(f)
        out_dir.mkdir(parents=True, exist_ok=True)
        # Wrap in envelope. We don't have the original request/response
        # headers, so the migration envelope records what we know:
        # the URL shape that historically produced this payload.
        envelope = {
            "fetcher": "fetch_tesla_locations",
            "fetcher_version": "0-migrated",
            "fetched_at": dt.datetime.fromtimestamp(
                f.stat().st_mtime, tz=dt.timezone.utc
            ).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "request": {
                "url": (
                    f"https://www.tesla.com/api/findus/get-charger-details"
                    f"?locationSlug={slug}&programType=supercharger"
                    f"&locale=en-US&isInHkMoTw=false"
                ),
                "method": "GET",
                "headers": {},
            },
            "response": {"status": 200, "headers": {}},
            "poller_run_id": None,
            "payload": old,
            "migrated_from": str(f.relative_to(ROOT)),
        }
        (out_dir / f"{ts}.json").write_text(json.dumps(envelope, ensure_ascii=False))
        migrated += 1

    print(
        f"migration done: {migrated} migrated, {skipped} already-present, "
        f"{bad} unreadable",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
