#!/usr/bin/env python3
"""Capture Campflare JSONL gzip exports.

Writes multipart envelope files under data/raw/<campflare-source>/<timestamp>/.

Run:
  CAMPFLARE_API_KEY=... python3 scripts/fetch_campflare_dump.py --slug campflare-campgrounds-export --kind campgrounds
  CAMPFLARE_API_KEY=... python3 scripts/fetch_campflare_dump.py --slug campflare-campsites-export --kind campsites
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import err, load_source, utc_ts, write_envelope  # noqa: E402

FETCHER = "fetch_campflare_dump"
FETCHER_VERSION = "1"
DEFAULT_BASE_URL = "https://api.campflare.com/v2"
DEFAULT_CHUNK_SIZE = 5_000
AUTH_HEADER = "Authorization"
REDACTED = "<redacted>"
TOKEN_ENV_NAMES = ("CAMPFLARE_API_KEY", "CAMPFLARE_TOKEN")
DEFAULT_ENV_FILES = (Path(".env"), Path("/tmp/campflare"))


def resolve_api_key(environ: dict[str, str] | None = None, env_files=DEFAULT_ENV_FILES) -> str:
    environ = environ or os.environ
    for name in TOKEN_ENV_NAMES:
        value = environ.get(name, "").strip()
        if value:
            return value

    for path in env_files:
        if not path.exists() or not path.is_file():
            continue
        for line in path.read_text().splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if "=" not in stripped:
                return stripped
            key, value = stripped.split("=", 1)
            if key.strip() in TOKEN_ENV_NAMES and value.strip():
                return value.strip().strip("\"'")

    raise RuntimeError("missing CAMPFLARE_API_KEY; set it in the environment, .env, or /tmp/campflare")


def http_get_bytes(url: str, headers: dict[str, str], timeout: int = 300) -> tuple[int, dict[str, str], bytes]:
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, {k.lower(): v for k, v in resp.getheaders()}, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


def fetch_manifest(base_url: str, api_key: str) -> dict:
    url = f"{base_url.rstrip('/')}/dumps/latest"
    status, _headers, body = http_get_bytes(url, {AUTH_HEADER: api_key}, timeout=120)
    if status != 200:
        raise RuntimeError(f"Campflare manifest request failed with HTTP {status}")
    return json.loads(body.decode("utf-8"))


def write_dump_envelopes(
    *,
    source,
    kind: str,
    dump_url: str,
    compressed_body: bytes,
    response_headers: dict[str, str],
    authorization_header: str,
    chunk_size: int,
    ts: str | None = None,
) -> list[Path]:
    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    ts = ts or utc_ts()
    request_headers = {AUTH_HEADER: REDACTED if authorization_header else REDACTED}
    decompressed = gzip.decompress(compressed_body)
    written: list[Path] = []
    chunk: list[dict] = []
    part_number = 1

    def flush() -> None:
        nonlocal chunk, part_number
        if not chunk:
            return
        written.append(
            write_envelope(
                source_obj=source,
                fetcher=FETCHER,
                fetcher_version=FETCHER_VERSION,
                request_url=dump_url,
                request_method="GET",
                request_headers=request_headers,
                response_status=200,
                response_headers=response_headers,
                payload=chunk,
                part=f"part-{part_number:06d}",
                ts=ts,
            )
        )
        part_number += 1
        chunk = []

    for line in decompressed.splitlines():
        if not line.strip():
            continue
        chunk.append(json.loads(line))
        if len(chunk) >= chunk_size:
            flush()
    flush()
    return written


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slug", required=True)
    parser.add_argument("--kind", choices=("campgrounds", "campsites"), required=True)
    parser.add_argument("--chunk-size", type=int, default=DEFAULT_CHUNK_SIZE)
    parser.add_argument("--base-url", default=os.environ.get("CAMPFLARE_API_BASE", DEFAULT_BASE_URL))
    args = parser.parse_args()

    source = load_source(args.slug)
    try:
        api_key = resolve_api_key()
        manifest = fetch_manifest(args.base_url, api_key)
        dump = manifest.get(args.kind) or {}
        dump_url = dump.get("url")
        if not dump_url:
            raise RuntimeError(f"Campflare manifest did not include {args.kind}.url")
        err(f"fetching Campflare {args.kind} dump")
        status, response_headers, body = http_get_bytes(dump_url, {AUTH_HEADER: api_key}, timeout=1800)
        if status != 200:
            raise RuntimeError(f"Campflare {args.kind} dump failed with HTTP {status}")
        written = write_dump_envelopes(
            source=source,
            kind=args.kind,
            dump_url=dump_url,
            compressed_body=body,
            response_headers=response_headers,
            authorization_header=api_key,
            chunk_size=args.chunk_size,
        )
    except Exception as e:  # noqa: BLE001
        err(f"  fetch failed: {e}")
        return 1

    err(f"  wrote {len(written)} part(s) for {args.kind}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
