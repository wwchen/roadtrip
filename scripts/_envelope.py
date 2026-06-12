"""Shared helpers for the thin fetchers.

Every fetcher writes a uniform envelope around the upstream response so the
Kotlin ETL has a stable outer shape to parse and a clear contract record:
which endpoint, which UA, which fetcher version produced this capture.

  data/raw/<source>/<UTC-ts>.json
  data/raw/<source>/<UTC-ts>/<part>.json   (multi-part captures)

The ETL picks the newest by listing the directory and taking the
lexicographically-greatest entry; no `latest` symlink to maintain.

Captures are append-only — see RFC 0007. Crawling Aspira/Tesla/etc. has
real cost (Azure WAF, Akamai, cookie injection); replaying raw is free.
"""
from __future__ import annotations

import datetime as dt
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).parent.parent
REGISTRY = ROOT / "config" / "poi-registry.yaml"


@dataclass
class LoadedSource:
    """One row from `data_sources:` in config/poi-registry.yaml.

    Exposes the bits the per-source fetcher scripts need: where to write
    the envelope (`output_dir_prefix`, relative to ROOT), the slug used
    for source labelling, and the optional CLI args block.
    """

    slug: str
    name: str
    output_dir_prefix: Path
    args: dict = field(default_factory=dict)


def load_source(slug: str) -> LoadedSource:
    """Look up a single data_source entry by slug.

    PyYAML-only — no Kotlin coupling. The YAML is the contract.
    """
    try:
        import yaml  # PyYAML
    except ImportError:
        err("scripts/_envelope.load_source needs PyYAML: pip install pyyaml")
        raise

    if not REGISTRY.exists():
        raise RuntimeError(f"missing {REGISTRY}")
    doc = yaml.safe_load(REGISTRY.read_text()) or {}
    for src in doc.get("data_sources") or []:
        if src.get("slug") == slug:
            fetcher = src.get("fetcher") or {}
            prefix = fetcher.get("output_dir_prefix")
            if not prefix:
                raise RuntimeError(
                    f"data_source slug={slug!r} is missing fetcher.output_dir_prefix"
                )
            return LoadedSource(
                slug=slug,
                name=src.get("name") or slug,
                output_dir_prefix=ROOT / prefix,
                args=fetcher.get("args") or {},
            )
    raise KeyError(f"data_source slug={slug!r} not in {REGISTRY}")


def utc_ts() -> str:
    """Filesystem-safe UTC timestamp: 2026-06-07T19-23-44Z."""
    now = dt.datetime.now(dt.timezone.utc)
    return now.strftime("%Y-%m-%dT%H-%M-%SZ")


def write_envelope(
    *,
    source: str | None = None,
    source_obj: LoadedSource | None = None,
    fetcher: str,
    fetcher_version: str,
    request_url: str,
    request_method: str,
    request_headers: dict,
    response_status: int,
    response_headers: dict,
    payload,  # str | dict | list — verbatim upstream content
    poller_run_id: int | None = None,
    part: str | None = None,
    ts: str | None = None,
) -> Path:
    """Write a single capture for one data_source. Returns the path written.

    Either `source` (slug string — the loader resolves it via
    config/poi-registry.yaml) or `source_obj` (already-loaded
    LoadedSource) must be supplied. When both are given, `source_obj`
    wins.

    The Kotlin ETL parses the outer shape uniformly and dispatches the
    `payload` field by `fetcher`. `payload` carries the verbatim upstream
    body — JSON for application/json responses, the original string for
    non-JSON.

    `part` lets a multi-file source nest under one timestamp:
      data/raw/uscampgrounds/2026-06-07T19-23-44Z/west.json

    `ts` lets a paginated/multi-step capture force all parts under the
    same timestamp directory; defaults to now.
    """
    if source_obj is None:
        if source is None:
            raise TypeError("write_envelope: pass `source=<slug>` or `source_obj=<LoadedSource>`")
        source_obj = load_source(source)
    out_root = source_obj.output_dir_prefix
    slug = source_obj.slug
    ts = ts or utc_ts()
    if part:
        out_dir = out_root / ts
        out_dir.mkdir(parents=True, exist_ok=True)
        out = out_dir / f"{part}.json"
    else:
        out_root.mkdir(parents=True, exist_ok=True)
        out = out_root / f"{ts}.json"

    envelope = {
        "fetcher": fetcher,
        "fetcher_version": fetcher_version,
        "fetched_at": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "request": {
            "url": request_url,
            "method": request_method,
            "headers": request_headers,
        },
        "response": {
            "status": response_status,
            "headers": response_headers,
        },
        "poller_run_id": poller_run_id,
        "payload": payload,
    }
    if part:
        envelope["part"] = part
    out.write_text(json.dumps(envelope, ensure_ascii=False))
    log(f" wrote file to {out}")
    return out


def http_get_text(
    url: str,
    *,
    headers: dict | None = None,
    timeout: int = 60,
) -> tuple[int, dict, str]:
    """GET that returns (status, response_headers, body_text). Raises on
    network failure (the fetcher script's caller decides how to handle).

    Response headers are normalized to lowercase keys so JSONB lookups
    in the ETL aren't case-sensitive (HTTP/1.1 headers are
    case-insensitive but most fetchers default to title-case).
    """
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return (
                resp.status,
                {k.lower(): v for k, v in resp.getheaders()},
                body.decode("utf-8", errors="replace"),
            )
    except urllib.error.HTTPError as e:
        body = e.read()
        return (
            e.code,
            {k.lower(): v for k, v in e.headers.items()},
            body.decode("utf-8", errors="replace"),
        )


def http_post_text(
    url: str,
    *,
    data: bytes,
    headers: dict | None = None,
    timeout: int = 120,
) -> tuple[int, dict, str]:
    """POST variant — Overpass's only entry point. Same lower-casing
    behavior as http_get_text."""
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return (
                resp.status,
                {k.lower(): v for k, v in resp.getheaders()},
                body.decode("utf-8", errors="replace"),
            )
    except urllib.error.HTTPError as e:
        body = e.read()
        return (
            e.code,
            {k.lower(): v for k, v in e.headers.items()},
            body.decode("utf-8", errors="replace"),
        )


def parse_payload(content_type: str, body: str):
    """JSON-decode if upstream said it was JSON, otherwise keep verbatim string.

    The envelope's `payload` field carries decoded JSON (object/array) when
    available — saves Kotlin a re-parse and lets the JSON serializer below
    pretty-print without escaping. Plain-text upstream (CSV) stays as a
    string field.
    """
    ct = (content_type or "").lower()
    if "json" in ct:
        try:
            return json.loads(body)
        except ValueError:
            pass
    return body

def log(msg: str) -> None:
    print(msg, file=sys.stdout)

def err(msg: str) -> None:
    print(msg, file=sys.stderr)
