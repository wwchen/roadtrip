#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

_require_sha() {
    local label="$1"
    local value="$2"
    if ! [[ "${value}" =~ ^[0-9a-f]{40}$ ]]; then
        echo "error: ${label} must be a full 40-character Git SHA" >&2
        exit 2
    fi
}

_require_sandbox_name() {
    local value="$1"
    if ! [[ "${value}" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]]; then
        echo "error: sandbox name must be 1-63 lowercase letters, digits, or hyphens" >&2
        exit 2
    fi
}

_ensure_data_volume() {
    local data_sha="$1"
    local data_image="${ROADTRIP_DATA_IMAGE:-ghcr.io/wwchen/roadtrip/data:${data_sha}}"
    local data_volume="${ROADTRIP_DATA_VOLUME:-roadtrip-data-${data_sha}}"
    local actual_sha

    docker pull "${data_image}"
    if ! docker volume inspect "${data_volume}" >/dev/null 2>&1; then
        docker volume create \
            --label "ca.floo.roadtrip.data-sha=${data_sha}" \
            --label "ca.floo.roadtrip.managed=true" \
            --label "ca.floo.roadtrip.data-image=${data_image}" \
            "${data_volume}" >/dev/null
    fi
    docker run --rm \
        --mount "type=volume,src=${data_volume},dst=/target" \
        "${data_image}"
    actual_sha="$(docker run --rm \
        --entrypoint sh \
        --mount "type=volume,src=${data_volume},dst=/target,readonly" \
        "${data_image}" \
        -c 'cat /target/.roadtrip-data-sha')"
    if [[ "${actual_sha}" != "${data_sha}" ]]; then
        echo "error: data volume marker is ${actual_sha}, expected ${data_sha}" >&2
        exit 1
    fi
}

_prune_roadtrip_images() {
    local retention="${ROADTRIP_IMAGE_RETENTION:-336h}"
    local keep="${ROADTRIP_IMAGE_KEEP:-5}"
    local repository
    local reference
    local image_id
    local index
    local repository_keep
    local -a references

    echo "==> pruning unused Roadtrip images (keep ${keep} tags per active repository)"
    for repository in \
        ghcr.io/wwchen/roadtrip/backend \
        ghcr.io/wwchen/roadtrip/recgov-companion \
        ghcr.io/wwchen/roadtrip/data \
        roadtrip/backend \
        roadtrip/recgov-companion \
        ghcr.io/wwchen/roadtrip/deploy; do
        repository_keep="${keep}"
        [[ "${repository}" == "ghcr.io/wwchen/roadtrip/deploy" ]] && repository_keep=0
        references=()
        while IFS= read -r reference; do
            references+=("${reference}")
        done < <(
            docker image ls "${repository}" --format '{{.Repository}}:{{.Tag}}' \
                | awk '$0 !~ /:<none>$/ && !seen[$0]++'
        )
        for index in "${!references[@]}"; do
            (( index < repository_keep )) && continue
            reference="${references[$index]}"
            image_id="$(docker image inspect --format '{{.Id}}' "${reference}" 2>/dev/null || true)"
            [[ -n "${image_id}" ]] || continue
            if [[ -z "$(docker ps -aq --filter "ancestor=${image_id}")" ]]; then
                docker image rm "${reference}" >/dev/null 2>&1 || true
            fi
        done
    done

    docker image prune -f \
        --filter "label=ca.floo.roadtrip.managed=true" \
        --filter "until=${retention}" >/dev/null
}

