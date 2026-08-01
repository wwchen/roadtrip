#!/usr/bin/env bash
# deploy.sh <env> <ref> [name]
#
# Unified deploy pipeline for roadtrip.
#
# <env>   Deployment environment.  Currently only "sandbox" is wired;
#         "prod" is scaffolded (routing=direct) but still delegates to
#         `make run env=prod` until a future migration PR.
# <ref>   A PR number (numeric) or branch name.  If numeric, the sandbox
#         name is "pr<N>".  Otherwise the branch is slugified (lowercase,
#         non-alphanumeric runs → single dash, leading/trailing dashes trimmed).
# [name]  Optional sandbox name override; skips auto-derivation.
#
# Routing modes
#   caddy-vhost  Write a per-instance Caddy snippet and reload (sandbox).
#   direct       No ingress registration step (prod: cloudflared → backend).
#
# Exits non-zero on any failure.  Safe to re-run: Compose is idempotent;
# Caddy snippets and state markers are overwritten on re-up.
#
# Runtime assumptions (documented):
#   - caddy-vhost: Caddy is running on the host and accepts
#     `caddy reload --config <path>`.  SANDBOX_CADDY_CONFIG is the path to
#     the root Caddyfile that imports per-sandbox snippets from
#     SANDBOX_CADDY_DIR.
#   - Snapshot (if present) is a pg_dump -Fc custom-format archive.
#     pg_restore runs as the sandbox POSTGRES_USER inside the postgres
#     container.
#   - docker compose (v2, plugin) is on PATH.
#   - nc is available for port scanning (falls back to ss if nc absent).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Argument handling ─────────────────────────────────────────────────────────
if [[ $# -lt 2 ]]; then
    echo "usage: deploy.sh <env> <ref> [name]" >&2
    echo "       env:  sandbox" >&2
    echo "       ref:  PR number (numeric) or branch name" >&2
    echo "       name: optional sandbox name override" >&2
    exit 1
fi

DEPLOY_ENV="$1"
REF="$2"
NAME_OVERRIDE="${3:-}"

# ── Tunables ──────────────────────────────────────────────────────────────────
# All host-specific values live here so the script can be re-targeted without
# touching the pipeline steps below.

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

# SQL seed file applied after snapshot restore.
SEED_SQL="${SEED_SQL:-${SCRIPT_DIR}/sandbox_seed_users.sql}"

# SQL scrub file applied after snapshot restore to blank PII columns in the
# watch subtree (availability_watch.trigger_config) while keeping all rows.
SCRUB_SQL="${SCRUB_SQL:-${SCRIPT_DIR}/sandbox_scrub.sql}"

# Postgres connection settings inside the sandbox (must match compose file).
POSTGRES_DB="${POSTGRES_DB:-roadtrip}"
POSTGRES_USER="${POSTGRES_USER:-roadtrip}"

# Backend readiness probe — retried up to this many times (1 s apart).
HEALTH_RETRIES="${HEALTH_RETRIES:-60}"

# Postgres health probe — retried up to this many times (1 s apart).
POSTGRES_HEALTH_RETRIES="${POSTGRES_HEALTH_RETRIES:-30}"

# ── Env-specific config ───────────────────────────────────────────────────────
# Sets: ROUTING (caddy-vhost | direct), COMPOSE_FILE, DO_DB_PREP.
case "${DEPLOY_ENV}" in
    sandbox)
        ROUTING="caddy-vhost"
        # COMPOSE_FILE may be overridden by the caller via env var.
        COMPOSE_FILE="${COMPOSE_FILE:-${REPO_ROOT}/docker-compose.sandbox.yml}"
        DO_DB_PREP="true"
        ;;
    prod)
        # Prod ingress uses cloudflared → backend directly; no Caddy vhost
        # needed.  The prod path has not yet been migrated to deploy.sh —
        # use `make run env=prod` until a follow-up PR wires this env.
        echo "error: env=prod is not yet wired through deploy.sh" >&2
        echo "       use 'make run env=prod' directly" >&2
        exit 1
        ;;
    *)
        echo "error: unknown env '${DEPLOY_ENV}'; supported: sandbox" >&2
        exit 1
        ;;
