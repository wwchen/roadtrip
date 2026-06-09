#!/usr/bin/env bash
#
# Mint Tesla cookies into this repo's .env. Use before any Tesla-touching
# refresh (fetch_tesla_index.py / fetch_tesla_locations.py / refresh-superchargers).
#
# Akamai binds _abck to the egress IP that minted it, so cookies captured
# here only work for fetches that egress from this laptop's IP — which
# matches `docker run` on the laptop. There's no longer a remote/prod
# variant of this script; production refresh runs on a host that mints
# its own cookies out-of-band.
#
# Usage: open tesla.com/findus in Chrome/Safari, click any Supercharger,
#   DevTools → Network → right-click get-charger-details → Copy as cURL,
#   then run this script.

set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v pbpaste >/dev/null 2>&1; then
  echo "error: pbpaste not found (this script expects macOS)" >&2
  exit 1
fi

curl_input="$(pbpaste)"
if [[ "$curl_input" != *"curl"* ]]; then
  echo "error: clipboard doesn't look like a cURL blob (no 'curl' token found)." >&2
  echo "       Open tesla.com/findus → click a Supercharger →" >&2
  echo "       DevTools Network → right-click get-charger-details → Copy as cURL." >&2
  exit 1
fi

cookies="$(printf '%s' "$curl_input" | python3 scripts/extract-cookies.py)" || exit 1
echo "✓ Extracted cookies (${#cookies} chars)"

if ! [[ "$cookies" == *"_abck="* ]] || \
   ! [[ "$cookies" == *"ak_bmsc="* || "$cookies" == *"bm_sc="* || "$cookies" == *"bm_sz="* ]]; then
  echo "⚠  Missing _abck or bm_*/ak_bmsc — this may not be a findus cURL."
  read -r -p "   Write anyway? [y/N] " yn
  [[ "$yn" == "y" || "$yn" == "Y" ]] || exit 1
fi

tmp=$(mktemp)
grep -v '^TESLA_COOKIES=' .env > "$tmp" || true
printf 'TESLA_COOKIES=%s\n' "$cookies" >> "$tmp"
mv "$tmp" .env
chmod 600 .env
echo "✓ wrote .env (TESLA_COOKIES=${#cookies} chars)"
echo
echo "Test now:"
echo "  make refresh-image"
echo "  docker run --rm --env-file .env \\"
echo "    -v \"\$(pwd)/data:/app/data\" -v \"\$(pwd)/scripts:/app/scripts\" \\"
echo "    -v \"\$(pwd)/config:/app/config:ro\" \\"
echo "    roadtrip-refresh:local python3 /app/scripts/fetch_tesla_index.py && python3 /app/scripts/fetch_tesla_locations.py --limit 3"
