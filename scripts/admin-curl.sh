#!/usr/bin/env bash
# Admin API thin shim. POSTs to /api/admin/ingest/{target} synchronously and
# exits with the run's status code (0 = completed, 1 = failed). Tilt uses this
# as a `cmd=` so the resource pane reflects pass/fail; `make pois-refresh`
# uses it from a terminal.
#
# Auth boundary lives upstream at the Cloudflare Zero Trust path rule on
# /api/admin/* (existing tunnel). Locally we hit 127.0.0.1 directly. If you
# ever expose dev to the internet, bind admin routes to loopback only.
#
# Usage:
#   scripts/admin-curl.sh ingest <target>
#   ADMIN_BASE=https://roadtrip.floo.ca scripts/admin-curl.sh ingest campgrounds

set -euo pipefail

cmd="${1:-}"
target="${2:-}"
if [[ -z "$cmd" || -z "$target" ]]; then
    echo "usage: $0 ingest <target>" >&2
    exit 2
fi
if [[ "$cmd" != "ingest" ]]; then
    echo "unknown command: $cmd (only 'ingest' is supported)" >&2
    exit 2
fi

BASE="${ADMIN_BASE:-http://127.0.0.1:8765}"
url="$BASE/api/admin/ingest/$target?triggered_by=cli"

# --fail-with-body: non-zero on 4xx/5xx but still print the JSON body so
# the user sees the failed_phase / known list. -sS keeps stderr quiet on
# success but shows curl errors. --max-time covers the long campgrounds
# pipeline (enricher worst case ~10 min); admin routes have their own
# per-phase timeout.
exec curl --fail-with-body -sS --max-time 1800 \
    -X POST -H 'Content-Type: application/json' \
    "$url"