esac

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

echo "==> ${DEPLOY_ENV}: ${SANDBOX_NAME}  (project: ${COMPOSE_PROJECT})"

# ── Resolve the image SHA ─────────────────────────────────────────────────────
# If REF is a numeric PR number, SANDBOX_SHA must be provided by the caller
# (e.g. from the GitHub Actions workflow that triggered this script).
# For a branch ref, the caller may set SANDBOX_SHA explicitly; otherwise we
# use REF as the image tag directly (assumes CI tagged it as the branch name).
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
# Ordering depends on whether a snapshot will be restored:
#
#   WITH snapshot:
#     1. Start postgres only → wait healthy → pg_restore (DB is still empty,
#        so no DDL collision with CREATE TABLE) → seed users → start backend.
#        Flyway boots against the already-restored schema (including the
#        restored flyway_schema_history) and is a no-op.
#
#   WITHOUT snapshot:
#     1. Start everything together (original behaviour).  Flyway migrates the
#        empty DB on backend boot; seed users are applied after postgres is
#        healthy and before the backend is reachable.
#
# This avoids the "relation already exists" abort that occurs when pg_restore
# is called against a DB that Flyway has already fully migrated.

HAVE_SNAPSHOT="false"
if [[ "${DO_DB_PREP}" == "true" && -n "${SANDBOX_SNAPSHOT_PATH}" && -f "${SANDBOX_SNAPSHOT_PATH}" ]]; then
    HAVE_SNAPSHOT="true"
fi

if [[ "${HAVE_SNAPSHOT}" == "true" ]]; then
    # ── Step 1: postgres only ─────────────────────────────────────────────────
    echo "==> docker compose up postgres (snapshot path; starting DB before backend)"
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        up -d postgres
else
    # ── Step 1: all services together ────────────────────────────────────────
    echo "==> docker compose up (project ${COMPOSE_PROJECT})"
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        up -d
fi

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

PG_WAIT=0
while ! _postgres_healthy; do
    PG_WAIT=$(( PG_WAIT + 1 ))
    if [[ ${PG_WAIT} -ge ${POSTGRES_HEALTH_RETRIES} ]]; then
        echo "error: postgres did not become healthy after ${POSTGRES_HEALTH_RETRIES}s" >&2
        exit 1
    fi
    sleep 1
done
echo "==> postgres healthy"

