#!/usr/bin/env bash
# sandbox_up.sh <ref> [name]
#
# Spin up one throwaway sandbox instance.
#
# <ref>   A PR number (numeric) or branch name.  If numeric, the sandbox name
#         is "pr<N>".  Otherwise the branch is slugified (lowercase,
#         non-alphanumeric runs → single dash, leading/trailing dashes trimmed).
# [name]  Optional override for the sandbox name; skips auto-derivation.
#
# Exits non-zero on any failure.  Safe to re-run: the Compose project is
# idempotent; Caddy and state files are overwritten on re-up.
#
# Runtime assumptions (documented):
#   - Caddy is running on the host and accepts `caddy reload --config <path>`.
#     SANDBOX_CADDY_CONFIG is the path to the root Caddyfile that imports
#     the per-sandbox snippets from SANDBOX_CADDY_DIR.
#   - Snapshot (if present) is a pg_dump -Fc custom-format archive.
#     pg_restore runs as the sandbox POSTGRES_USER inside the postgres container.
#   - docker compose (v2, plugin) is on PATH.
#   - nc is available for port scanning (falls back to ss if nc absent).
set -euo pipefail

# ── Tunables ─────────────────────────────────────────────────────────────────
# All host-specific values live here so the script can be re-targeted without
# touching the steps below.

# DNS zone used to build the sandbox URL: sb-<name>.<zone>
SANDBOX_TUNNEL_ZONE="${SANDBOX_TUNNEL_ZONE:-sandbox.roadtrip.floo.ca}"

# Directory where per-sandbox Caddy snippet files are written.
# The root Caddyfile must `import` this directory, e.g.:
#   import /etc/caddy/sandboxes/*.caddy
SANDBOX_CADDY_DIR="${SANDBOX_CADDY_DIR:-/etc/caddy/sandboxes}"

# Path to the root Caddyfile — passed to `caddy reload --config`.
SANDBOX_CADDY_CONFIG="${SANDBOX_CADDY_CONFIG:-/etc/caddy/Caddyfile}"

# Directory for .meta marker files (consumed by sandbox_reap.sh).
SANDBOX_STATE_DIR="${SANDBOX_STATE_DIR:-/var/lib/roadtrip-sandboxes}"

# Optional pg_dump -Fc archive to restore before seeding.
# Leave blank (or unset) to start with an empty Flyway-migrated schema.
SANDBOX_SNAPSHOT_PATH="${SANDBOX_SNAPSHOT_PATH:-}"

# Host-local port range allocated to sandboxes.
SANDBOX_PORT_RANGE_START="${SANDBOX_PORT_RANGE_START:-41000}"
SANDBOX_PORT_RANGE_END="${SANDBOX_PORT_RANGE_END:-41999}"

# Postgres password for the throwaway sandbox DB (not a real secret).
SANDBOX_DB_PASSWORD="${SANDBOX_DB_PASSWORD:-sandbox}"

# Path to docker-compose.sandbox.yml (relative to repo root or absolute).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${REPO_ROOT}/docker-compose.sandbox.yml}"

# SQL seed file applied after snapshot restore.
SEED_SQL="${SEED_SQL:-${SCRIPT_DIR}/sandbox_seed_users.sql}"

# Postgres connection settings inside the sandbox (must match compose file).
POSTGRES_DB="${POSTGRES_DB:-roadtrip}"
POSTGRES_USER="${POSTGRES_USER:-roadtrip}"

# Backend readiness probe — retried up to this many times (1 s apart).
HEALTH_RETRIES="${HEALTH_RETRIES:-60}"

