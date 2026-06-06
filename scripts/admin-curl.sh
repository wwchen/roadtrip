#!/usr/bin/env bash
# Admin API thin shim. POSTs to /api/admin/data/{fetch|import}[/{target}]
# synchronously and exits with the run's status code (0 = completed/noop,
# 1 = failed). Tilt uses this as a `cmd=` so the resource pane reflects
# pass/fail; `make data-fetch` / `make data-import` use it from a terminal.
#
# Auth boundary lives upstream at the Cloudflare Zero Trust path rule on
# /api/admin/* (existing tunnel). Locally we hit 127.0.0.1 directly. If you
# ever expose dev to the internet, bind admin routes to loopback only.
#
# Usage:
#   scripts/admin-curl.sh fetch                # fan out across all targets
#   scripts/admin-curl.sh fetch campgrounds    # one target
#   scripts/admin-curl.sh import               # all targets
#   scripts/admin-curl.sh import planet-fitness
#   ADMIN_BASE=https://roadtrip.floo.ca scripts/admin-curl.sh fetch campgrounds

set -euo pipefail

verb="${1:-}"
target="${2:-}"
case "$verb" in
    fetch|import) ;;
    "")
        echo "usage: $0 {fetch|import} [target]" >&2
        exit 2
        ;;
    *)
        echo "unknown verb: $verb (expected 'fetch' or 'import')" >&2
        exit 2
        ;;
esac

BASE="${ADMIN_BASE:-http://127.0.0.1:8765}"
path="/api/admin/data/$verb"
[ -n "$target" ] && path="$path/$target"

# --fail-with-body: non-zero on 4xx/5xx but still print the JSON body so
# the user sees the failed_phase / known list. -sS keeps stderr quiet on
# success but shows curl errors. --max-time covers the long campgrounds
# pipeline (enricher worst case ~10 min); admin routes have their own
# per-phase timeout.
exec curl --fail-with-body -sS --max-time 1800 \
    -X POST -H 'Content-Type: application/json' \
    "$BASE$path"
