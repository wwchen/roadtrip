#!/usr/bin/env bash
# cloudflare_sandbox.sh — per-sandbox Cloudflare DNS provisioning.
#
# Sourced by deploy.sh (on up) and sandbox_down.sh (on down).  NOT executable on
# its own.  Provides two entrypoints:
#
#   cf_sandbox_up   <fqdn>   — create/refresh the proxied CNAME for <fqdn>.
#   cf_sandbox_down <fqdn>   — delete it (idempotent; missing = success).
#
# Design (see docs/sandbox-deploys.md):
#   * DNS is the only per-sandbox Cloudflare resource these scripts manage.  We
#     create ONE explicit proxied CNAME per sandbox (roadtrip-sb-<name>.<zone> →
#     <tunnel-id>.cfargotunnel.com).  No wildcard DNS exists, so only sandboxes
#     we made (plus explicit prod records) resolve to the tunnel.
#   * The tunnel ingress rule (*.<zone> → caddy:80) is configured ONCE by hand,
#     as is the Cloudflare Access application that gates roadtrip-sb-*.<zone>
#     behind the identity providers.  Neither is touched here — a static,
#     human-set Access policy can't be misconfigured open by automation, and a
#     sandbox op can never mutate (or break) the production tunnel.  See the
#     "First-time host setup" checklist in the docs.
#
# Because Access is a static wildcard app covering roadtrip-sb-*.<zone>, a new
# sandbox is gated the instant its DNS resolves — there is no window where the
# host is reachable ungated.
#
# All Cloudflare interaction is gated on CF_API_TOKEN_FILE being readable.  When
# it is absent (local dev, CI, SSH-less hosts) every function is a logged no-op,
# so the sandbox still comes up host-locally without public exposure.
#
# Config (env vars; sane failures if unset while the token IS present):
#   CF_API_TOKEN_FILE   Path to a file containing the API token (default below).
#                       Needs Zone:DNS:Edit and Access:Apps:Read (the read is
#                       used to VERIFY the static Access gate before publishing
#                       DNS — it never edits Access).
#   CF_ZONE_NAME        DNS zone (defaults to SANDBOX_TUNNEL_ZONE).
#   CF_ACCOUNT_ID       Cloudflare account id (for the Access-app verification).
#   CF_TUNNEL_ID        Main tunnel id; CNAME target is <id>.cfargotunnel.com.
#   CF_SKIP_ACCESS_CHECK  Set to "1" to bypass the Access verification (e.g. if
#                       the token lacks Access:Apps:Read).  Discouraged: it
#                       removes the guarantee that a published host is gated.
#
# curl + python3 are required on the host (already used elsewhere in deploy.sh).

CF_API_TOKEN_FILE="${CF_API_TOKEN_FILE:-/var/lib/roadtrip-sandboxes/cloudflare_api_token}"
CF_API_BASE="${CF_API_BASE:-https://api.cloudflare.com/client/v4}"

# Host config file: CF_TUNNEL_ID / CF_ZONE_NAME live here (not in git —
# host-specific).  The GitHub workflow invokes these scripts over SSH with no
# per-run CF env, so persistent host config must come from a file.  Sourced if
# present; env vars still win because the `:-` defaults only apply when unset.
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
# Echoes the raw JSON response; returns curl's exit status.
#
# The bearer token is passed via `curl --config -` (a config file read from
# stdin), NOT `-H "Authorization: Bearer <token>"`: a header on the command line
# puts the token in the process's argv, where any local `ps`/`/proc` reader sees
# it for the life of the request.  With --config, argv contains only "--config"
# and "-"; the header (and token) arrive on stdin.
_cf_api() {
    local method="$1" path="$2" body="${3:-}"
    local token; token="$(cat "${CF_API_TOKEN_FILE}")"
    local config
    config="header = \"Authorization: Bearer ${token}\""
    if [[ -n "${body}" ]]; then
        printf '%s\nheader = "Content-Type: application/json"\n' "${config}" \
            | curl -sS -X "${method}" "${CF_API_BASE}${path}" --config - --data "${body}"
    else
        printf '%s\n' "${config}" \
            | curl -sS -X "${method}" "${CF_API_BASE}${path}" --config -
    fi
}

# ── Internal: extract a field from a CF JSON response via python3. ───────────
# Usage: echo "$json" | _cf_json <python-expr over `d`>  (d = parsed dict)
_cf_json() {
    python3 -c "import sys,json; d=json.load(sys.stdin); print(${1})" 2>/dev/null
}

# ── Internal: resolve + cache zone id. ───────────────────────────────────────
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
    # The zone response also carries the account id — use it for the Access
    # verification if the operator didn't set CF_ACCOUNT_ID explicitly.
    if [[ -z "${CF_ACCOUNT_ID:-}" ]]; then
        CF_ACCOUNT_ID="$(printf '%s' "${resp}" | _cf_json "d['result'][0]['account']['id']")"
        [[ "${CF_ACCOUNT_ID}" == "None" ]] && CF_ACCOUNT_ID=""
    fi
    return 0
}

# ── Internal: find a DNS record id by exact name (empty if none). ────────────
_cf_dns_record_id() {
    local fqdn="$1"
    _cf_api GET "/zones/${CF_ZONE_ID}/dns_records?type=CNAME&name=${fqdn}" \
        | _cf_json "(d['result'][0]['id'] if d.get('result') else '')"
}

