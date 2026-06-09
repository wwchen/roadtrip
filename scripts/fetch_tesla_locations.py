#!/usr/bin/env python3
"""Capture per-supercharger detail payloads (Tesla get-charger-details).

Thin fetch-only per RFC 0007. One envelope per slug:

  data/raw/tesla-locations/<slug>/<UTC-ts>.json

Reads the latest tesla-index capture under data/raw/tesla-index/, walks
its NA superchargers, and skips slugs that already have a capture
within the freshness window (default: 30 days). Hits get-charger-details
for the rest at SLEEP_S between calls (Akamai-friendly pacing).

Crawling Tesla is expensive (cookie minting + curl-impersonate); the
default behavior is "skip what we have." Pass --refresh-all to ignore
freshness and re-fetch every slug.

Run:
  python3 scripts/fetch_tesla_locations.py
  python3 scripts/fetch_tesla_locations.py --limit 5
  python3 scripts/fetch_tesla_locations.py --refresh-all
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import tesla_client  # noqa: E402
from _envelope import (  # noqa: E402
    LoadedSource,
    err,
    load_source,
    write_envelope,
)

SLUG = "tesla-locations"
INDEX_SLUG = "tesla-index"
SOURCE = load_source(SLUG)
INDEX_SOURCE = load_source(INDEX_SLUG)

ROOT = Path(__file__).parent.parent
INDEX_DIR = INDEX_SOURCE.output_dir_prefix
LOCATIONS_DIR = SOURCE.output_dir_prefix

NA_BBOX = (14, 72, -180, -52)  # min_lat, max_lat, min_lng, max_lng — NA + Caribbean
SLEEP_S = 1.0
DEFAULT_FRESHNESS_DAYS = 30

# Once Akamai's bot wall fires, every subsequent slug 429s. Bailing fast lets
# the wrapper re-mint cookies (loop back to the cookie-paste stage) and
# resume — is_fresh() skips slugs we already captured this window, so
# nothing is re-fetched on the second try.
CONSECUTIVE_429_LIMIT = 3
EXIT_RATE_LIMITED = 2

FETCHER = "fetch_tesla_locations"
FETCHER_VERSION = "1"


def newest_index_capture() -> Path | None:
    """Pick the lexicographically-newest tesla-index capture (no symlinks)."""
    if not INDEX_DIR.exists():
        return None
    captures = sorted(INDEX_DIR.glob("*.json"))
    return captures[-1] if captures else None


def latest_capture(slug: str) -> Path | None:
    d = LOCATIONS_DIR / slug
    if not d.exists():
        return None
    captures = sorted(d.glob("*.json"))
    return captures[-1] if captures else None


def is_fresh(slug: str, max_age_days: int) -> bool:
    cap = latest_capture(slug)
    if cap is None:
        return False
    age_s = time.time() - cap.stat().st_mtime
    return age_s < max_age_days * 86400


def na_supercharger_slugs(index_path: Path) -> list[str]:
    """Walk the bulk locations payload, return slugs of NA supercharger pins.

    Oracle is `supercharger_function` populated, full stop. Tesla's
    bulk feed has two fields that look like filters but aren't:
      - `location_type`: inconsistent. Real superchargers come back
        tagged ['nacs'] (Magic Dock / NACS partner) or ['party'] (V4)
        instead of ['supercharger']. ~4k NA rows.
      - `show_on_find_us='0'`: editorial. Tesla doesn't promote these
        on tesla.com/findus, but they're still site_status='open' and
        access_type='Public' — a Tesla driver can plug in. ~268 NA rows
        (Magic Dock / partner-launch sites). Famous example: 32648
        (El Paso Cielo Vista).
    Trusting `supercharger_function` alone matches "every real
    supercharger a Tesla driver could use today."
    """
    d = json.loads(index_path.read_text())
    payload = d.get("payload") or d  # tolerate envelope or bare
    items = ((payload.get("data") or {}).get("data") or [])
    out = []
    for item in items:
        sf = item.get("supercharger_function") or {}
        if not sf:
            continue
        lat = item.get("latitude")
        lng = item.get("longitude")
        if lat is None or lng is None:
            continue
        if not (NA_BBOX[0] < lat < NA_BBOX[1] and NA_BBOX[2] < lng < NA_BBOX[3]):
            continue
        slug = item.get("location_url_slug")
        if not slug:
            continue
        out.append(slug)
    return out


def fetch_slug(slug: str) -> tuple[int, dict | str]:
    """Hit get-charger-details via tesla_client. Returns (http status, payload)."""
    return tesla_client.fetch_tesla_pricing(slug)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=None,
                        help="only process first N slugs (smoke test)")
    parser.add_argument("--refresh-all", action="store_true",
                        help="ignore freshness, re-fetch every slug")
    parser.add_argument("--max-age-days", type=int, default=DEFAULT_FRESHNESS_DAYS,
                        help="skip slugs whose latest capture is younger than this")
    args = parser.parse_args()

    tesla_client.load_env()

    idx = newest_index_capture()
    if idx is None:
        err("no tesla-index capture under data/raw/tesla-index/; run fetch_tesla_index.py first")
        return 1
    err(f"using index {idx.relative_to(ROOT)}")
    slugs = na_supercharger_slugs(idx)
    if args.limit:
        slugs = slugs[: args.limit]
    err(f"NA supercharger slugs in index: {len(slugs)}")

    LOCATIONS_DIR.mkdir(parents=True, exist_ok=True)

    fetched = 0
    skipped = 0
    failed = 0
    consecutive_429 = 0
    started = time.time()
    for i, slug in enumerate(slugs, 1):
        if not args.refresh_all and is_fresh(slug, args.max_age_days):
            skipped += 1
            continue
        status, payload = fetch_slug(slug)
        if status != 200 or not isinstance(payload, dict):
            failed += 1
            err(f"  fail {slug}: HTTP {status}")
            if status == 429:
                consecutive_429 += 1
                if consecutive_429 >= CONSECUTIVE_429_LIMIT:
                    err(
                        f"  {consecutive_429} consecutive 429s — Akamai is blocking us. "
                        f"Bailing. Re-mint cookies and re-run; is_fresh skips slugs already "
                        f"captured this window. fetched={fetched} skipped={skipped} failed={failed}"
                    )
                    return EXIT_RATE_LIMITED
            else:
                consecutive_429 = 0
            time.sleep(SLEEP_S)
            continue
        consecutive_429 = 0
        # Per-slug captures live at data/raw/tesla-locations/<slug>/<ts>.json
        # — synthesize a LoadedSource that re-roots under <slug>/.
        per_slug = LoadedSource(
            slug=f"{SLUG}/{slug}",
            name=f"{SOURCE.name} ({slug})",
            output_dir_prefix=LOCATIONS_DIR / slug,
            args={},
        )
        write_envelope(
            source_obj=per_slug,
            fetcher=FETCHER,
            fetcher_version=FETCHER_VERSION,
            request_url=(
                f"https://www.tesla.com/api/findus/get-charger-details"
                f"?locationSlug={slug}&programType=supercharger"
                f"&locale=en-US&isInHkMoTw=false"
            ),
            request_method="GET",
            request_headers={},
            response_status=status,
            response_headers={},
            payload=payload,
        )
        fetched += 1
        time.sleep(SLEEP_S)
        if i % 50 == 0 or i == len(slugs):
            elapsed = time.time() - started
            err(f"  {i}/{len(slugs)} fetched={fetched} skipped={skipped} "
                f"failed={failed} ({elapsed:.0f}s)")
    err(f"done: fetched={fetched} skipped={skipped} failed={failed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
