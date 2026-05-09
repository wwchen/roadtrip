"""On-disk cookie cache, one JSON file per profile.

Each entry: {"harvested_at": unix_ts, "cookies": [playwright cookie dicts]}
Callers decide staleness based on profile.ttl_seconds.
"""
from __future__ import annotations
import json
import time
from pathlib import Path
from typing import Any


def _path(cache_dir: Path, name: str) -> Path:
    return cache_dir / f"{name}.json"


def read(cache_dir: Path, name: str) -> dict[str, Any] | None:
    p = _path(cache_dir, name)
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text())
    except (json.JSONDecodeError, OSError):
        return None


def write(cache_dir: Path, name: str, cookies: list[dict[str, Any]]) -> dict[str, Any]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    entry = {"harvested_at": int(time.time()), "cookies": cookies}
    _path(cache_dir, name).write_text(json.dumps(entry, indent=2))
    return entry


def is_fresh(entry: dict[str, Any], ttl_seconds: int) -> bool:
    return int(time.time()) - int(entry.get("harvested_at", 0)) < ttl_seconds


def as_header(cookies: list[dict[str, Any]]) -> str:
    """Render a Cookie: header value (space-joined key=value; pairs)."""
    return "; ".join(f"{c['name']}={c['value']}" for c in cookies)
