#!/usr/bin/env bash
#
# Capture Tesla findus cookies from *this machine's* clipboard and push them
# to the mini's .env, then restart the app container.
#
# Why this exists: Akamai binds _abck to the egress IP that minted it. The
# pricing API call runs from the mini, so cookies must be minted from the
# mini's IP. Using Tailscale's exit-node feature, this laptop can egress via
# the mini for cookie capture — we don't have to ssh+screenshare.
#
# Usage: ./refresh-cookies-remote.sh <ssh-host> <ssh-user> <remote-repo-dir>

set -euo pipefail

HOST="${1:?ssh host required}"
USER="${2:?ssh user required}"
DIR="${3:?remote repo dir required}"

cd "$(dirname "$0")/.."

# --- Tailscale exit-node check ---------------------------------------------
# Soft check: we just confirm *some* exit node is active, since the Tailscale
# peer name is usually not the same as the SSH alias. We can't cheaply verify
# it's *the right* exit node without an HTTP round-trip; that's what the
# smoke test at the end catches. If no exit node at all is active, warn.
if command -v tailscale >/dev/null 2>&1; then
  exit_node="$(tailscale status --json 2>/dev/null \
    | python3 -c 'import json,sys
d=json.load(sys.stdin)
for p in d.get("Peer", {}).values():
  if p.get("ExitNode"):
    print(p.get("HostName",""))
    break' 2>/dev/null || true)"
  if [[ -z "$exit_node" ]]; then
    echo "⚠  No Tailscale exit node active. Cookies will be bound to this"
    echo "   laptop's egress IP, not the mini's — pricing calls from the"
    echo "   mini will 403. Turn on the exit node and re-capture."
    read -r -p "   Proceed anyway? [y/N] " yn
    [[ "$yn" == "y" || "$yn" == "Y" ]] || exit 1
  else
    echo "✓ Tailscale egress via '$exit_node'"
  fi
fi

# --- Read cURL from clipboard ----------------------------------------------
if ! command -v pbpaste >/dev/null 2>&1; then
  echo "error: pbpaste not found (this script expects macOS)" >&2
  exit 1
fi
curl_input="$(pbpaste)"
if [[ "$curl_input" != *"curl"* ]]; then
  echo "error: clipboard doesn't look like a cURL blob (no 'curl' token found)." >&2
  echo "       Open tesla.com/findus in Chrome or Safari → click a Supercharger," >&2
  echo "       DevTools → Network → right-click get-charger-details → Copy as cURL," >&2
  echo "       then re-run this command." >&2
  exit 1
fi

# --- Extract cookies -------------------------------------------------------
cookies="$(printf '%s' "$curl_input" | python3 scripts/extract-cookies.py)" || exit 1
cookie_len="${#cookies}"
echo "✓ Extracted cookies ($cookie_len chars)"

# Sanity: Tesla cookies always include _abck + at least one bm_*/ak_bmsc.
if ! [[ "$cookies" == *"_abck="* ]] || \
   ! [[ "$cookies" == *"ak_bmsc="* || "$cookies" == *"bm_sc="* || "$cookies" == *"bm_sz="* ]]; then
  echo "⚠  Missing _abck or bm_*/ak_bmsc — this may not be a findus cURL."
  read -r -p "   Write anyway? [y/N] " yn
  [[ "$yn" == "y" || "$yn" == "Y" ]] || exit 1
fi

# --- Push to the mini ------------------------------------------------------
# We base64-encode the cookies locally and embed the encoded blob into the
# remote script body. Avoids:
#   - Putting cookies on argv (secrets visible in `ps`).
#   - Stdin conflicts (the script body itself travels on stdin via `bash -s`).
#   - Shell quoting nightmares with cookies that contain $ / ' / ; etc.
# DEPLOY_DIR is passed literally so `~` expands on the remote side.
encoded="$(printf '%s' "$cookies" | base64 | tr -d '\n')"
echo "→ Pushing to $USER@$HOST:$DIR/.env"
ssh -T "$HOST" -l "$USER" "REMOTE_DIR='$DIR' COOKIE_B64='$encoded' bash -s" <<'REMOTE'
set -euo pipefail
dir="$(eval echo "$REMOTE_DIR")"  # expand ~ against remote $HOME
cd "$dir"
new="$(printf '%s' "$COOKIE_B64" | base64 -d)"
tmp=$(mktemp)
grep -v '^TESLA_COOKIES=' .env > "$tmp" || true
printf 'TESLA_COOKIES=%s\n' "$new" >> "$tmp"
mv "$tmp" .env
chmod 600 .env
echo "✓ wrote .env (TESLA_COOKIES=${#new} chars)"
export PATH=/usr/local/bin:$PATH
docker compose --env-file /dev/null restart app >/dev/null
echo "✓ app container restarted"
REMOTE

# --- Smoke-test through the public endpoint --------------------------------
echo "→ Smoke-testing https://roadtrip.floo.ca/api/pricing/32498 (ashburnsupercharger)"
sleep 2  # give the app a beat to come back up
status=$(curl -sS -o /tmp/rt-smoke.txt -w '%{http_code}' \
  "https://roadtrip.floo.ca/api/pricing/32498" || echo '000')
if [[ "$status" == "200" ]]; then
  echo "✓ HTTP 200 — cookies are live"
else
  echo "✗ HTTP $status"
  head -c 300 /tmp/rt-smoke.txt
  echo
  echo "  (If 429 cpr_chlge: cookies are unpromoted — capture again right after"
  echo "   clicking a Supercharger in the same Safari session.)"
fi
rm -f /tmp/rt-smoke.txt
