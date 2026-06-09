#!/usr/bin/env bash
#
# End-to-end Tesla Supercharger pricing fetch: mint cookies → smoke-test →
# run the full bulk index + per-slug detail fetch. If the smoke test
# 403s/429s (cookies pinned to wrong IP, expired, missing _abck), prompt
# for a fresh cURL paste and try again. Loops until cookies work or the
# user bails out.
#
# Why a wrapper: cookie minting is interactive (Safari → DevTools → Copy
# as cURL), and a fresh-mint sometimes still fails Akamai's bot wall on
# the first try. Doing this as one operation avoids the "ran the full
# fetch, hit 429 on slug 12 of 1500, now what?" trap.
#
# Usage:
#   make fetch-tesla-supercharger-pricing         (canonical entry point)
#   ./scripts/fetch-tesla-supercharger-pricing.sh (direct invocation)

set -euo pipefail

cd "$(dirname "$0")/.."

MAX_ATTEMPTS=${MAX_ATTEMPTS:-5}

# Smoke test: bulk-index endpoint via the same Docker image the full refresh
# uses. Fails fast (one HTTP call) on bad cookies; succeeds in a few seconds.
# The script returns 1 on 403/429 / missing TESLA_COOKIES, which is what we
# want — we don't want to write a bogus envelope to data/raw/ either.
smoke_test() {
  docker run --rm --env-file .env \
    -v "$(pwd)/data:/app/data" \
    -v "$(pwd)/scripts:/app/scripts" \
    roadtrip-refresh:local \
    python3 /app/scripts/fetch_tesla_index.py
}

echo "→ Building refresh image (no-op if already built)…"
make refresh-image >/dev/null

attempt=1
while (( attempt <= MAX_ATTEMPTS )); do
  echo
  echo "── Attempt $attempt/$MAX_ATTEMPTS ──"
  echo "1. Open tesla.com/findus → click any Supercharger →"
  echo "   DevTools → Network → right-click get-charger-details → Copy as cURL."
  echo "2. Press Enter when copied."
  read -r _

  ./scripts/refresh-tesla-cookies.sh

  echo
  echo "→ Smoke-testing cookies (fetch bulk index)…"
  if smoke_test; then
    echo "✓ Cookies work."
    break
  fi

  echo "✗ Smoke test failed (likely 403/429 — Akamai pinned to wrong IP, or"
  echo "  cookies missing _abck). Re-mint from the same browser session and"
  echo "  try again."
  if (( attempt == MAX_ATTEMPTS )); then
    echo "  Gave up after $MAX_ATTEMPTS attempts." >&2
    exit 1
  fi
  attempt=$(( attempt + 1 ))
done

echo
echo "→ Running full Tesla refresh (bulk index already written; now per-slug detail)…"
make refresh-superchargers
echo
echo "✓ Done."
