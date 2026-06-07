#!/usr/bin/env python3
"""Local fetcher entry point — reads config/poi-registry.yaml.

Picks a source via fzf and runs its fetcher locally (no backend needed).
For the production-shaped path that goes through the admin API and is
recorded in poller_runs, use `bin/refresh` instead.

  python3 scripts/poll_raw.py                         # fzf source picker
  python3 scripts/poll_raw.py <source>                # by source id
  python3 scripts/poll_raw.py --governing <slug>      # all sources under one body
  python3 scripts/poll_raw.py --all                   # every source
  python3 scripts/poll_raw.py --list                  # JSON, no fetch

The list of sources comes from config/poi-registry.yaml — one source per
governing_body.sources row. Adding a new fetcher means appending a row
there + writing the Python script under scripts/.
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
RAW = ROOT / "data" / "raw"
REGISTRY = ROOT / "config" / "poi-registry.yaml"


@dataclass
class Source:
    id: str
    fetcher: str
    args: dict = field(default_factory=dict)
    governing_body: str = ""
    depends_on: list[str] = field(default_factory=list)


def load_sources() -> list[Source]:
    """Flatten poi-registry.yaml into a list of Source rows."""
    if not REGISTRY.exists():
        err(f"missing {REGISTRY}")
        sys.exit(1)
    doc = yaml.safe_load(REGISTRY.read_text())
    out: list[Source] = []
    for gb in doc.get("governing_bodies") or []:
        for src in gb.get("sources") or []:
            out.append(
                Source(
                    id=src["id"],
                    fetcher=src["fetcher"],
                    args=src.get("args") or {},
                    governing_body=gb["slug"],
                    depends_on=src.get("depends_on") or [],
                )
            )
    return out


def err(msg: str) -> None:
    print(msg, file=sys.stderr)


def captures_since(raw_dir: str, since_ts: float) -> list[Path]:
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
    cli_args: list[str] = []
    for k, v in src.args.items():
        cli_args += [f"--{k}", str(v)]
    cmd = ["python3", str(ROOT / "scripts" / f"{src.fetcher}.py"), *cli_args]
    err(f"\n→ {src.id} ({src.governing_body}): {' '.join(cmd)}")
    started = time.time() - 1
    rc = subprocess.call(cmd, cwd=ROOT)
    if rc != 0:
        err(f"  ⚠ {src.id} exited {rc}")
        return rc
    fresh = captures_since(src.id, started)
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
    by_id = {s.id: s for s in sources}
    visited: set[str] = set()
    visiting: set[str] = set()
    out: list[Source] = []

    def visit(s: Source) -> None:
        if s.id in visited:
            return
        if s.id in visiting:
            raise RuntimeError(f"depends_on cycle on {s.id}")
        visiting.add(s.id)
        for dep in s.depends_on:
            if dep in by_id:
                visit(by_id[dep])
        visiting.discard(s.id)
        visited.add(s.id)
        out.append(s)

    for s in sources:
        visit(s)
    return out


def fzf_pick(sources: list[Source]) -> Source | None:
    if not shutil.which("fzf"):
        err("fzf not installed; pass a source name explicitly")
        err(f"  available: {', '.join(s.id for s in sources)}")
        return None
    rows = "\n".join(
        f"{s.id:<22} ({s.governing_body})  fetcher={s.fetcher}" for s in sources
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
    return next((s for s in sources if s.id == name), None)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", nargs="?", help="source id (no fzf)")
    parser.add_argument(
        "--governing", metavar="SLUG", help="run every source under this governing body"
    )
    parser.add_argument("--all", action="store_true", help="run every source in registry order")
    parser.add_argument("--list", action="store_true", help="JSON registry, no fetch")
    args = parser.parse_args()

    sources = load_sources()

    if args.list:
        print(
            json.dumps(
                [
                    {
                        "id": s.id,
                        "governing_body": s.governing_body,
                        "fetcher": s.fetcher,
                        "args": s.args,
                    }
                    for s in sources
                ],
                indent=2,
            )
        )
        return 0

    if args.governing:
        chosen = [s for s in sources if s.governing_body == args.governing]
        if not chosen:
            err(f"no sources for governing_body={args.governing}")
            return 1
        rc = 0
        for s in topo_sort(chosen):
            if run_source(s) != 0:
                rc = 1
        return rc

    if args.all:
        rc = 0
        for s in topo_sort(sources):
            if run_source(s) != 0:
                rc = 1
        return rc

    if args.source:
        match = next((s for s in sources if s.id == args.source), None)
        if match is None:
            err(f"unknown source: {args.source}")
            err(f"  available: {', '.join(s.id for s in sources)}")
            return 1
        return run_source(match)

    pick = fzf_pick(sources)
    if pick is None:
        err("nothing picked")
        return 1
    return run_source(pick)


if __name__ == "__main__":
    sys.exit(main())
