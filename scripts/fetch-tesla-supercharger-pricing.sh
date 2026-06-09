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

# Smoke test: bulk-index endpoint via the same Docker image the full
# fetch uses. Fails fast (one HTTP call) on bad cookies; succeeds in a few
# seconds. fetch_tesla_index.py returns 1 on missing TESLA_COOKIES /
# 403 / 429 — exactly the failure modes that warrant a fresh cookie mint.
#
# We capture stdout+stderr and tee them so the user still sees curl-impersonate's
# output, but we also pattern-match on the captured text so we can tell a
# cookie failure (loop) apart from an environment failure (abort). Stock
# Python tracebacks, missing modules, missing image, etc. should NOT trigger
# another cookie-mint round-trip — that's just user-hostile.
smoke_test() {
  local out
  if ! out="$(docker run --rm --env-file .env \
    -v "$(pwd)/data:/app/data" \
    -v "$(pwd)/scripts:/app/scripts" \
    -v "$(pwd)/config:/app/config:ro" \
    roadtrip-refresh:local \
    python3 /app/scripts/fetch_tesla_index.py 2>&1)"; then
    printf '%s\n' "$out"
    if grep -qE 'upstream HTTP (403|429)|Access Denied' <<<"$out"; then
      return 2  # cookie failure — loop
    fi
    return 3    # environment failure — abort
  fi
  printf '%s\n' "$out"
  return 0
}

echo "→ Building roadtrip-refresh:local (no-op if already built)…"
docker build -q -t roadtrip-refresh:local -f scripts/Dockerfile.refresh scripts/ >/dev/null

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
  set +e
  smoke_test
  rc=$?
  set -e

  case $rc in
    0)
      echo "✓ Cookies work."
      break
      ;;
    2)
      echo "✗ Smoke test failed: 403/429 from Tesla. Cookies are bound to the"
      echo "  wrong IP, expired, or missing _abck. Re-mint from the same"
      echo "  browser session and try again."
      ;;
    *)
      echo "✗ Smoke test failed for a non-cookie reason (Python error, missing"
      echo "  dep, Docker issue). See output above. Bailing — this isn't"
      echo "  something a fresh cookie mint will fix."
      exit "$rc"
      ;;
  esac
  if (( attempt == MAX_ATTEMPTS )); then
    echo "  Gave up after $MAX_ATTEMPTS attempts." >&2
    exit 1
  fi
  attempt=$(( attempt + 1 ))
done

echo
echo "→ Bulk index written by the smoke test. Walking per-slug detail now…"
docker run --rm --env-file .env \
  -v "$(pwd)/data:/app/data" \
  -v "$(pwd)/scripts:/app/scripts" \
  -v "$(pwd)/config:/app/config:ro" \
  -v "$(pwd)/.env:/app/.env:ro" \
  roadtrip-refresh:local \
  python3 /app/scripts/fetch_tesla_locations.py
echo
echo "✓ Done."
