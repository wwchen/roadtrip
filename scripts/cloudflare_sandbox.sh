#!/usr/bin/env bash
# cloudflare_sandbox.sh — per-sandbox Cloudflare DNS + Access provisioning.
#
# Sourced by deploy.sh (on up) and sandbox_down.sh (on down).  NOT executable on
# its own.  Provides two entrypoints:
#
#   cf_sandbox_up   <fqdn>   — create the proxied CNAME + a Cloudflare Access app
#                              gating <fqdn> behind the configured IdP.
#   cf_sandbox_down <fqdn>   — delete both (idempotent; missing = success).
#
# Design (see docs/sandbox-deploys.md):
#   * DNS is the gate that decides which hostnames reach the tunnel.  We create
#     ONE explicit proxied CNAME per sandbox (roadtrip-sb-<name>.<zone> →
#     <tunnel-id>.cfargotunnel.com).  No wildcard DNS exists, so only sandboxes
#     we made (plus explicit prod records) resolve to the tunnel.
#   * The tunnel's ingress rule (*.<zone> → caddy:80) is configured ONCE by hand
#     (not here) — this library never mutates the production tunnel config, so a
#     sandbox op can never break roadtrip.floo.ca.
#   * Access: sandboxes disable app auth (ROADTRIP_SANDBOX_ASSUME_USER=true) and
#     resolve every request to a seeded ADMIN user, so a public host would be
#     open admin.  Each sandbox host gets a self-hosted Access application whose
#     policy allows the configured IdP identities only.
#
# All Cloudflare interaction is gated on CF_API_TOKEN_FILE being readable.  When
# it is absent (local dev, CI, SSH-less hosts) every function is a logged no-op,
# so the sandbox still comes up host-locally without exposure.
#
# Required config (env vars; sane failures if unset while the token IS present):
#   CF_API_TOKEN_FILE   Path to a file containing the API token (default below).
#   CF_ACCOUNT_ID       Cloudflare account id (resolved from the zone if unset).
#   CF_ZONE_NAME        DNS zone (defaults to SANDBOX_TUNNEL_ZONE).
#   CF_TUNNEL_ID        Main tunnel id; CNAME target is <id>.cfargotunnel.com.
#   CF_ACCESS_IDP_IDS   Comma-separated Access identity-provider ids to allow.
#   CF_ACCESS_EMAILS    Optional comma-separated allowed emails (extra include).
#
# curl + python3 are required on the host (already used elsewhere in deploy.sh).

CF_API_TOKEN_FILE="${CF_API_TOKEN_FILE:-/var/lib/roadtrip-sandboxes/cloudflare_api_token}"
CF_API_BASE="${CF_API_BASE:-https://api.cloudflare.com/client/v4}"

