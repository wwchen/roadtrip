#!/usr/bin/env python3
"""Single entry point for the thin Python fetchers (RFC 0007).

Wraps every `scripts/fetch_*.py` behind one fzf picker. Run it, pick a
source, the script delegates to that fetcher and prints the raw-cache
path it just wrote.

  python3 scripts/poll_raw.py              # fzf picker
  python3 scripts/poll_raw.py <source>     # by name (no fzf)
  python3 scripts/poll_raw.py --all        # every source, in order
  python3 scripts/poll_raw.py --list       # JSON registry, no fetch

Sources are registered in SOURCES below — one row per coherent capture
under `data/raw/<source>/`. Adding a new fetcher = appending a row.

Note: Tesla per-slug enrichment (`fetch_tesla_locations.py`) is paired
with the Tesla index because the locations script reads the latest
index capture; running them together produces a complete capture set.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).parent.parent
RAW = ROOT / "data" / "raw"


@dataclass
class Source:
    name: str
    blurb: str
    raw_dir: str  # under data/raw/, e.g. "osm-pf" or "tesla-index"
    cmd: list[str]  # how to invoke (relative to repo root)
    extra_dirs: list[str] | None = None  # for fetchers that touch >1 raw dir
    # When set, --all skips this source if the named source failed in this run.
    # Lets tesla-locations bail out when tesla-index can't get fresh data.
    skip_if_failed: str | None = None


SOURCES: list[Source] = [
    Source(
        name="planet-fitness",
        blurb="OSM Overpass — Planet Fitness US locations (~1.5k pins)",
        raw_dir="osm-pf",
        cmd=["python3", "scripts/fetch_planet_fitness.py"],
    ),
    Source(
        name="campgrounds",
        blurb="uscampgrounds.info regional CSVs (5 regions, US public campgrounds)",
        raw_dir="uscampgrounds",
        cmd=["python3", "scripts/fetch_campgrounds.py"],
    ),
    Source(
        name="bc-parks",
        blurb="BC Parks Strapi API — provincial parks with camping",
        raw_dir="bcparks-strapi",
        cmd=["python3", "scripts/fetch_bc_parks.py"],
    ),
    Source(
        name="parks-national",
        blurb="USGS PAD-US — National Parks (paginated)",
        raw_dir="padus-np",
        cmd=["python3", "scripts/fetch_parks.py", "--layer", "national-parks"],
    ),
    Source(
        name="parks-state",
        blurb="USGS PAD-US — State Parks (paginated)",
        raw_dir="padus-sp",
        cmd=["python3", "scripts/fetch_parks.py", "--layer", "state-parks"],
    ),
    Source(
        name="aspira-maps",
        blurb="Aspira NextGen /api/maps for PC + BC + WA (booking-ID lookup)",
        raw_dir="aspira-maps-pc",  # primary; fetcher also writes -bc and -wa
        cmd=["python3", "scripts/fetch_aspira_maps.py"],
        extra_dirs=["aspira-maps-bc", "aspira-maps-wa"],
    ),
    Source(
        name="tesla-index",
        blurb="Tesla bulk locations feed (curl-impersonate; needs Tesla cookies in .env)",
        raw_dir="tesla-index",
        cmd=["python3", "scripts/fetch_tesla_index.py"],
    ),
    Source(
        name="tesla-locations",
        blurb="Tesla per-slug get-charger-details for NA superchargers (cache-aware)",
        raw_dir="tesla-locations",
        cmd=["python3", "scripts/fetch_tesla_locations.py"],
        skip_if_failed="tesla-index",
    ),
]


def err(msg: str) -> None:
    print(msg, file=sys.stderr)


def captures_since(raw_dir: str, since_ts: float) -> list[Path]:
    """Envelopes under data/raw/<raw_dir>/ whose mtime is at-or-after
    `since_ts`. Recurses one level (paginated sources nest pages under a
    per-capture subdir). Empty list = nothing fresh (the script ran but
    didn't write — e.g. cache-aware skip)."""
    d = RAW / raw_dir
    if not d.exists():
        return []
    out: list[Path] = []
    for entry in d.iterdir():
        if entry.is_file() and entry.suffix == ".json":
            if entry.stat().st_mtime >= since_ts:
                out.append(entry)
        elif entry.is_dir():
            for f in entry.glob("*.json"):
                if f.stat().st_mtime >= since_ts:
                    out.append(f)
    return sorted(out)


def run_source(src: Source) -> int:
    err(f"\n→ {src.name}: {' '.join(src.cmd)}")
    started = time.time() - 1  # 1s slack for filesystems with second-precision mtimes
    rc = subprocess.call(src.cmd, cwd=ROOT)
    if rc != 0:
        err(f"  ⚠ {src.name} exited {rc}")
        return rc
    fresh: list[Path] = []
    for d in [src.raw_dir] + (src.extra_dirs or []):
        fresh.extend(captures_since(d, started))
    if fresh:
        err("  wrote:")
        # If the run produced lots of files (paginated, per-slug), summarize.
        if len(fresh) > 10:
            total_kb = sum(p.stat().st_size for p in fresh) // 1024
            sample = fresh[0].relative_to(ROOT)
            err(f"    {len(fresh)} files, {total_kb} KB total (e.g. {sample})")
        else:
            for p in fresh:
                rel = p.relative_to(ROOT)
                size_kb = p.stat().st_size // 1024
                err(f"    {rel}  ({size_kb} KB)")
    else:
        err("  (no new captures — cache-aware skip)")
    return 0


def fzf_pick(sources: list[Source]) -> Source | None:
    if not shutil.which("fzf"):
        err("fzf not installed; install fzf or pass a source name explicitly")
        err("  available sources: " + ", ".join(s.name for s in sources))
        return None
    rows = "\n".join(f"{s.name:<20} {s.blurb}" for s in sources)
    proc = subprocess.run(
        ["fzf", "--height=40%", "--prompt=poll raw> ", "--no-multi"],
        input=rows,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        return None
    name = proc.stdout.strip().split(None, 1)[0]
    for s in sources:
        if s.name == name:
            return s
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", help="source name (no fzf)")
    parser.add_argument("--all", action="store_true", help="run every source in order")
    parser.add_argument("--list", action="store_true", help="JSON registry, no fetch")
    args = parser.parse_args()

    if args.list:
        print(json.dumps(
            [{"name": s.name, "blurb": s.blurb, "raw_dir": s.raw_dir} for s in SOURCES],
            indent=2,
        ))
        return 0

    if args.all:
        rc = 0
        failed: set[str] = set()
        for s in SOURCES:
            if s.skip_if_failed and s.skip_if_failed in failed:
                err(f"\n→ {s.name}: skipped (depends on {s.skip_if_failed} which failed)")
                continue
            if run_source(s) != 0:
                rc = 1
                failed.add(s.name)
        return rc

    if args.source:
        for s in SOURCES:
            if s.name == args.source:
                return run_source(s)
        err(f"unknown source: {args.source}")
        err("  available: " + ", ".join(s.name for s in SOURCES))
        return 1

    pick = fzf_pick(SOURCES)
    if pick is None:
        err("nothing picked")
        return 1
    return run_source(pick)


if __name__ == "__main__":
    sys.exit(main())
