#!/usr/bin/env bash
#
# End-to-end Tesla Supercharger pricing fetch: mint cookies → smoke-test →
# walk per-slug detail. If anything 403s/429s (cookies pinned to wrong IP,
# expired, missing _abck, OR Akamai rate-limited mid-walk), prompt for a
# fresh cURL paste and try again. fetch_tesla_locations.py is resumable —
# its is_fresh() guard skips slugs already captured this freshness window,
# so the second attempt picks up where the first left off.
#
# Why a wrapper: cookie minting is interactive (Safari → DevTools → Copy
# as cURL), Akamai sometimes rejects the first mint, and Akamai also
# sometimes rate-limits mid-walk. Doing this as one operation avoids the
# "ran the full fetch, hit 429 on slug 12 of 1500, now what?" trap.
#
# Usage:
#   make fetch-tesla-supercharger-pricing         (canonical entry point)
#   ./scripts/fetch-tesla-supercharger-pricing.sh (direct invocation)

set -euo pipefail

cd "$(dirname "$0")/.."

MAX_ATTEMPTS=${MAX_ATTEMPTS:-5}

# Run a Python script inside the refresh image. Captures stdout+stderr and
# echoes them (so the user sees progress live via tee), and returns the
# script's exit code. Patterns at call-sites decide what failures mean.
run_in_image() {
  local script=$1
  docker run --rm --env-file .env \
    -v "$(pwd)/data:/app/data" \
    -v "$(pwd)/scripts:/app/scripts" \
    -v "$(pwd)/config:/app/config:ro" \
    roadtrip-refresh:local \
    python3 "/app/scripts/$script"
}

# Smoke test: bulk-index endpoint. Fails fast (one HTTP call) on bad
# cookies; on success, the index is also written, so the locations walk
# can run immediately. fetch_tesla_index.py returns 1 on missing
# TESLA_COOKIES / 403 / 429.
#
# We capture output and pattern-match so we can tell a cookie failure
# (loop) apart from an environment failure (abort). Stock Python
# tracebacks, missing modules, missing image, etc. should NOT trigger a
# cookie-mint round-trip — that's user-hostile.
smoke_test() {
  local out
  if ! out="$(run_in_image fetch_tesla_index.py 2>&1)"; then
    printf '%s\n' "$out"
    if grep -qE 'upstream HTTP (403|429)|Access Denied' <<<"$out"; then
      return 2  # cookie failure — loop
    fi
    return 3    # environment failure — abort
  fi
  printf '%s\n' "$out"
  return 0
}

# Per-slug walk. Streams progress to the terminal as it runs (no capture
# — the walk takes minutes and the user wants to see live "wrote file…"
# lines). Exit codes:
#   0  — done, all slugs we needed are captured
#   2  — bailed after consecutive 429s; cookies need re-minting
#   *  — anything else; abort
walk_locations() {
  set +e
  run_in_image fetch_tesla_locations.py
  local rc=$?
  set -e
  return "$rc"
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
    0) ;;  # cookies work — fall through to the locations walk
    2)
      echo "✗ Smoke test failed: 403/429 from Tesla. Cookies are bound to the"
      echo "  wrong IP, expired, or missing _abck. Re-mint and try again."
      attempt=$(( attempt + 1 ))
      continue
      ;;
    *)
      echo "✗ Smoke test failed for a non-cookie reason (Python error, missing"
      echo "  dep, Docker issue). See output above. Bailing — this isn't"
      echo "  something a fresh cookie mint will fix."
      exit "$rc"
      ;;
  esac

  echo "✓ Cookies work."
  echo
  echo "→ Walking per-slug detail (resumes from any prior capture in the freshness window)…"
  walk_locations
  rc=$?
  case $rc in
    0)
      echo "✓ Done."
      exit 0
      ;;
    2)
      echo
      echo "✗ Locations walk hit consecutive 429s and bailed. Re-mint cookies"
      echo "  and try again — fetch_tesla_locations.py is resumable, so"
      echo "  already-captured slugs are skipped."
      attempt=$(( attempt + 1 ))
      ;;
    *)
      echo "✗ Locations walk failed with exit $rc (not a cookie issue)."
      exit "$rc"
      ;;
  esac
done

echo "Gave up after $MAX_ATTEMPTS attempts." >&2
exit 1
