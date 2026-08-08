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
#   - caddy-vhost: the caddy CONTAINER (SANDBOX_CADDY_CONTAINER, default
#     roadtrip-caddy-1) is running with SANDBOX_CADDY_DIR bind-mounted to
#     /etc/caddy/sandboxes and joined to SANDBOX_NETWORK.  Reload is
#     `docker exec <container> caddy reload --config SANDBOX_CADDY_CONFIG`;
#     no host `caddy` binary is required.
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

# ── Host config file ──────────────────────────────────────────────────────────
# Persistent per-host SANDBOX_* config (e.g. SANDBOX_SNAPSHOT_PATH).  The GitHub
# workflow runs this over SSH with no per-run env, so host-specific values must
# live in a file.  Sourced BEFORE the tunables so each `${VAR:-default}` below
# picks up what the file set (and falls back to the default otherwise).
SANDBOX_ENV_FILE="${SANDBOX_ENV_FILE:-/var/lib/roadtrip-sandboxes/sandbox.env}"
if [[ -f "${SANDBOX_ENV_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${SANDBOX_ENV_FILE}"
fi

# ── Tunables ──────────────────────────────────────────────────────────────────
# All host-specific values live here so the script can be re-targeted without
# touching the pipeline steps below.

# DNS zone used to build the sandbox URL: <prefix><name>.<zone>
# Default is `floo.ca` (one label under the apex) so the free Cloudflare
# Universal SSL cert `*.floo.ca` covers sandbox hostnames — a deeper zone like
# `sandbox.roadtrip.floo.ca` is a second-level wildcard the free cert does NOT
# cover (TLS wildcards match a single label), which needs paid ACM.
SANDBOX_TUNNEL_ZONE="${SANDBOX_TUNNEL_ZONE:-floo.ca}"

# Hostname prefix for the sandbox vhost: full host is <prefix><name>.<zone>,
# e.g. roadtrip-sb-pr560.floo.ca.  Both the DNS record and the Cloudflare tunnel
# rule are broad single-label `*.floo.ca` wildcards (partial labels like
# `roadtrip-sb-*` are rejected by both, and a deeper zone needs paid ACM), so
# Caddy is the actual filter: it only serves vhosts named with this prefix, and
# any other `*.floo.ca` host reaches caddy but matches no vhost.  This prefix
# namespaces sandbox hosts and keeps them recognisable.
SANDBOX_HOST_PREFIX="${SANDBOX_HOST_PREFIX:-roadtrip-sb-}"

# Cloudflare DNS + Access provisioning (per-sandbox public exposure).  All the
# CF_* config lives in scripts/cloudflare_sandbox.sh; provisioning is a no-op
# unless CF_API_TOKEN_FILE is readable, so local/CI runs are unaffected.
# shellcheck source=scripts/cloudflare_sandbox.sh
source "${SCRIPT_DIR}/cloudflare_sandbox.sh"

# Directory where per-sandbox Caddy snippet files are written.  This is the
# HOST side of the caddy container's bind-mount (see docker-compose.yml's caddy
# service: ./caddy/sandboxes → /etc/caddy/sandboxes).  Default resolves to the
# repo's caddy/sandboxes so a plain checkout works with no host setup.
SANDBOX_CADDY_DIR="${SANDBOX_CADDY_DIR:-${REPO_ROOT}/caddy/sandboxes}"

# Path to the root Caddyfile INSIDE the caddy container — passed to
# `caddy reload --config` via `docker exec`.
SANDBOX_CADDY_CONFIG="${SANDBOX_CADDY_CONFIG:-/etc/caddy/Caddyfile}"

# Name of the running caddy container (base `roadtrip` compose project, so
# `roadtrip-caddy-1`).  `docker exec <this> caddy reload` re-reads the snippets;
# this replaces the old assumption that a `caddy` binary is on the host PATH.
SANDBOX_CADDY_CONTAINER="${SANDBOX_CADDY_CONTAINER:-roadtrip-caddy-1}"

# Shared Docker network (owned by the base project) that the caddy proxy and
# every sandbox backend join.  Must match the `name:` in docker-compose.yml.
SANDBOX_NETWORK="${SANDBOX_NETWORK:-roadtrip-sandbox}"

# Backend container port the proxy forwards to (matches the sandbox backend's
# in-container listen port, 8765).
SANDBOX_BACKEND_PORT="${SANDBOX_BACKEND_PORT:-8765}"

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
# The commit under review (what /api/build-info reports).  Distinct from
# SANDBOX_SHA, which is the IMAGE tag to pull and may be an ancestor when the
# PR head has no built image (scripts/docs/frontend PR).  Defaults to the image
# SHA so callers that don't set it keep the old behaviour.
SANDBOX_BUILD_SHA="${SANDBOX_BUILD_SHA:-${SANDBOX_SHA}}"

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
export SANDBOX_BUILD_SHA
export SANDBOX_BRANCH
export SANDBOX_PORT
export SANDBOX_DB_PASSWORD
export POSTGRES_DB
export POSTGRES_USER
# Consumed by the backend service's network alias (sb-${SANDBOX_NAME}-backend).
export SANDBOX_NAME

# ── Ensure the shared proxy network exists ───────────────────────────────────
# The base `roadtrip` project owns this network, but a sandbox may be brought
# up before (or without) that project on a given host.  `docker network create`
# is not idempotent, so guard it — the sandbox compose attaches to it as
# external and would fail hard if it were missing.
#
# The `com.docker.compose.network` label is REQUIRED: if this fallback creates a
# bare (unlabeled) network and the base `roadtrip` project is later brought up,
# `docker compose up` aborts with "network roadtrip-sandbox was found but has
# incorrect label" — breaking prod deploys.  Creating it with the label Compose
# expects lets the base project adopt it cleanly.  (Verified against Compose
# 5.3.1.)  Note this is only reachable if the base stack — and thus the caddy
# container — is down, in which case the sandbox won't route until it's up; the
# guard just avoids a cryptic external-network-missing error in that window.
if [[ "${ROUTING}" == "caddy-vhost" ]]; then
    if ! docker network inspect "${SANDBOX_NETWORK}" >/dev/null 2>&1; then
        echo "==> creating shared proxy network: ${SANDBOX_NETWORK}"
        docker network create \
            --label "com.docker.compose.network=${SANDBOX_NETWORK}" \
            "${SANDBOX_NETWORK}" >/dev/null
    fi
fi

# ── Build the React frontend ──────────────────────────────────────────────────
# The compose files bind-mount frontend/dist from this checkout (like web/), so
# the served frontend tracks the reviewed SHA rather than whatever the pre-built
# image happens to carry. That means the build has to happen here.
#
# Skipped without npm rather than aborting: the backend serves the legacy page
# per file when a build is absent, so a host without Node gets the vanilla site
# instead of a failed deploy. Non-fatal on failure for the same reason.
if [[ -d "${REPO_ROOT}/frontend" ]]; then
    if command -v npm >/dev/null 2>&1; then
        echo "==> building frontend (npm ci && npm run build)"
        if ! (cd "${REPO_ROOT}/frontend" && npm ci --no-audit --no-fund && npm run build); then
            echo "WARNING: frontend build failed; migrated pages will fall back to web/" >&2
        fi
    else
        echo "WARNING: npm not found; skipping frontend build (serving legacy pages)" >&2
    fi
fi

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

# ── Wait for postgres init to COMPLETE, then be healthy ──────────────────────
# The postgres image's entrypoint runs /docker-entrypoint-initdb.d scripts
# against a TEMPORARY internal server, then fast-shuts-it-down and restarts as
# the real server.  `pg_isready` passes against that temp server, so a restore
# started on `pg_isready` alone races the restart and dies mid-COPY with
# "server closed the connection unexpectedly".  On a fresh volume we therefore
# first wait for the entrypoint's one-shot marker "PostgreSQL init process
# complete; ready for start up." (printed only as the temp server hands off),
# and only then poll pg_isready.  On a re-up (volume already initialized) the
# entrypoint skips init and never prints the marker, so we don't require it.
_postgres_log() {
    docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" logs postgres 2>/dev/null
}
_postgres_healthy() {
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        exec -T postgres \
        pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
        >/dev/null 2>&1
}

echo "==> waiting for postgres init to complete"
PG_WAIT=0
while :; do
    logs="$(_postgres_log)"
    # Init done (fresh volume) OR init was skipped (re-up: data dir already
    # populated, entrypoint logs "Skipping initialization").
    if grep -q "init process complete" <<<"${logs}" \
        || grep -q "Skipping initialization" <<<"${logs}"; then
        break
    fi
    PG_WAIT=$(( PG_WAIT + 1 ))
    if [[ ${PG_WAIT} -ge ${POSTGRES_HEALTH_RETRIES} ]]; then
        echo "error: postgres init did not complete after ${POSTGRES_HEALTH_RETRIES}s" >&2
        exit 1
    fi
    sleep 1
done

echo "==> waiting for postgres to accept connections"
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
        # The `http://` scheme is REQUIRED: a scheme-less site address binds
        # :443 even with `auto_https off` (that flag disables cert automation,
        # not the 443 default), so Caddy would never listen on :80 and the
        # tunnel (→ caddy:80) would get no listener.  With `http://` the vhost
        # binds :80.  Verified against caddy:2-alpine.
        #
        # reverse_proxy targets the backend's network alias on the shared
        # roadtrip-sandbox network, NOT a host port: the caddy container cannot
        # reach the host's 127.0.0.1:${SANDBOX_PORT} loopback bind.
        SANDBOX_FQDN="${SANDBOX_HOST_PREFIX}${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
        cat > "${CADDY_SNIPPET}" <<CADDY
# Auto-generated by deploy.sh — do not edit by hand.
# Sandbox: ${SANDBOX_NAME}   host-probe port: ${SANDBOX_PORT}
http://${SANDBOX_FQDN} {
    reverse_proxy sb-${SANDBOX_NAME}-backend:${SANDBOX_BACKEND_PORT}
}
CADDY
        echo "==> wrote Caddy snippet: ${CADDY_SNIPPET}"
        # Reload the caddy CONTAINER so the new vhost is active — the snippet
        # dir is bind-mounted in, so the file is already visible to it.
        echo "==> reloading Caddy (docker exec ${SANDBOX_CADDY_CONTAINER})"
        docker exec "${SANDBOX_CADDY_CONTAINER}" \
            caddy reload --config "${SANDBOX_CADDY_CONFIG}"
        # Provision the per-sandbox public DNS CNAME for this host.  No-op when
        # no CF token is present (local/CI).  The Cloudflare Access app that
        # gates roadtrip-sb-*.<zone> is a static, human-configured wildcard app
        # (see docs) — so the moment this DNS resolves, the host is already
        # behind Access; there is no ungated window.
        # Fatal on failure: a swallowed DNS/Access-gate failure would let the
        # script print "Sandbox is live" and the workflow post a URL that can't
        # resolve (or, worse, an ungated host).  cf_sandbox_up returns 0 on the
        # no-token local/CI path, so this only fails a token-configured host.
        if ! cf_sandbox_up "${SANDBOX_FQDN}"; then
            echo "error: Cloudflare provisioning failed for ${SANDBOX_FQDN}; not marking the sandbox live" >&2
            exit 1
        fi
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
SANDBOX_URL="https://${SANDBOX_HOST_PREFIX}${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
echo ""
echo "Sandbox is live: ${SANDBOX_URL}"
