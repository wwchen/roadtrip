#!/usr/bin/env bash
# sandbox_down.sh <name>
#
# Tear down a sandbox instance created by sandbox_up.sh.
# Idempotent: missing Compose project, missing Caddy snippet, or missing marker
# are all treated as already-gone (not an error).
#
# Steps:
#   1. docker compose down -v  (removes containers + the postgres-data volume)
#   2. Remove the Caddy vhost snippet and reload Caddy
#   3. Remove the state marker file
set -euo pipefail

# ── Tunables (must mirror deploy.sh) ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SANDBOX_CADDY_DIR="${SANDBOX_CADDY_DIR:-${REPO_ROOT}/caddy/sandboxes}"
SANDBOX_CADDY_CONFIG="${SANDBOX_CADDY_CONFIG:-/etc/caddy/Caddyfile}"
SANDBOX_CADDY_CONTAINER="${SANDBOX_CADDY_CONTAINER:-roadtrip-caddy-1}"
SANDBOX_STATE_DIR="${SANDBOX_STATE_DIR:-/var/lib/roadtrip-sandboxes}"
COMPOSE_FILE="${COMPOSE_FILE:-${REPO_ROOT}/docker-compose.sandbox.yml}"
# Must mirror deploy.sh so the FQDN reconstructed for Cloudflare teardown
# matches what was provisioned on up.
SANDBOX_TUNNEL_ZONE="${SANDBOX_TUNNEL_ZONE:-floo.ca}"
SANDBOX_HOST_PREFIX="${SANDBOX_HOST_PREFIX:-roadtrip-sb-}"

# Cloudflare DNS + Access teardown; no-op unless CF_API_TOKEN_FILE is readable.
# shellcheck source=scripts/cloudflare_sandbox.sh
source "${SCRIPT_DIR}/cloudflare_sandbox.sh"

# ── Argument handling ─────────────────────────────────────────────────────────
if [[ $# -lt 1 ]]; then
    echo "usage: sandbox_down.sh <name>" >&2
    exit 1
fi

SANDBOX_NAME="$1"
COMPOSE_PROJECT="roadtrip-sb-${SANDBOX_NAME}"

echo "==> tearing down sandbox: ${SANDBOX_NAME}  (project: ${COMPOSE_PROJECT})"

# ── Stop and remove Compose project + volume ─────────────────────────────────
# `docker compose down -v` removes containers and named volumes.
# If the project never existed (or was already removed), Compose exits 0
# with a "no containers" notice — idempotent.
docker compose \
    -p "${COMPOSE_PROJECT}" \
    -f "${COMPOSE_FILE}" \
    down -v
echo "==> compose project removed"

# ── Remove Caddy vhost snippet ────────────────────────────────────────────────
CADDY_SNIPPET="${SANDBOX_CADDY_DIR}/sb-${SANDBOX_NAME}.caddy"
if [[ -f "${CADDY_SNIPPET}" ]]; then
    rm -f "${CADDY_SNIPPET}"
    echo "==> removed Caddy snippet: ${CADDY_SNIPPET}"
    # Reload the caddy CONTAINER only when a snippet was actually removed so we
    # don't error on a reload when the file was already absent.  If the caddy
    # container isn't running (e.g. base stack down), the exec fails — tolerate
    # it: teardown must stay idempotent and a stopped proxy has no stale vhost.
    echo "==> reloading Caddy (docker exec ${SANDBOX_CADDY_CONTAINER})"
    docker exec "${SANDBOX_CADDY_CONTAINER}" \
        caddy reload --config "${SANDBOX_CADDY_CONFIG}" \
        || echo "==> warning: caddy reload failed (container not running?); snippet already removed"
else
    echo "==> Caddy snippet not found (already removed): ${CADDY_SNIPPET}"
fi

# ── Remove Cloudflare DNS + Access ────────────────────────────────────────────
# Reconstruct the same FQDN deploy.sh provisioned; no-op without a CF token.
cf_sandbox_down "${SANDBOX_HOST_PREFIX}${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}" \
    || echo "==> warning: Cloudflare teardown failed; check DNS/Access for ${SANDBOX_NAME}"

# ── Remove state marker ───────────────────────────────────────────────────────
MARKER="${SANDBOX_STATE_DIR}/${SANDBOX_NAME}.meta"
if [[ -f "${MARKER}" ]]; then
    rm -f "${MARKER}"
    echo "==> removed marker: ${MARKER}"
else
    echo "==> marker not found (already removed): ${MARKER}"
fi

echo "==> sandbox ${SANDBOX_NAME} is down"
