#!/usr/bin/env bash
#
# Paste a "Copy as cURL" from Chrome DevTools, extract the Cookie header,
# save it to .env as TESLA_COOKIES, and smoke-test against the findus API.
#
# Usage: ./refresh-cookies.sh
#
# Tesla cookies (_abck in particular) are IP-bound: pull them from a browser
# on the same network where this script runs (same home Wi-Fi, same VPN, etc).

set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=".env"

cat <<'EOF'
Refresh Tesla findus cookies
────────────────────────────
  1. Open https://www.tesla.com/findus?functionType=supercharger in Chrome.
  2. Click any Supercharger on the map.
  3. Open DevTools (⌥⌘I) → Network tab.
  4. Find the `get-charger-details?...` request.
  5. Right-click it → Copy → Copy as cURL.

EOF

# Reading the cURL blob from a terminal paste is unreliable: macOS's canonical-mode
# line buffer truncates long lines silently. Prefer the clipboard; fall back to
# $EDITOR on a temp file so nothing is bounded by terminal line limits.
curl_input=""

if command -v pbpaste >/dev/null 2>&1; then
  clip="$(pbpaste)"
  if [[ "$clip" == *"curl "* && "$clip" == *"tesla.com"* ]]; then
    echo "Found a tesla.com cURL on the clipboard (${#clip} chars)."
    read -r -p "Use it? [Y/n] " yn
    [[ "$yn" != "n" && "$yn" != "N" ]] && curl_input="$clip"
  fi
fi

if [[ -z "$curl_input" ]]; then
  editor="${VISUAL:-${EDITOR:-vi}}"
  tmp_in="$(mktemp -t tesla-curl).sh"
  cat > "$tmp_in" <<'HDR'
# Paste the "Copy as cURL" blob below this line, save, and quit.
# Lines starting with # are ignored.
HDR
  echo "Opening $editor — paste the cURL, save, and quit."
  "$editor" "$tmp_in"
  curl_input="$(grep -v '^#' "$tmp_in")"
  rm -f "$tmp_in"
fi

if [[ -z "$curl_input" ]]; then
  echo "error: no input" >&2
  exit 1
fi

# Extract the cookie string after -b or --cookie (single or double quoted).
cookies="$(
  CURL_INPUT="$curl_input" python3 - <<'PYEOF'
import os, re, sys
raw = os.environ["CURL_INPUT"]
# Chrome's "Copy as cURL" uses `-b '…'`; Safari's uses `-H 'Cookie: …'`.
patterns = [
    r"-b\s+'((?:[^'\\]|\\.)*)'",
    r'-b\s+"((?:[^"\\]|\\.)*)"',
    r"--cookie\s+'((?:[^'\\]|\\.)*)'",
    r'--cookie\s+"((?:[^"\\]|\\.)*)"',
    r"-H\s+'[Cc]ookie:\s*((?:[^'\\]|\\.)*)'",
    r'-H\s+"[Cc]ookie:\s*((?:[^"\\]|\\.)*)"',
]
for p in patterns:
    m = re.search(p, raw)
    if m:
        print(m.group(1))
        sys.exit(0)
sys.exit(1)
PYEOF
)" || {
  echo "error: couldn't find a -b '…' cookie block in the pasted cURL." >&2
  echo "       expected something like: curl '...' -b 'ak_bmsc=...; _abck=...; ...' -H '...'" >&2
  exit 1
}

# Sanity: Tesla findus cookies always include Akamai's _abck plus at least
# one bm_* / ak_bmsc companion cookie. The exact companion varies by session.
if ! [[ "$cookies" == *"_abck="* ]] || ! [[ "$cookies" == *"ak_bmsc="* || "$cookies" == *"bm_sc="* || "$cookies" == *"bm_sz="* ]]; then
  echo "warn: pasted cookie string doesn't look like tesla.com's (missing _abck / bm_*)."
  read -r -p "      write it anyway? [y/N] " yn
  [[ "$yn" == "y" || "$yn" == "Y" ]] || exit 1
fi

# Replace any existing TESLA_COOKIES line, preserve everything else.
if [[ -f "$ENV_FILE" ]]; then
  tmp="$(mktemp)"
  grep -v '^TESLA_COOKIES=' "$ENV_FILE" > "$tmp" || true
  mv "$tmp" "$ENV_FILE"
else
  touch "$ENV_FILE"
fi
printf 'TESLA_COOKIES=%s\n' "$cookies" >> "$ENV_FILE"
echo "✓ wrote TESLA_COOKIES (${#cookies} chars) to $ENV_FILE"

# Smoke-test against a known site. Using Ashburn, VA — the first Supercharger
# in the US, unlikely to ever be decommissioned.
echo
echo "Smoke-testing findus endpoint…"
tmp_resp="$(mktemp)"
status=$(curl -sS -o "$tmp_resp" -w '%{http_code}' --http2 \
  'https://www.tesla.com/api/findus/get-charger-details?locationSlug=ashburnsupercharger&programType=supercharger&locale=en-US&isInHkMoTw=false' \
  -H 'accept: application/json, text/plain, */*' \
  -H 'accept-language: en-US,en;q=0.9' \
  -b "$cookies" \
  -H 'referer: https://www.tesla.com/findus?location=ashburnsupercharger&functionType=supercharger' \
  -H 'sec-ch-ua: "Not/A)Brand";v="99", "Chromium";v="148"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "macOS"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36')

if [[ "$status" == "200" ]]; then
  name="$(python3 -c "import json,sys; d=json.load(open('$tmp_resp')); print(d['data']['data'].get('name',''))")"
  echo "✓ HTTP 200 — $name"
else
  echo "✗ HTTP $status — cookies may be stale or IP-bound to a different network."
  echo "  Response head:"
  head -c 300 "$tmp_resp" | sed 's/^/    /'
  echo
fi
rm -f "$tmp_resp"

# If the local server is running, offer to restart so it picks up the new cookies.
if command -v lsof >/dev/null 2>&1; then
  pid="$(lsof -i :8765 -sTCP:LISTEN -Pn 2>/dev/null | awk 'NR==2 {print $2}')"
  if [[ -n "$pid" ]] && ps -p "$pid" -o command= 2>/dev/null | grep -q python; then
    echo
    read -r -p "Local server running (pid $pid). Restart to pick up new cookies? [Y/n] " yn
    if [[ "$yn" != "n" && "$yn" != "N" ]]; then
      kill "$pid"
      sleep 0.5
      nohup python3 server.py > /tmp/roadtrip-server.log 2>&1 &
      disown
      echo "✓ server restarted (pid $!)"
    fi
  fi
fi

# Same courtesy for Docker.
if command -v docker >/dev/null 2>&1 && docker compose ps --services 2>/dev/null | grep -q '^app$'; then
  echo
  read -r -p "Docker app container detected. Restart it? [Y/n] " yn
  if [[ "$yn" != "n" && "$yn" != "N" ]]; then
    docker compose restart app
  fi
fi

echo
echo "Done. Pricing popups will work until Tesla rotates the cookies (usually within a day)."
