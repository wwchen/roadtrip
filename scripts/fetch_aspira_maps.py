#!/usr/bin/env python3
"""Capture Aspira NextGen `/api/maps` for one host.

Per RFC 0007 PR 3.5: vendor-centric, host-parameterized. The poller
(or `bin/refresh GOVERNING=...`) invokes this once per host that the
registry declares — three times today (PC/BC/WA). The `--source` flag
controls the data/raw subdir (must match config/poi-registry.yaml).

  python3 scripts/fetch_aspira_maps.py --host=reservation.pc.gc.ca --source=aspira-maps-pc
  python3 scripts/fetch_aspira_maps.py --host=camping.bcparks.ca   --source=aspira-maps-bc
  python3 scripts/fetch_aspira_maps.py --host=washington.goingtocamp.com --source=aspira-maps-wa

Adding a fourth Aspira host is a one-line YAML change — no edits here.

Aspira's WAF (Azure App Gateway) rejects bare-curl UAs and `Accept-
Charset` headers — same browser-shaped UA as the backend's
AspiraAvailabilityClient is required.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from _envelope import err, http_get_text, parse_payload, write_envelope  # noqa: E402

UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)

FETCHER = "fetch_aspira_maps"
FETCHER_VERSION = "3"  # v3: --host param, single-host per invocation


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True, help="Aspira host (e.g. reservation.pc.gc.ca)")
    parser.add_argument(
        "--source",
        required=True,
        help="data/raw subdir / poi-registry source.id (e.g. aspira-maps-pc)",
    )
    args = parser.parse_args()

    url = f"https://{args.host}/api/maps"
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Referer": f"https://{args.host}/",
    }
    err(f"fetching {args.host}…")
    try:
        status, resp_headers, body = http_get_text(url, headers=headers, timeout=30)
    except Exception as e:  # noqa: BLE001
        err(f"  {args.host} failed: {e}")
        return 1

    # WAF challenge detection: Aspira sometimes returns HTML 200s.
    if body.lstrip().startswith("<"):
        err(f"  {args.host} WAF-challenge HTML response (status={status}); not writing")
        return 1

    payload = parse_payload(resp_headers.get("content-type", ""), body)
    if status != 200:
        err(f"  {args.host} HTTP {status}; not writing")
        return 1
    write_envelope(
        source=args.source,
        fetcher=FETCHER,
        fetcher_version=FETCHER_VERSION,
        request_url=url,
        request_method="GET",
        request_headers=headers,
        response_status=status,
        response_headers=resp_headers,
        payload=payload,
    )
    n = len(payload) if isinstance(payload, list) else "?"
    err(f"  {args.host}: wrote ({n} top-level entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
