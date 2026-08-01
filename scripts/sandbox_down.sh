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

# ── Tunables (must mirror sandbox_up.sh) ─────────────────────────────────────
SANDBOX_CADDY_DIR="${SANDBOX_CADDY_DIR:-/etc/caddy/sandboxes}"
SANDBOX_CADDY_CONFIG="${SANDBOX_CADDY_CONFIG:-/etc/caddy/Caddyfile}"
SANDBOX_STATE_DIR="${SANDBOX_STATE_DIR:-/var/lib/roadtrip-sandboxes}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${REPO_ROOT}/docker-compose.sandbox.yml}"

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
    # Reload Caddy only when a snippet was actually removed so we don't
    # error on a reload when the file was already absent.
    echo "==> reloading Caddy"
    caddy reload --config "${SANDBOX_CADDY_CONFIG}"
else
    echo "==> Caddy snippet not found (already removed): ${CADDY_SNIPPET}"
fi

# ── Remove state marker ───────────────────────────────────────────────────────
MARKER="${SANDBOX_STATE_DIR}/${SANDBOX_NAME}.meta"
if [[ -f "${MARKER}" ]]; then
    rm -f "${MARKER}"
    echo "==> removed marker: ${MARKER}"
else
    echo "==> marker not found (already removed): ${MARKER}"
fi

echo "==> sandbox ${SANDBOX_NAME} is down"
