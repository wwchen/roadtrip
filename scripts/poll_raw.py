#!/usr/bin/env python3
"""Local fetcher entry point — reads config/poi-registry.yaml.

Picks a source via fzf and runs its fetcher locally (no backend needed).
For the production-shaped path that goes through the admin API and is
recorded in poller_runs, use `bin/refresh` instead.

  python3 scripts/poll_raw.py                         # fzf source picker
  python3 scripts/poll_raw.py <slug>                  # by data_source slug
  python3 scripts/poll_raw.py --all                   # every enabled source
  python3 scripts/poll_raw.py --list                  # JSON, no fetch

The list of sources comes from config/poi-registry.yaml — one entry per
`data_sources:` row. Adding a new fetcher means appending a row there +
writing the Python script under scripts/.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

try:
    import yaml  # PyYAML
except ImportError:
    print("scripts/poll_raw.py needs PyYAML: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).parent.parent
REGISTRY = ROOT / "config" / "poi-registry.yaml"


@dataclass
class Source:
    slug: str
    enabled: bool
    fetcher_enabled: bool
    executor: str
    filename: str
    args: dict = field(default_factory=dict)
    output_dir_prefix: Path = ROOT
    depends_on: list[str] = field(default_factory=list)


def load_sources() -> list[Source]:
    """Flatten poi-registry.yaml's data_sources into Source rows."""
    if not REGISTRY.exists():
        err(f"missing {REGISTRY}")
        sys.exit(1)
    doc = yaml.safe_load(REGISTRY.read_text()) or {}
    out: list[Source] = []
    for src in doc.get("data_sources") or []:
        fetcher = src.get("fetcher") or {}
        prefix = fetcher.get("output_dir_prefix") or f"data/raw/{src['slug']}"
        out.append(
            Source(
                slug=src["slug"],
                enabled=bool(src.get("enabled", True)),
                fetcher_enabled=bool(fetcher.get("enabled", True)),
                executor=fetcher.get("executor", "python3"),
                filename=fetcher.get("filename", ""),
                args=fetcher.get("args") or {},
                output_dir_prefix=ROOT / prefix,
                depends_on=src.get("depends_on") or [],
            )
        )
    return out


def err(msg: str) -> None:
    print(msg, file=sys.stderr)


def captures_since(out_dir: Path, since_ts: float) -> list[Path]:
    if not out_dir.exists():
        return []
    out: list[Path] = []
    for entry in out_dir.iterdir():
        if entry.is_file() and entry.suffix == ".json":
            if entry.stat().st_mtime >= since_ts:
                out.append(entry)
        elif entry.is_dir():
            for f in entry.glob("*.json"):
                if f.stat().st_mtime >= since_ts:
                    out.append(f)
    return sorted(out)


def run_source(src: Source) -> int:
    if not src.fetcher_enabled:
        err(f"  ⚠ {src.slug}: fetcher.enabled=false in poi-registry.yaml; skipping")
        return 0
    cli_args: list[str] = []
    for k, v in src.args.items():
        cli_args += [f"--{k}", str(v)]
    script = ROOT / src.filename if src.filename else None
    if script is None or not script.exists():
        err(f"  ⚠ {src.slug}: missing fetcher script {src.filename}")
        return 1
    cmd = [src.executor, str(script), *cli_args]
    err(f"\n→ {src.slug}: {' '.join(cmd)}")
    started = time.time() - 1
    rc = subprocess.call(cmd, cwd=ROOT)
    if rc != 0:
        err(f"  ⚠ {src.slug} exited {rc}")
        return rc
    fresh = captures_since(src.output_dir_prefix, started)
    if fresh:
        err("  wrote:")
        if len(fresh) > 10:
            kb = sum(p.stat().st_size for p in fresh) // 1024
            err(f"    {len(fresh)} files, {kb} KB total (e.g. {fresh[0].relative_to(ROOT)})")
        else:
            for p in fresh:
                err(f"    {p.relative_to(ROOT)}  ({p.stat().st_size // 1024} KB)")
    else:
        err("  (no new captures — cache-aware skip)")
    return 0


def topo_sort(sources: list[Source]) -> list[Source]:
    by_id = {s.slug: s for s in sources}
    visited: set[str] = set()
    visiting: set[str] = set()
    out: list[Source] = []

    def visit(s: Source) -> None:
        if s.slug in visited:
            return
        if s.slug in visiting:
            raise RuntimeError(f"depends_on cycle on {s.slug}")
        visiting.add(s.slug)
        for dep in s.depends_on:
            if dep in by_id:
                visit(by_id[dep])
        visiting.discard(s.slug)
        visited.add(s.slug)
        out.append(s)

    for s in sources:
        visit(s)
    return out


def fzf_pick(sources: list[Source]) -> Source | None:
    if not shutil.which("fzf"):
        err("fzf not installed; pass a slug explicitly")
        err(f"  available: {', '.join(s.slug for s in sources)}")
        return None
    rows = "\n".join(
        f"{s.slug:<26} enabled={s.enabled}  fetcher={s.filename}" for s in sources
    )
    proc = subprocess.run(
        ["fzf", "--height=40%", "--prompt=poll raw> ", "--no-multi"],
        input=rows,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        return None
    name = proc.stdout.strip().split(None, 1)[0]
    return next((s for s in sources if s.slug == name), None)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", help="data_source slug (no fzf)")
    parser.add_argument("--all", action="store_true", help="run every enabled data_source")
    parser.add_argument("--list", action="store_true", help="JSON registry, no fetch")
    args = parser.parse_args()

    sources = load_sources()

    if args.list:
        print(
            json.dumps(
                [
                    {
                        "slug": s.slug,
                        "enabled": s.enabled,
                        "fetcher_enabled": s.fetcher_enabled,
                        "executor": s.executor,
                        "filename": s.filename,
                        "args": s.args,
                        "output_dir_prefix": str(s.output_dir_prefix.relative_to(ROOT)),
                    }
                    for s in sources
                ],
                indent=2,
            )
        )
        return 0

    if args.all:
        rc = 0
        # Skip top-level disabled sources entirely. Sources with
        # fetcher.enabled=false are kept in the topo sort (their
        # depends_on may still matter for siblings) but `run_source`
        # short-circuits with a "skipping" log.
        for s in topo_sort([s for s in sources if s.enabled]):
            if run_source(s) != 0:
                rc = 1
        return rc

    if args.source:
        match = next((s for s in sources if s.slug == args.source), None)
        if match is None:
            err(f"unknown source: {args.source}")
            err(f"  available: {', '.join(s.slug for s in sources)}")
            return 1
        return run_source(match)

    pick = fzf_pick(sources)
    if pick is None:
        err("nothing picked")
        return 1
    return run_source(pick)


if __name__ == "__main__":
    sys.exit(main())