_deploy_prod() {
    if [[ $# -lt 3 ]]; then
        echo "usage: deploy.sh prod <app-sha> <data-tree-sha> <companion-tree-sha> [branch]" >&2
        exit 2
    fi
    local app_sha="$1"
    local data_sha="$2"
    local companion_sha="$3"
    local branch="${4:-master}"
    local caddy_dir="${SANDBOX_CADDY_DIR:-${HOME}/.roadtrip/caddy/sandboxes}"
    local wait_seconds="${ROADTRIP_DEPLOY_WAIT_SECONDS:-180}"
    local -a compose=(docker compose -f docker-compose.yml -f docker-compose.secrets.yml --profile tunnel --profile pois --profile recgov-companion)
    local -a secret_exec=(./secrets/manage.py exec prod --)

    _require_sha "app SHA" "${app_sha}"
    _require_sha "data tree SHA" "${data_sha}"
    _require_sha "companion tree SHA" "${companion_sha}"

    export ROADTRIP_BACKEND_IMAGE="ghcr.io/wwchen/roadtrip/backend:${app_sha}"
    export ROADTRIP_COMPANION_IMAGE="ghcr.io/wwchen/roadtrip/recgov-companion:${companion_sha}"
    export ROADTRIP_DATA_IMAGE="ghcr.io/wwchen/roadtrip/data:${data_sha}"
    export ROADTRIP_DATA_SHA="${data_sha}"
    export ROADTRIP_DATA_VOLUME="roadtrip-data-${data_sha}"
    export ROADTRIP_BUILD_ENV=prod
    export ROADTRIP_BUILD_SHA="${app_sha}"
    export ROADTRIP_BUILD_BRANCH="${branch}"
    export SANDBOX_CADDY_DIR="${caddy_dir}"

    mkdir -p "${caddy_dir}"
    if ! find "${caddy_dir}" -name '*.caddy' -print -quit | grep -q . \
        && docker container inspect roadtrip-caddy-1 >/dev/null 2>&1; then
        docker cp roadtrip-caddy-1:/etc/caddy/sandboxes/. "${caddy_dir}/"
    fi

    _ensure_data_volume "${data_sha}"
    "${secret_exec[@]}" "${compose[@]}" pull backend recgov-companion
    "${secret_exec[@]}" "${compose[@]}" up -d --force-recreate backend
    "${secret_exec[@]}" "${compose[@]}" up -d
    "${secret_exec[@]}" "${compose[@]}" restart grafana alloy tempo prometheus loki
    "${secret_exec[@]}" "${compose[@]}" up -d --wait --wait-timeout "${wait_seconds}"
    _prune_roadtrip_images
    echo "==> production deployed: ${app_sha}"
}

COMMAND="${1:-}"
if [[ "${COMMAND}" == "prod" ]]; then
    shift
    _deploy_prod "$@"
    exit 0
fi

# ── Argument handling ─────────────────────────────────────────────────────────
if [[ $# -lt 2 ]]; then
    echo "usage: deploy.sh sandbox-up <ref> [name]" >&2
    echo "       deploy.sh sandbox-down <name>" >&2
    echo "       deploy.sh prod <app-sha> <data-tree-sha> <companion-tree-sha> [branch]" >&2
    exit 1
fi

DEPLOY_ENV="$1"
REF="$2"
NAME_OVERRIDE="${3:-}"

# ── Host config file ──────────────────────────────────────────────────────────
SANDBOX_ENV_FILE="${SANDBOX_ENV_FILE:-/var/lib/roadtrip-sandboxes/sandbox.env}"
if [[ -f "${SANDBOX_ENV_FILE}" ]]; then
    # shellcheck disable=SC1090
    source "${SANDBOX_ENV_FILE}"
fi

# ── Tunables ──────────────────────────────────────────────────────────────────

SANDBOX_TUNNEL_ZONE="${SANDBOX_TUNNEL_ZONE:-floo.ca}"

SANDBOX_HOST_PREFIX="${SANDBOX_HOST_PREFIX:-roadtrip-sb-}"

# shellcheck source=scripts/cloudflare_sandbox.sh
source "${SCRIPT_DIR}/cloudflare_sandbox.sh"

SANDBOX_CADDY_DIR="${SANDBOX_CADDY_DIR:-${REPO_ROOT}/caddy/sandboxes}"

SANDBOX_CADDY_CONFIG="${SANDBOX_CADDY_CONFIG:-/etc/caddy/Caddyfile}"

SANDBOX_CADDY_CONTAINER="${SANDBOX_CADDY_CONTAINER:-roadtrip-caddy-1}"

SANDBOX_NETWORK="${SANDBOX_NETWORK:-roadtrip-sandbox}"

SANDBOX_BACKEND_PORT="${SANDBOX_BACKEND_PORT:-8765}"

SANDBOX_STATE_DIR="${SANDBOX_STATE_DIR:-/var/lib/roadtrip-sandboxes}"

SANDBOX_SNAPSHOT_PATH="${SANDBOX_SNAPSHOT_PATH:-}"

SANDBOX_PORT_RANGE_START="${SANDBOX_PORT_RANGE_START:-41000}"
SANDBOX_PORT_RANGE_END="${SANDBOX_PORT_RANGE_END:-41999}"

SANDBOX_DB_PASSWORD="${SANDBOX_DB_PASSWORD:-sandbox}"

SEED_SQL="${SEED_SQL:-${SCRIPT_DIR}/sandbox_seed_users.sql}"

SCRUB_SQL="${SCRUB_SQL:-${SCRIPT_DIR}/sandbox_scrub.sql}"

POSTGRES_DB="${POSTGRES_DB:-roadtrip}"
POSTGRES_USER="${POSTGRES_USER:-roadtrip}"

HEALTH_RETRIES="${HEALTH_RETRIES:-60}"

POSTGRES_HEALTH_RETRIES="${POSTGRES_HEALTH_RETRIES:-30}"

_sandbox_down() {
    local sandbox_name="$1"
    local compose_project="roadtrip-sb-${sandbox_name}"
    local marker="${SANDBOX_STATE_DIR}/${sandbox_name}.meta"
    local data_sha
    local caddy_snippet="${SANDBOX_CADDY_DIR}/sb-${sandbox_name}.caddy"

    _require_sandbox_name "${sandbox_name}"
    data_sha="$(sed -n 's/^DATA_SHA=//p' "${marker}" 2>/dev/null | head -1)"
    export ROADTRIP_DATA_VOLUME="${ROADTRIP_DATA_VOLUME:-roadtrip-data-${data_sha:-legacy}}"

    echo "==> tearing down sandbox: ${sandbox_name} (project: ${compose_project})"
    docker compose -p "${compose_project}" -f "${REPO_ROOT}/docker-compose.sandbox.yml" down -v

    if [[ -f "${caddy_snippet}" ]]; then
        rm -f "${caddy_snippet}"
        docker exec "${SANDBOX_CADDY_CONTAINER}" \
            caddy reload --config "${SANDBOX_CADDY_CONFIG}" \
            || echo "==> warning: caddy reload failed; snippet was removed"
    fi

    cf_sandbox_down "${SANDBOX_HOST_PREFIX}${sandbox_name}.${SANDBOX_TUNNEL_ZONE}" \
        || echo "==> warning: Cloudflare teardown failed for ${sandbox_name}"
    rm -f "${marker}"
    _prune_roadtrip_images
    echo "==> sandbox ${sandbox_name} is down"
}

if [[ "${DEPLOY_ENV}" == "sandbox-down" ]]; then
    _sandbox_down "${REF}"
    exit 0
fi

# ── Env-specific config ───────────────────────────────────────────────────────
case "${DEPLOY_ENV}" in
    sandbox|sandbox-up)
        DEPLOY_ENV="sandbox"
        ROUTING="caddy-vhost"
        COMPOSE_FILE="${COMPOSE_FILE:-${REPO_ROOT}/docker-compose.sandbox.yml}"
        DO_DB_PREP="true"
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
    SANDBOX_NAME="$(printf '%s' "${REF}" \
        | tr '[:upper:]' '[:lower:]' \
        | sed 's/[^a-z0-9]\{1,\}/-/g' \
        | sed 's/^-//; s/-$//')"
fi

if [[ -z "${SANDBOX_NAME}" ]]; then
    echo "error: could not derive a sandbox name from ref '${REF}'" >&2
    exit 1
fi
_require_sandbox_name "${SANDBOX_NAME}"

COMPOSE_PROJECT="roadtrip-sb-${SANDBOX_NAME}"

echo "==> ${DEPLOY_ENV}: ${SANDBOX_NAME}  (project: ${COMPOSE_PROJECT})"

# ── Resolve the image SHA ─────────────────────────────────────────────────────
SANDBOX_SHA="${SANDBOX_SHA:-${REF}}"
SANDBOX_BRANCH="${SANDBOX_BRANCH:-${REF}}"
SANDBOX_BUILD_SHA="${SANDBOX_BUILD_SHA:-${SANDBOX_SHA}}"
_require_sha "sandbox SHA" "${SANDBOX_SHA}"

if [[ -z "${ROADTRIP_DATA_SHA:-}" ]]; then
    if git -C "${REPO_ROOT}" rev-parse HEAD:data >/dev/null 2>&1; then
        ROADTRIP_DATA_SHA="$(git -C "${REPO_ROOT}" rev-parse HEAD:data)"
    else
        echo "error: ROADTRIP_DATA_SHA is required outside a Git checkout" >&2
        exit 1
    fi
fi
_require_sha "data tree SHA" "${ROADTRIP_DATA_SHA}"
ROADTRIP_DATA_VOLUME="${ROADTRIP_DATA_VOLUME:-roadtrip-data-${ROADTRIP_DATA_SHA}}"
ROADTRIP_DATA_IMAGE="${ROADTRIP_DATA_IMAGE:-ghcr.io/wwchen/roadtrip/data:${ROADTRIP_DATA_SHA}}"

_ensure_data_volume "${ROADTRIP_DATA_SHA}" >/dev/null

# ── Allocate a free host-local port ──────────────────────────────────────────
_port_in_use() {
    local port="$1"
    if command -v nc >/dev/null 2>&1; then
        nc -z 127.0.0.1 "${port}" 2>/dev/null
    elif command -v ss >/dev/null 2>&1; then
        ss -tnl 2>/dev/null | grep -q ":${port} "
    else
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
export SANDBOX_NAME
export ROADTRIP_DATA_SHA
export ROADTRIP_DATA_VOLUME

# ── Ensure the shared proxy network exists ───────────────────────────────────
if [[ "${ROUTING}" == "caddy-vhost" ]]; then
    if ! docker network inspect "${SANDBOX_NETWORK}" >/dev/null 2>&1; then
        echo "==> creating shared proxy network: ${SANDBOX_NETWORK}"
        docker network create \
            --label "com.docker.compose.network=${SANDBOX_NETWORK}" \
            "${SANDBOX_NETWORK}" >/dev/null
    fi
fi

# ── Start Compose services ────────────────────────────────────────────────────

HAVE_SNAPSHOT="false"
if [[ "${DO_DB_PREP}" == "true" && -n "${SANDBOX_SNAPSHOT_PATH}" && -f "${SANDBOX_SNAPSHOT_PATH}" ]]; then
    HAVE_SNAPSHOT="true"
fi

if [[ "${HAVE_SNAPSHOT}" == "true" ]]; then
    echo "==> docker compose up postgres (snapshot path; starting DB before backend)"
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        up -d postgres
else
    echo "==> docker compose up (project ${COMPOSE_PROJECT})"
    docker compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        up -d
fi

# ── Wait for postgres init to COMPLETE, then be healthy ──────────────────────
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

        echo "==> docker compose up (remaining services)"
        docker compose \
            -p "${COMPOSE_PROJECT}" \
            -f "${COMPOSE_FILE}" \
            up -d
    else
        echo "==> no snapshot to restore (SANDBOX_SNAPSHOT_PATH not set or file absent)"
    fi
fi

# ── Register vhost ────────────────────────────────────────────────────────────
case "${ROUTING}" in
    caddy-vhost)
        CADDY_SNIPPET="${SANDBOX_CADDY_DIR}/sb-${SANDBOX_NAME}.caddy"
        mkdir -p "${SANDBOX_CADDY_DIR}"
        SANDBOX_FQDN="${SANDBOX_HOST_PREFIX}${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
        cat > "${CADDY_SNIPPET}" <<CADDY
http://${SANDBOX_FQDN} {
    reverse_proxy sb-${SANDBOX_NAME}-backend:${SANDBOX_BACKEND_PORT}
}
CADDY
        echo "==> wrote Caddy snippet: ${CADDY_SNIPPET}"
        echo "==> reloading Caddy (docker exec ${SANDBOX_CADDY_CONTAINER})"
        docker exec "${SANDBOX_CADDY_CONTAINER}" \
            caddy reload --config "${SANDBOX_CADDY_CONFIG}"
        if ! cf_sandbox_up "${SANDBOX_FQDN}"; then
            echo "error: Cloudflare provisioning failed for ${SANDBOX_FQDN}; not marking the sandbox live" >&2
            exit 1
        fi
        ;;
    direct)
        ;;
    *)
        echo "error: unknown routing '${ROUTING}'; supported: caddy-vhost, direct" >&2
        exit 1
        ;;
esac

# ── Write state marker (consumed by sandbox_reap.sh) ─────────────────────────
mkdir -p "${SANDBOX_STATE_DIR}"
MARKER="${SANDBOX_STATE_DIR}/${SANDBOX_NAME}.meta"
printf 'NAME=%s\nPORT=%s\nSTART_EPOCH=%s\nDATA_SHA=%s\n' \
    "${SANDBOX_NAME}" \
    "${SANDBOX_PORT}" \
    "$(date +%s)" \
    "${ROADTRIP_DATA_SHA}" \
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

# ── Step 4 (no-snapshot path): seed users after Flyway ───────────────────────
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
_prune_roadtrip_images
SANDBOX_URL="https://${SANDBOX_HOST_PREFIX}${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
echo ""
echo "Sandbox is live: ${SANDBOX_URL}"