# Host config file: CF_TUNNEL_ID / CF_ACCESS_IDP_IDS / CF_ACCESS_EMAILS /
# CF_ACCOUNT_ID live here (not in git — host-specific).  The GitHub workflow
# invokes these scripts over SSH with no per-run CF env, so persistent host
# config must come from a file.  Sourced if present; env vars still win because
# the `:-` defaults below only apply when unset.
CF_SANDBOX_CONFIG_FILE="${CF_SANDBOX_CONFIG_FILE:-/var/lib/roadtrip-sandboxes/cloudflare.env}"
if [[ -f "${CF_SANDBOX_CONFIG_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${CF_SANDBOX_CONFIG_FILE}"
fi

# ── Internal: is Cloudflare provisioning enabled on this host? ───────────────
_cf_enabled() {
    [[ -s "${CF_API_TOKEN_FILE}" ]]
}

# ── Internal: authenticated curl against the CF API. ─────────────────────────
# Usage: _cf_api <method> <path> [json-body]
# Echoes the raw JSON response; returns curl's exit status.  The token is read
# per-call from the file and never placed in the process args or logged.
_cf_api() {
    local method="$1" path="$2" body="${3:-}"
    local token; token="$(cat "${CF_API_TOKEN_FILE}")"
    if [[ -n "${body}" ]]; then
        curl -sS -X "${method}" "${CF_API_BASE}${path}" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            --data "${body}"
    else
        curl -sS -X "${method}" "${CF_API_BASE}${path}" \
            -H "Authorization: Bearer ${token}"
    fi
}

# ── Internal: extract a field from a CF JSON response via python3. ───────────
# Usage: echo "$json" | _cf_json <python-expr over `d`>  (d = parsed dict)
_cf_json() {
    python3 -c "import sys,json; d=json.load(sys.stdin); print(${1})" 2>/dev/null
}

# ── Internal: resolve + cache zone id (and account id if unset). ─────────────
_cf_resolve_zone() {
    local zone="${CF_ZONE_NAME:-${SANDBOX_TUNNEL_ZONE}}"
    if [[ -z "${zone}" ]]; then
        echo "cf: no zone (set CF_ZONE_NAME or SANDBOX_TUNNEL_ZONE)" >&2
        return 1
    fi
    local resp; resp="$(_cf_api GET "/zones?name=${zone}")"
    CF_ZONE_ID="$(printf '%s' "${resp}" | _cf_json "d['result'][0]['id']")"
    if [[ -z "${CF_ZONE_ID}" || "${CF_ZONE_ID}" == "None" ]]; then
        echo "cf: could not resolve zone id for ${zone}" >&2
        return 1
    fi
    if [[ -z "${CF_ACCOUNT_ID:-}" ]]; then
        CF_ACCOUNT_ID="$(printf '%s' "${resp}" | _cf_json "d['result'][0]['account']['id']")"
    fi
    return 0
}

# ── Internal: find a DNS record id by exact name (empty if none). ────────────
_cf_dns_record_id() {
    local fqdn="$1"
    _cf_api GET "/zones/${CF_ZONE_ID}/dns_records?type=CNAME&name=${fqdn}" \
        | _cf_json "(d['result'][0]['id'] if d.get('result') else '')"
}

# ── Internal: find an Access app id by exact domain (empty if none). ─────────
_cf_access_app_id() {
    local fqdn="$1"
    _cf_api GET "/accounts/${CF_ACCOUNT_ID}/access/apps" \
        | _cf_json "next((a['id'] for a in d.get('result',[]) if a.get('domain')=='${fqdn}'), '')"
}

# ── Internal: Access include[] array from IdP ids + emails. ──────────────────
# With neither set, defaults to [{"everyone": {}}] — in Cloudflare Access that
# means "anyone who AUTHENTICATES via a configured IdP" (Google/GitHub here),
# NOT anonymous access.  So the host is always login-gated; CF_ACCESS_* only
# NARROWS it to specific IdPs/emails.
_cf_access_includes() {
    python3 - "$@" <<'PY'
import sys, json
idps = [x for x in sys.argv[1].split(',') if x]
emails = [x for x in sys.argv[2].split(',') if x] if len(sys.argv) > 2 else []
inc = [{"login_method": {"id": i}} for i in idps]
inc += [{"email": {"email": e}} for e in emails]
if not inc:
    inc = [{"everyone": {}}]  # authenticated via any configured IdP
print(json.dumps(inc))
PY
}

# ── Public: provision DNS + Access for a sandbox host. ───────────────────────
cf_sandbox_up() {
    local fqdn="$1"
    if ! _cf_enabled; then
        echo "==> cf: token absent (${CF_API_TOKEN_FILE}); skipping DNS/Access for ${fqdn}"
        return 0
    fi
    _cf_resolve_zone || return 1

    if [[ -z "${CF_TUNNEL_ID:-}" ]]; then
        echo "cf: CF_TUNNEL_ID unset — cannot point ${fqdn} at the tunnel" >&2
        return 1
    fi
    local target="${CF_TUNNEL_ID}.cfargotunnel.com"

    # ── DNS CNAME (proxied) ──────────────────────────────────────────────────
    local existing; existing="$(_cf_dns_record_id "${fqdn}")"
    local dns_body
    dns_body="$(printf '{"type":"CNAME","name":"%s","content":"%s","proxied":true}' "${fqdn}" "${target}")"
    if [[ -n "${existing}" ]]; then
        echo "==> cf: DNS ${fqdn} exists; updating"
        _cf_api PUT "/zones/${CF_ZONE_ID}/dns_records/${existing}" "${dns_body}" >/dev/null
    else
        echo "==> cf: creating DNS ${fqdn} → ${target}"
        local resp; resp="$(_cf_api POST "/zones/${CF_ZONE_ID}/dns_records" "${dns_body}")"
        if [[ "$(printf '%s' "${resp}" | _cf_json "d.get('success')")" != "True" ]]; then
            echo "cf: DNS create failed: $(printf '%s' "${resp}" | _cf_json "d.get('errors')")" >&2
            return 1
        fi
    fi

    # ── Access application + policy ──────────────────────────────────────────
    # Idempotent: skip creation if an app already covers this exact domain.
    local app_id; app_id="$(_cf_access_app_id "${fqdn}")"
    if [[ -n "${app_id}" ]]; then
        echo "==> cf: Access app for ${fqdn} exists (${app_id}); leaving in place"
        return 0
    fi
    # Always non-empty: falls back to {"everyone":{}} (auth-gated via any IdP).
    local includes; includes="$(_cf_access_includes "${CF_ACCESS_IDP_IDS:-}" "${CF_ACCESS_EMAILS:-}")"
    echo "==> cf: creating Access app for ${fqdn}"
    local app_body
    app_body="$(python3 - "${fqdn}" "${includes}" <<'PY'
import sys, json
fqdn, includes = sys.argv[1], json.loads(sys.argv[2])
print(json.dumps({
    "name": f"sandbox {fqdn}",
    "domain": fqdn,
    "type": "self_hosted",
    "session_duration": "24h",
    "policies": [{
        "name": "sandbox-allow",
        "decision": "allow",
        "include": includes,
    }],
}))
PY
)"
    local resp; resp="$(_cf_api POST "/accounts/${CF_ACCOUNT_ID}/access/apps" "${app_body}")"
    if [[ "$(printf '%s' "${resp}" | _cf_json "d.get('success')")" != "True" ]]; then
        echo "cf: Access app create failed: $(printf '%s' "${resp}" | _cf_json "d.get('errors')")" >&2
        return 1
    fi
    echo "==> cf: Access app created for ${fqdn}"
    return 0
}

# ── Public: remove DNS + Access for a sandbox host (idempotent). ─────────────
cf_sandbox_down() {
    local fqdn="$1"
    if ! _cf_enabled; then
        echo "==> cf: token absent; skipping DNS/Access teardown for ${fqdn}"
        return 0
    fi
    _cf_resolve_zone || return 1

    local app_id; app_id="$(_cf_access_app_id "${fqdn}")"
    if [[ -n "${app_id}" ]]; then
        echo "==> cf: deleting Access app ${app_id} (${fqdn})"
        _cf_api DELETE "/accounts/${CF_ACCOUNT_ID}/access/apps/${app_id}" >/dev/null || true
    fi
    local rec; rec="$(_cf_dns_record_id "${fqdn}")"
    if [[ -n "${rec}" ]]; then
        echo "==> cf: deleting DNS ${fqdn} (${rec})"
        _cf_api DELETE "/zones/${CF_ZONE_ID}/dns_records/${rec}" >/dev/null || true
    fi
    return 0
}