# ── Internal: verify a static Access app actually gates <fqdn>. ──────────────
# The static-wildcard-app model only protects a sandbox if that app EXISTS and
# has at least one `allow` policy.  Publishing DNS before confirming this would
# expose an unauthenticated seed-admin backend, so cf_sandbox_up calls this
# first and fails closed on a negative/unverifiable result.
#
# Prints "ok" to stdout when a self-hosted Access app whose (possibly wildcard)
# domain matches <fqdn> has >=1 allow policy; prints a reason otherwise.  Uses
# only Access:Apps:Read.  fnmatch handles the `roadtrip-sb-*.floo.ca` wildcard.
_cf_access_gate_status() {
    local fqdn="$1"
    if [[ -z "${CF_ACCOUNT_ID:-}" ]]; then
        echo "no-account-id"; return 0
    fi
    _cf_api GET "/accounts/${CF_ACCOUNT_ID}/access/apps" | python3 - "${fqdn}" <<'PY'
import sys, json, fnmatch
fqdn = sys.argv[1]
try:
    d = json.load(sys.stdin)
except Exception:
    print("unreadable"); sys.exit(0)
if not d.get("success"):
    print("api-error"); sys.exit(0)
for app in d.get("result", []):
    domains = list(app.get("self_hosted_domains") or [])
    if app.get("domain"):
        domains.append(app["domain"])
    if not any(fnmatch.fnmatch(fqdn, dom) for dom in domains):
        continue
    policies = app.get("policies") or []
    if any(p.get("decision") == "allow" for p in policies):
        print("ok"); sys.exit(0)
    print("no-allow-policy"); sys.exit(0)
print("no-app")
PY
}

# ── Public: create/refresh the proxied CNAME for a sandbox host. ─────────────
cf_sandbox_up() {
    local fqdn="$1"
    if ! _cf_enabled; then
        echo "==> cf: token absent (${CF_API_TOKEN_FILE}); skipping DNS for ${fqdn}"
        return 0
    fi
    _cf_resolve_zone || return 1
    if [[ -z "${CF_TUNNEL_ID:-}" ]]; then
        echo "cf: CF_TUNNEL_ID unset — cannot point ${fqdn} at the tunnel" >&2
        return 1
    fi
    local target="${CF_TUNNEL_ID}.cfargotunnel.com"

    # ── Verify the Access gate BEFORE publishing DNS (fail closed). ──────────
    # DNS is what makes the host publicly reach the seed-admin backend; if the
    # static wildcard Access app is missing/mistyped/policy-less, publishing
    # would expose it ungated.  So confirm a matching app with an allow policy
    # exists first.  CF_SKIP_ACCESS_CHECK=1 opts out (discouraged).
    if [[ "${CF_SKIP_ACCESS_CHECK:-}" != "1" ]]; then
        local gate; gate="$(_cf_access_gate_status "${fqdn}")"
        if [[ "${gate}" != "ok" ]]; then
            echo "cf: Access gate not verified for ${fqdn} (${gate}); refusing to publish DNS for an ungated seed-admin backend. Fix the static Access app for roadtrip-sb-*.${CF_ZONE_NAME:-${SANDBOX_TUNNEL_ZONE}} (needs an allow policy), or set CF_SKIP_ACCESS_CHECK=1 to override." >&2
            return 1
        fi
        echo "==> cf: Access gate verified for ${fqdn}"
    fi

    local existing; existing="$(_cf_dns_record_id "${fqdn}")"
    local dns_body
    dns_body="$(printf '{"type":"CNAME","name":"%s","content":"%s","proxied":true}' "${fqdn}" "${target}")"
    if [[ -n "${existing}" ]]; then
        echo "==> cf: DNS ${fqdn} exists; updating"
        if [[ "$(_cf_api PUT "/zones/${CF_ZONE_ID}/dns_records/${existing}" "${dns_body}" | _cf_json "d.get('success')")" != "True" ]]; then
            echo "cf: DNS update failed for ${fqdn}" >&2
            return 1
        fi
    else
        echo "==> cf: creating DNS ${fqdn} → ${target}"
        local resp; resp="$(_cf_api POST "/zones/${CF_ZONE_ID}/dns_records" "${dns_body}")"
        if [[ "$(printf '%s' "${resp}" | _cf_json "d.get('success')")" != "True" ]]; then
            echo "cf: DNS create failed: $(printf '%s' "${resp}" | _cf_json "d.get('errors')")" >&2
            return 1
        fi
    fi
    return 0
}

# ── Public: delete the proxied CNAME for a sandbox host (idempotent). ─────────
cf_sandbox_down() {
    local fqdn="$1"
    if ! _cf_enabled; then
        echo "==> cf: token absent; skipping DNS teardown for ${fqdn}"
        return 0
    fi
    if ! _cf_resolve_zone; then
        echo "cf: could not resolve zone; DNS for ${fqdn} may be orphaned — remove manually" >&2
        return 1
    fi
    local rec; rec="$(_cf_dns_record_id "${fqdn}")"
    if [[ -n "${rec}" ]]; then
        echo "==> cf: deleting DNS ${fqdn} (${rec})"
        if [[ "$(_cf_api DELETE "/zones/${CF_ZONE_ID}/dns_records/${rec}" | _cf_json "d.get('success')")" != "True" ]]; then
            echo "cf: DNS delete failed for ${fqdn}" >&2
            return 1
        fi
    fi
    return 0
}