# ── Argument handling ─────────────────────────────────────────────────────────
if [[ $# -lt 1 ]]; then
    echo "usage: sandbox_up.sh <ref> [name]" >&2
    exit 1
fi

REF="$1"
NAME_OVERRIDE="${2:-}"

# ── Derive sandbox name ───────────────────────────────────────────────────────
if [[ -n "${NAME_OVERRIDE}" ]]; then
    SANDBOX_NAME="${NAME_OVERRIDE}"
elif [[ "${REF}" =~ ^[0-9]+$ ]]; then
    SANDBOX_NAME="pr${REF}"
else
    # Slugify: lowercase, collapse non-alnum runs to dash, trim edges.
    SANDBOX_NAME="$(printf '%s' "${REF}" \
        | tr '[:upper:]' '[:lower:]' \
        | sed 's/[^a-z0-9]\{1,\}/-/g' \
        | sed 's/^-//; s/-$//')"
fi

if [[ -z "${SANDBOX_NAME}" ]]; then
    echo "error: could not derive a sandbox name from ref '${REF}'" >&2
    exit 1
fi

# Compose project name for this sandbox.
COMPOSE_PROJECT="roadtrip-sb-${SANDBOX_NAME}"

echo "==> sandbox: ${SANDBOX_NAME}  (project: ${COMPOSE_PROJECT})"

# ── Resolve the image SHA ─────────────────────────────────────────────────────
# If REF is a numeric PR number, SANDBOX_SHA must be provided by the caller
# (e.g. from the GitHub Actions workflow that triggered this script).
# For a branch ref, the caller may set SANDBOX_SHA explicitly; otherwise we
# use REF as the image tag directly (assumes the CI push tagged it as the branch).
SANDBOX_SHA="${SANDBOX_SHA:-${REF}}"
SANDBOX_BRANCH="${SANDBOX_BRANCH:-${REF}}"

# ── Allocate a free host-local port ──────────────────────────────────────────
_port_in_use() {
    local port="$1"
    if command -v nc >/dev/null 2>&1; then
        nc -z 127.0.0.1 "${port}" 2>/dev/null
    elif command -v ss >/dev/null 2>&1; then
        ss -tnl 2>/dev/null | grep -q ":${port} "
    else
        # Last resort: attempt to bind via /dev/tcp (bash built-in).
        (echo >/dev/tcp/127.0.0.1/"${port}") 2>/dev/null
    fi
}

SANDBOX_PORT=""
for port in $(seq "${SANDBOX_PORT_RANGE_START}" "${SANDBOX_PORT_RANGE_END}"); do
    if ! _port_in_use "${port}"; then
        SANDBOX_PORT="${port}"
        break
    fi
done

if [[ -z "${SANDBOX_PORT}" ]]; then
    echo "error: no free port in range ${SANDBOX_PORT_RANGE_START}–${SANDBOX_PORT_RANGE_END}" >&2
    exit 1
fi

echo "==> allocated port ${SANDBOX_PORT}"

# ── Export vars consumed by docker-compose.sandbox.yml ───────────────────────
export SANDBOX_SHA
export SANDBOX_BRANCH
export SANDBOX_PORT
export SANDBOX_DB_PASSWORD
export POSTGRES_DB
export POSTGRES_USER

# ── Start Compose services ────────────────────────────────────────────────────
echo "==> docker compose up (project ${COMPOSE_PROJECT})"
docker compose \
    -p "${COMPOSE_PROJECT}" \
    -f "${COMPOSE_FILE}" \
    up -d

# ── Wait for postgres to be healthy ──────────────────────────────────────────
echo "==> waiting for postgres to be healthy"
_postgres_healthy() {
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        exec -T postgres \
        pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
        >/dev/null 2>&1
}

PG_RETRIES=30
PG_WAIT=0
while ! _postgres_healthy; do
    PG_WAIT=$(( PG_WAIT + 1 ))
    if [[ ${PG_WAIT} -ge ${PG_RETRIES} ]]; then
        echo "error: postgres did not become healthy after ${PG_RETRIES}s" >&2
        exit 1
    fi
    sleep 1
done
echo "==> postgres healthy"

# ── Restore snapshot (optional) ──────────────────────────────────────────────
if [[ -n "${SANDBOX_SNAPSHOT_PATH}" && -f "${SANDBOX_SNAPSHOT_PATH}" ]]; then
    echo "==> restoring snapshot: ${SANDBOX_SNAPSHOT_PATH}"
    # pg_restore into the sandbox DB.  --no-owner/--no-acl so restored objects
    # are owned by POSTGRES_USER regardless of the snapshot's origin user.
    # --exit-on-error so a partial restore is loud rather than silent.
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        exec -T postgres \
        pg_restore \
            --username="${POSTGRES_USER}" \
            --dbname="${POSTGRES_DB}" \
            --no-owner \
            --no-acl \
            --exit-on-error \
        < "${SANDBOX_SNAPSHOT_PATH}"
    echo "==> snapshot restored"
else
    echo "==> no snapshot to restore (SANDBOX_SNAPSHOT_PATH not set or file absent)"
fi

# ── Apply user seed SQL ───────────────────────────────────────────────────────
echo "==> seeding sandbox users"
docker compose \
    -p "${COMPOSE_PROJECT}" \
    -f "${COMPOSE_FILE}" \
    exec -T postgres \
    psql \
        --username="${POSTGRES_USER}" \
        --dbname="${POSTGRES_DB}" \
        --no-password \
    < "${SEED_SQL}"
echo "==> users seeded"

# ── Write Caddy vhost snippet ─────────────────────────────────────────────────
CADDY_SNIPPET="${SANDBOX_CADDY_DIR}/sb-${SANDBOX_NAME}.caddy"
mkdir -p "${SANDBOX_CADDY_DIR}"
cat > "${CADDY_SNIPPET}" <<CADDY
# Auto-generated by sandbox_up.sh — do not edit by hand.
# Sandbox: ${SANDBOX_NAME}   port: ${SANDBOX_PORT}
sb-${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE} {
    reverse_proxy 127.0.0.1:${SANDBOX_PORT}
}
CADDY
echo "==> wrote Caddy snippet: ${CADDY_SNIPPET}"

# Reload Caddy so the new vhost is active.
# Assumption: `caddy` is on PATH and accepts `reload --config <path>`.
echo "==> reloading Caddy"
caddy reload --config "${SANDBOX_CADDY_CONFIG}"

# ── Write state marker (consumed by sandbox_reap.sh) ─────────────────────────
mkdir -p "${SANDBOX_STATE_DIR}"
MARKER="${SANDBOX_STATE_DIR}/${SANDBOX_NAME}.meta"
printf 'NAME=%s\nPORT=%s\nSTART_EPOCH=%s\n' \
    "${SANDBOX_NAME}" \
    "${SANDBOX_PORT}" \
    "$(date +%s)" \
    > "${MARKER}"
echo "==> wrote marker: ${MARKER}"

# ── Health-check the backend ──────────────────────────────────────────────────
echo "==> waiting for backend to be ready"
HEALTH_URL="http://127.0.0.1:${SANDBOX_PORT}/api/health/ready"
HEALTH_WAIT=0
until curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; do
    HEALTH_WAIT=$(( HEALTH_WAIT + 1 ))
    if [[ ${HEALTH_WAIT} -ge ${HEALTH_RETRIES} ]]; then
        echo "error: backend did not become ready at ${HEALTH_URL} after ${HEALTH_RETRIES}s" >&2
        exit 1
    fi
    sleep 1
done
echo "==> backend ready"

# ── Done ──────────────────────────────────────────────────────────────────────
SANDBOX_URL="https://sb-${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
echo ""
echo "Sandbox is live: ${SANDBOX_URL}"