# ── Prepare DB (snapshot restore + user seed) ────────────────────────────────
if [[ "${DO_DB_PREP}" == "true" ]]; then
    if [[ "${HAVE_SNAPSHOT}" == "true" ]]; then
        # ── Idempotency check ─────────────────────────────────────────────────
        # The postgres-data named volume persists across re-ups of the same
        # Compose project, so a second /sandbox on the same PR would call
        # pg_restore over an already-migrated DB → "relation already exists"
        # abort.  Check for flyway_schema_history before restoring; if it
        # already exists the restore + seed already ran and we just start the
        # backend.
        already_initialized="$(docker compose \
            -p "${COMPOSE_PROJECT}" \
            -f "${COMPOSE_FILE}" \
            exec -T postgres \
            psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -tAc \
            "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" \
            2>/dev/null | tr -d '[:space:]')"

        if [[ "${already_initialized}" == "t" ]]; then
            echo "==> DB already initialized (re-up); skipping restore+seed"
        else
            # ── Step 2: restore snapshot into the empty DB ────────────────────
            # postgres is up but the backend has NOT started yet, so the DB is
            # still empty.  pg_restore runs with no risk of DDL collision with
            # Flyway.
            # --no-owner/--no-acl: restored objects owned by POSTGRES_USER
            # regardless of the snapshot's origin user.
            # --exit-on-error: a partial restore is loud rather than silent.
            echo "==> restoring snapshot: ${SANDBOX_SNAPSHOT_PATH}"
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

            # ── Step 3a: scrub PII columns ────────────────────────────────
            # Blank availability_watch.trigger_config (email/Slack destinations)
            # while preserving every watch row and FK integrity.  The watch
            # subtree is no longer excluded from snapshots — it's scrubbed here.
            echo "==> scrubbing PII columns (trigger_config)"
            docker compose \
                -p "${COMPOSE_PROJECT}" \
                -f "${COMPOSE_FILE}" \
                exec -T postgres \
                psql \
                    --username="${POSTGRES_USER}" \
                    --dbname="${POSTGRES_DB}" \
                    --no-password \
                    -v ON_ERROR_STOP=1 \
                < "${SCRUB_SQL}"
            echo "==> PII scrubbed"

            # ── Step 3b: seed users ───────────────────────────────────────────
            # Runs after restore.  The backend has not started yet, so seed
            # rows are visible to Flyway's no-op check and to the first request.
            echo "==> seeding sandbox users"
            docker compose \
                -p "${COMPOSE_PROJECT}" \
                -f "${COMPOSE_FILE}" \
                exec -T postgres \
                psql \
                    --username="${POSTGRES_USER}" \
                    --dbname="${POSTGRES_DB}" \
                    --no-password \
                    -v ON_ERROR_STOP=1 \
                < "${SEED_SQL}"
            echo "==> users seeded"
        fi

        # ── Step 4: start remaining services (backend + any others) ──────────
        # Runs whether this was a fresh restore or a re-up.  Flyway boots
        # against the already-restored schema (including flyway_schema_history)
        # and is a no-op.
        echo "==> docker compose up (remaining services)"
        docker compose \
            -p "${COMPOSE_PROJECT}" \
            -f "${COMPOSE_FILE}" \
            up -d
    else
        echo "==> no snapshot to restore (SANDBOX_SNAPSHOT_PATH not set or file absent)"
        # No-snapshot seed is deferred to AFTER the backend health-check below
        # (step 4-no-snapshot) so that Flyway has completed its migrations before
        # we INSERT into app_user / user_role.  Seeding before the backend is up
        # races Flyway: app_user may not exist yet.
    fi
fi

# ── Register vhost ────────────────────────────────────────────────────────────
case "${ROUTING}" in
    caddy-vhost)
        CADDY_SNIPPET="${SANDBOX_CADDY_DIR}/sb-${SANDBOX_NAME}.caddy"
        mkdir -p "${SANDBOX_CADDY_DIR}"
        cat > "${CADDY_SNIPPET}" <<CADDY
# Auto-generated by deploy.sh — do not edit by hand.
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
        ;;
    direct)
        # Prod-style ingress: cloudflared routes directly to the backend
        # process.  No per-instance vhost registration is needed.
        ;;
    *)
        echo "error: unknown routing '${ROUTING}'; supported: caddy-vhost, direct" >&2
        exit 1
        ;;
esac

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
# For the no-snapshot path this is also the Flyway gate: the backend's /ready
# endpoint only returns 200 after Flyway has completed all migrations, so the
# seed that follows is guaranteed to find app_user / user_role already present.
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

# ── Step 4 (no-snapshot path): seed users after Flyway ───────────────────────
# With a snapshot, seed runs before the backend starts (schema from restore).
# Without a snapshot, seed runs here — after the backend health-check confirms
# Flyway is complete — so we never INSERT into tables that don't exist yet.
if [[ "${DO_DB_PREP}" == "true" && "${HAVE_SNAPSHOT}" == "false" ]]; then
    echo "==> seeding sandbox users (post-Flyway)"
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        exec -T postgres \
        psql \
            --username="${POSTGRES_USER}" \
            --dbname="${POSTGRES_DB}" \
            --no-password \
            -v ON_ERROR_STOP=1 \
        < "${SEED_SQL}"
    echo "==> users seeded"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
SANDBOX_URL="https://sb-${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
echo ""
echo "Sandbox is live: ${SANDBOX_URL}"
