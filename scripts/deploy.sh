#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RECLAIM="${SCRIPT_DIR}/reclaim.sh"

# Host-health guardrails. Defined here rather than in the tunables block below
# because the `prod` subcommand dispatches before that block is reached.
# A prod deploy pulls the backend, companion, and data images at once; 20GB
# clears that with room to spare while still tripping long before the volume
# fills. Override per-host with ROADTRIP_MIN_FREE_DISK_GB.
MIN_FREE_DISK_GB="${MIN_FREE_DISK_GB:-20}"

# Longer than deploy.yml's 30m timeout-minutes, so a deploy that GitHub still
# considers live is never killed; anything older is provably abandoned.
STALE_DEPLOY_SECONDS="${STALE_DEPLOY_SECONDS:-2400}"

# Which processes count as a deploy. Overridable so tests can scope the kill to
# their own fixture instead of every deploy running on the machine.
STALE_DEPLOY_PATTERN="${STALE_DEPLOY_PATTERN:-deploy\.sh (prod|sandbox-up|sandbox-down)}"

# Root of the host's release tree: releases/<sha> plus the `current` symlink the
# deploy workflow flips only after a deploy returns.
ROADTRIP_HOME="${ROADTRIP_HOME:-${HOME}/.roadtrip}"

# The last prod deploy that passed its health gate, and therefore the rollback
# target. Written only on success, so it can never name a broken release.
LAST_GOOD_RELEASE_FILE="${LAST_GOOD_RELEASE_FILE:-${ROADTRIP_HOME}/last-good-release}"

# How long a guard container holds a freshly created data volume open. Must
# outlast the slowest deploy (image pulls plus the health gate) and stay well
# under a leak that matters; deploy.yml's own timeout is 30m.
DATA_VOLUME_GUARD_SECONDS="${DATA_VOLUME_GUARD_SECONDS:-1800}"

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

# Deploys that ran while the daemon was wedged never return, so they pile up
# invisibly, each holding an SSH session and a half-applied Compose state. Clear
# the ones older than the workflow's own timeout: they cannot still be live.
_clear_stale_deploys() {
    local max_age="${ROADTRIP_STALE_DEPLOY_SECONDS:-${STALE_DEPLOY_SECONDS}}"
    local pattern="${ROADTRIP_STALE_DEPLOY_PATTERN:-${STALE_DEPLOY_PATTERN}}"
    local self="$$"
    local pid
    local age
    local -a stale=()

    while read -r pid age; do
        [[ -n "${pid}" ]] || continue
        (( pid == self )) && continue
        (( age > max_age )) && stale+=("${pid}")
    done < <(
        pgrep -f "${pattern}" 2>/dev/null \
            | while read -r pid; do
                  # macOS ps has no etimes, so convert [[dd-]hh:]mm:ss by hand.
                  age="$(ps -o etime= -p "${pid}" 2>/dev/null | tr -d ' ' | awk '
                      { days = 0; rest = $0
                        if (rest ~ /-/) { split(rest, d, "-"); days = d[1]; rest = d[2] }
                        n = split(rest, p, ":")
                        secs = (n == 3) ? p[1]*3600 + p[2]*60 + p[3] : p[1]*60 + p[2]
                        print days*86400 + secs }')"
                  [[ -n "${age}" ]] && printf '%s %s\n' "${pid}" "${age}"
              done
    )

    if (( ${#stale[@]} == 0 )); then
        return 0
    fi
    echo "==> clearing ${#stale[@]} stale deploy process tree(s) older than ${max_age}s: ${stale[*]}"
    for pid in "${stale[@]}"; do
        _kill_process_tree "${pid}"
    done
}

# pkill -P reaches only direct children, leaving the docker CLI and its plugins
# alive and still holding the daemon. Walk the whole tree instead. Children are
# snapshotted before the parent dies so they cannot be reparented out of reach;
# a live parent can still fork after the snapshot, which this does not prevent.
_kill_process_tree() {
    local root="$1"
    local child
    local -a children=()

    while read -r child; do
        [[ "${child}" =~ ^[0-9]+$ ]] || continue
        (( child == $$ )) && continue
        children+=("${child}")
    done < <(pgrep -P "${root}" 2>/dev/null || true)
    if (( ${#children[@]} > 0 )); then
        for child in "${children[@]}"; do
            _kill_process_tree "${child}"
        done
    fi
    kill -9 "${root}" 2>/dev/null || true
}

_DATA_VOLUME_GUARD=""

# A data volume is labelled `managed=true` from the moment it is created, but
# nothing mounts it until Compose brings the backend up minutes later. The
# sandbox sweep runs `docker volume prune --all` against that same label every
# 30 minutes, and prod, sandbox and sweep sit in three different GitHub
# concurrency groups, so nothing stops a prune landing inside that window and
# deleting a volume a deploy is about to depend on.
#
# Docker never prunes a volume a container refers to, so hold one over the
# window. The hold is a bounded `sleep` rather than an EXIT trap because the
# sandbox path already owns the EXIT trap for its slot lock, and because an
# abandoned deploy must not leave the volume unprunable forever. Relabelling
# the volume instead is not an option: Docker has no API for it.
_hold_data_volume() {
    local data_volume="$1"
    local data_image="$2"
    local seconds="${ROADTRIP_DATA_VOLUME_GUARD_SECONDS:-${DATA_VOLUME_GUARD_SECONDS}}"
    local name="roadtrip-data-guard-$$"

    _release_data_volume
    # PIDs are reused, so a guard from a long-dead deploy can still own the
    # name. Clearing it is safe: it can only be a guard.
    docker rm -f "${name}" >/dev/null 2>&1 || true
    if docker run -d --rm \
        --name "${name}" \
        --entrypoint sh \
        --mount "type=volume,src=${data_volume},dst=/target,readonly" \
        "${data_image}" \
        -c "sleep ${seconds}" >/dev/null 2>&1; then
        _DATA_VOLUME_GUARD="${name}"
        echo "==> holding ${data_volume} open against concurrent prunes (${name})"
    else
        echo "warning: could not hold ${data_volume}; a concurrent prune could remove it before it is mounted" >&2
    fi
}

# Safe to call when nothing is held, and safe to call twice.
_release_data_volume() {
    if [[ -n "${_DATA_VOLUME_GUARD}" ]]; then
        docker rm -f "${_DATA_VOLUME_GUARD}" >/dev/null 2>&1 || true
        _DATA_VOLUME_GUARD=""
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
    # Before the populate run, so the volume is only unreferenced for the
    # moment between `volume create` and this call.
    _hold_data_volume "${data_volume}" "${data_image}"
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

# ── Rollback ─────────────────────────────────────────────────────────────────
# Set for the duration of a rollback deploy so a rollback that is itself
# unhealthy stops instead of recursing into another one.
_ROLLBACK_ACTIVE=0
_ROLLBACK_FROM_SHA=""
_ROLLBACK_TO_SHA=""

# The release that is live right now. deploy.yml flips `current` only after
# deploy.sh returns, so mid-deploy this still names the release we would roll
# back to. The symlink target is authoritative; the marker install-release
# writes inside the release is the fallback.
_current_release_sha() {
    local link="${ROADTRIP_HOME}/current"
    local target=""
    local sha=""

    target="$(readlink "${link}" 2>/dev/null || true)"
    if [[ -n "${target}" ]]; then
        sha="$(basename "${target}")"
    fi
    if ! [[ "${sha}" =~ ^[0-9a-f]{40}$ ]]; then
        # 2>/dev/null before the input redirect, or the shell's own "No such
        # file" for a missing marker escapes to stderr.
        sha="$(tr -d '[:space:]' 2>/dev/null < "${link}/.roadtrip-release-sha" || true)"
    fi
    [[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || return 1
    printf '%s\n' "${sha}"
}

_record_last_good_release() {
    local app_sha="$1"
    local data_sha="$2"
    local companion_sha="$3"
    local branch="$4"
    local tmp="${LAST_GOOD_RELEASE_FILE}.tmp.$$"

    mkdir -p "$(dirname "${LAST_GOOD_RELEASE_FILE}")"
    printf 'APP_SHA=%s\nDATA_SHA=%s\nCOMPANION_SHA=%s\nBRANCH=%s\n' \
        "${app_sha}" "${data_sha}" "${companion_sha}" "${branch}" > "${tmp}"
    # Rename so a reader mid-write sees the previous record, never half of one.
    mv -f "${tmp}" "${LAST_GOOD_RELEASE_FILE}"
}

# Prints "<app-sha> <data-sha> <companion-sha> <branch>" for the release to roll
# back to, or fails when there is none. The last-good record is preferred: it
# names a triple that provably passed a health gate. Hosts deployed before that
# record existed fall back to the live release symlink, reusing the current data
# and companion SHAs because the old ones were never written down anywhere.
_previous_release() {
    local fallback_data="$1"
    local fallback_companion="$2"
    local app_sha=""
    local data_sha=""
    local companion_sha=""
    local branch=""

    if [[ -f "${LAST_GOOD_RELEASE_FILE}" ]]; then
        app_sha="$(sed -n 's/^APP_SHA=//p' "${LAST_GOOD_RELEASE_FILE}" | head -1)"
        data_sha="$(sed -n 's/^DATA_SHA=//p' "${LAST_GOOD_RELEASE_FILE}" | head -1)"
        companion_sha="$(sed -n 's/^COMPANION_SHA=//p' "${LAST_GOOD_RELEASE_FILE}" | head -1)"
        branch="$(sed -n 's/^BRANCH=//p' "${LAST_GOOD_RELEASE_FILE}" | head -1)"
    fi
    if ! [[ "${app_sha}" =~ ^[0-9a-f]{40}$ ]]; then
        app_sha="$(_current_release_sha || true)"
        data_sha="${fallback_data}"
        companion_sha="${fallback_companion}"
        branch=""
    fi

    [[ "${app_sha}" =~ ^[0-9a-f]{40}$ ]] || return 1
    [[ "${data_sha}" =~ ^[0-9a-f]{40}$ ]] || data_sha="${fallback_data}"
    [[ "${companion_sha}" =~ ^[0-9a-f]{40}$ ]] || companion_sha="${fallback_companion}"
    printf '%s %s %s %s\n' \
        "${app_sha}" "${data_sha}" "${companion_sha}" "${branch:-master}"
}

_rollback_failed_notice() {
    echo "==> ROLLBACK FAILED: ${_ROLLBACK_FROM_SHA} was unhealthy and ${_ROLLBACK_TO_SHA} did not come back up; production needs manual recovery" >&2
}

# Reuses the ordinary deploy path rather than a second, less-tested one. The
# EXIT trap is how a rollback that dies anywhere inside that path still says so
# — under `set -e` there is no return to inspect.
_rollback_prod() {
    _ROLLBACK_ACTIVE=1
    trap _rollback_failed_notice EXIT
    _deploy_prod "$@"
    trap - EXIT
    _ROLLBACK_ACTIVE=0
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
    local failure_log_lines="${ROADTRIP_DEPLOY_FAILURE_LOG_LINES:-120}"
    local previous=""
    local prev_app_sha=""
    local prev_data_sha=""
    local prev_companion_sha=""
    local prev_branch=""
    local -a compose=(docker compose -f docker-compose.yml -f docker-compose.secrets.yml --profile tunnel --profile pois --profile recgov-companion)
    local -a secret_exec=(./secrets/manage.py exec prod --)

    _require_sha "app SHA" "${app_sha}"
    _require_sha "data tree SHA" "${data_sha}"
    _require_sha "companion tree SHA" "${companion_sha}"

    _clear_stale_deploys
    "${RECLAIM}" check-disk --label "prod deploy" --scope host --min-gb "${ROADTRIP_MIN_FREE_DISK_GB:-${MIN_FREE_DISK_GB}}" || exit 1

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
    # Compose reports only "container X is unhealthy" when a dependency never
    # comes up, which says nothing about why. The backend's own log holds the
    # reason (a Flyway checksum mismatch, a bad secret), so surface it here
    # rather than making someone SSH to the host to read it.
    if ! "${secret_exec[@]}" "${compose[@]}" up -d --wait --wait-timeout "${wait_seconds}"; then
        echo "==> deploy did not come up healthy; last ${failure_log_lines} backend lines:" >&2
        "${secret_exec[@]}" "${compose[@]}" logs --tail "${failure_log_lines}" --no-color backend >&2 || true

        # A broken container is left live otherwise: Compose has already
        # recreated the backend, and the workflow's only remaining act is to
        # not flip the `current` symlink.
        if (( _ROLLBACK_ACTIVE == 1 )); then
            # The EXIT trap _rollback_prod installed says the rest.
            exit 1
        fi

        previous="$(_previous_release "${data_sha}" "${companion_sha}" || true)"
        if [[ -z "${previous}" ]]; then
            echo "==> ROLLBACK UNAVAILABLE: no previous release to fall back to; ${app_sha} is live and unhealthy" >&2
            exit 1
        fi
        read -r prev_app_sha prev_data_sha prev_companion_sha prev_branch <<<"${previous}"
        if [[ "${prev_app_sha}" == "${app_sha}" ]]; then
            echo "==> ROLLBACK UNAVAILABLE: ${app_sha} is itself the previous release; ${app_sha} is live and unhealthy" >&2
            exit 1
        fi

        echo "==> rolling production back to ${prev_app_sha}" >&2
        _ROLLBACK_FROM_SHA="${app_sha}"
        _ROLLBACK_TO_SHA="${prev_app_sha}"
        _rollback_prod \
            "${prev_app_sha}" "${prev_data_sha}" "${prev_companion_sha}" "${prev_branch}"
        echo "==> ROLLBACK OK: ${app_sha} was unhealthy; production is back on ${prev_app_sha}" >&2
        exit 1
    fi
    _release_data_volume
    _record_last_good_release "${app_sha}" "${data_sha}" "${companion_sha}" "${branch}"
    "${RECLAIM}" prune --scope host --no-include-anonymous
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

SCRUB_SQL="${SCRUB_SQL:-${SCRIPT_DIR}/sandbox_scrub.sql}"

# Fixed, not env-overridable: docker-compose.sandbox.yml hardcodes the same two
# values, so an override here would change only the host-side psql calls below
# and silently disagree with the database Compose actually started.
POSTGRES_DB="roadtrip"
POSTGRES_USER="roadtrip"

HEALTH_RETRIES="${HEALTH_RETRIES:-60}"

POSTGRES_HEALTH_RETRIES="${POSTGRES_HEALTH_RETRIES:-30}"

SANDBOX_SECRETS_ENV="local"
SANDBOX_TEARDOWN_COMPOSE_SHA="0000000000000000000000000000000000000000"
SANDBOX_SLOT_IDS=(1 2)

_require_sandbox_owner_name() {
    local value="$1"
    local slot

    _require_sandbox_name "${value}"
    for slot in "${SANDBOX_SLOT_IDS[@]}"; do
        if [[ "${value}" == "${slot}" ]]; then
            echo "error: sandbox name '${value}' is reserved for public slot teardown; use a non-numeric owner name" >&2
            exit 2
        fi
    done
}

_sandbox_compose() {
    "${REPO_ROOT}/secrets/manage.py" exec "${SANDBOX_SECRETS_ENV}" -- docker compose "$@"
}

_marker_field() {
    local marker="$1"
    local key="$2"
    sed -n "s/^${key}=//p" "${marker}" 2>/dev/null | head -1
}

_marker_for_slot() {
    local slot="$1"
    local marker

    for marker in "${SANDBOX_STATE_DIR}"/*.meta; do
        [[ -e "${marker}" ]] || continue
        if [[ "$(_marker_field "${marker}" SLOT)" == "${slot}" ]]; then
            printf '%s\n' "${marker}"
            return 0
        fi
    done
    return 1
}

_slot_has_containers() {
    local slot="$1"
    [[ -n "$(docker ps -aq --filter "label=com.docker.compose.project=roadtrip-sb-${slot}")" ]]
}

_slot_available() {
    local slot="$1"

    if _marker_for_slot "${slot}" >/dev/null; then
        return 1
    fi
    if [[ -f "${SANDBOX_CADDY_DIR}/sb-${slot}.caddy" ]]; then
        return 1
    fi
    if _slot_has_containers "${slot}"; then
        return 1
    fi
    return 0
}

_reserved_port() {
    local port="$1"
    local marker

    for marker in "${SANDBOX_STATE_DIR}"/*.meta; do
        [[ -e "${marker}" ]] || continue
        if [[ "$(_marker_field "${marker}" PORT)" == "${port}" ]]; then
            return 0
        fi
    done
    return 1
}

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

_allocate_port() {
    local port

    for port in $(seq "${SANDBOX_PORT_RANGE_START}" "${SANDBOX_PORT_RANGE_END}"); do
        if ! _port_in_use "${port}" && ! _reserved_port "${port}"; then
            printf '%s\n' "${port}"
            return 0
        fi
    done

    echo "error: no free port in range ${SANDBOX_PORT_RANGE_START}-${SANDBOX_PORT_RANGE_END}" >&2
    exit 1
}

_sandbox_lock_dir=""
_sandbox_lock_release() {
    if [[ -n "${_sandbox_lock_dir}" ]]; then
        # Ours may have been reclaimed while we held it.
        if [[ "$(sed -n '1p' "${_sandbox_lock_dir}/pid" 2>/dev/null)" == "$$" ]]; then
            rm -f "${_sandbox_lock_dir}/pid"
            rmdir "${_sandbox_lock_dir}" 2>/dev/null || true
        fi
        _sandbox_lock_dir=""
    fi
}

# GNU stat first because BSD stat rejects -c outright, while GNU stat *accepts*
# -f and prints filesystem junk with a zero exit -- so validate the output, not
# the exit status. Prints nothing when neither form works: the caller must not
# guess an age in either direction.
_lock_mtime_epoch() {
    local value

    value="$(stat -c %Y "$1" 2>/dev/null || true)"
    if ! [[ "${value}" =~ ^[0-9]+$ ]]; then
        value="$(stat -f %m "$1" 2>/dev/null || true)"
    fi
    [[ "${value}" =~ ^[0-9]+$ ]] || return 0
    printf '%s\n' "${value}"
}

# Rename is atomic: exactly one racer wins, the rest go back to waiting.
_sandbox_lock_steal() {
    local lock="$1"
    local aside="${lock}.stale.$$"

    if mv "${lock}" "${aside}" 2>/dev/null; then
        rm -rf "${aside}"
    fi
}

# A holder killed mid-flight used to leak this lock permanently, blocking every
# teardown and the sweep for two days on 2026-08-18. Record an owner so the next
# caller can reclaim it.
_sandbox_lock_acquire() {
    local lock="${SANDBOX_STATE_DIR}/.slot-lock"
    local max_wait="${ROADTRIP_SANDBOX_LOCK_WAIT_SECONDS:-60}"
    local grace="${ROADTRIP_SANDBOX_LOCK_OWNER_GRACE_SECONDS:-5}"
    local poll=1
    local waited=0
    local owner_pid
    local lock_age
    local lock_mtime
    local warned=0

    mkdir -p "${SANDBOX_STATE_DIR}"
    until mkdir "${lock}" 2>/dev/null; do
        # Age gates every reclaim: it belongs to the directory, so unlike the
        # PID we just read it cannot be stale and name a lock someone else has
        # since taken. Costs up to `grace` before recovery.
        owner_pid="$(sed -n '1p' "${lock}/pid" 2>/dev/null || true)"
        lock_mtime="$(_lock_mtime_epoch "${lock}")"
        if [[ "${lock_mtime}" =~ ^[0-9]+$ ]]; then
            lock_age=$(( $(date +%s) - lock_mtime ))
        else
            # Unknown age means we cannot tell an abandoned lock from a fresh
            # one, so refuse to reclaim and say so, rather than silently never
            # recovering (or, worse, stealing a live lock).
            if (( warned == 0 )); then
                echo "warning: cannot read mtime of ${lock}; lock recovery disabled" >&2
                warned=1
            fi
            lock_age=0
        fi
        if (( lock_age >= grace )); then
            if [[ "${owner_pid}" =~ ^[0-9]+$ ]]; then
                if ! kill -0 "${owner_pid}" 2>/dev/null; then
                    echo "==> reclaiming sandbox slot lock abandoned by PID ${owner_pid}"
                    _sandbox_lock_steal "${lock}"
                fi
            else
                # Died before writing a marker, or predates this recovery.
                echo "==> reclaiming ownerless sandbox slot lock (${lock_age}s old)"
                _sandbox_lock_steal "${lock}"
            fi
        fi

        # Every path advances `waited`, so max_wait stays a real bound.
        if (( waited >= max_wait )); then
            echo "error: timed out waiting for sandbox slot lock: ${lock}" >&2
            exit 1
        fi
        sleep "${poll}"
        waited=$(( waited + poll ))
    done
    # Before the trap: a failed write leaves a lock the grace path recovers.
    printf '%s\n' "$$" > "${lock}/pid"
    _sandbox_lock_dir="${lock}"
    trap _sandbox_lock_release EXIT
}

_resolve_sandbox_marker() {
    local requested="$1"
    local direct="${SANDBOX_STATE_DIR}/${requested}.meta"
    local by_slot

    if [[ -f "${direct}" ]]; then
        printf '%s\n' "${direct}"
        return 0
    fi
    by_slot="$(_marker_for_slot "${requested}" || true)"
    if [[ -n "${by_slot}" ]]; then
        printf '%s\n' "${by_slot}"
        return 0
    fi
    return 1
}

_write_sandbox_marker() {
    local status="$1"
    local marker="${SANDBOX_STATE_DIR}/${SANDBOX_OWNER}.meta"

    mkdir -p "${SANDBOX_STATE_DIR}"
    printf 'NAME=%s\nSLOT=%s\nPORT=%s\nSTART_EPOCH=%s\nDATA_SHA=%s\nURL=%s\nSTATUS=%s\n' \
        "${SANDBOX_OWNER}" \
        "${SANDBOX_SLOT}" \
        "${SANDBOX_PORT}" \
        "$(date +%s)" \
        "${ROADTRIP_DATA_SHA}" \
        "${SANDBOX_URL}" \
        "${status}" \
        > "${marker}"
    echo "==> wrote marker: ${marker}"
}

_sandbox_down() {
    local requested_name="$1"
    local marker
    local sandbox_owner
    local sandbox_slot
    local runtime_name
    local compose_project
    local data_sha
    local sandbox_port
    local sandbox_fqdn
    local sandbox_url
    local caddy_snippet
    local SANDBOX_NAME
    local SANDBOX_SHA
    local SANDBOX_BUILD_SHA
    local SANDBOX_BRANCH
    local SANDBOX_PORT
    local ROADTRIP_DATA_VOLUME
    local ROADTRIP_WEB_ROOT_URL

    _require_sandbox_name "${requested_name}"
    _sandbox_lock_acquire
    marker="$(_resolve_sandbox_marker "${requested_name}" || true)"
    if [[ -z "${marker}" ]]; then
        echo "error: sandbox marker is required for teardown: ${SANDBOX_STATE_DIR}/${requested_name}.meta" >&2
        exit 1
    fi
    sandbox_owner="$(basename "${marker}" .meta)"
    sandbox_slot="$(_marker_field "${marker}" SLOT)"
    runtime_name="${sandbox_slot:-${sandbox_owner}}"
    data_sha="$(sed -n 's/^DATA_SHA=//p' "${marker}" 2>/dev/null | head -1)"
    sandbox_port="$(sed -n 's/^PORT=//p' "${marker}" 2>/dev/null | head -1)"
    if [[ -z "${data_sha}" || -z "${sandbox_port}" ]]; then
        echo "error: sandbox marker is missing DATA_SHA or PORT: ${marker}" >&2
        exit 1
    fi
    _require_sandbox_name "${runtime_name}"
    compose_project="roadtrip-sb-${runtime_name}"
    sandbox_fqdn="${SANDBOX_HOST_PREFIX}${runtime_name}.${SANDBOX_TUNNEL_ZONE}"
    sandbox_url="https://${sandbox_fqdn}"
    caddy_snippet="${SANDBOX_CADDY_DIR}/sb-${runtime_name}.caddy"

    SANDBOX_NAME="${runtime_name}"
    SANDBOX_SHA="${SANDBOX_TEARDOWN_COMPOSE_SHA}"
    SANDBOX_BUILD_SHA="${SANDBOX_TEARDOWN_COMPOSE_SHA}"
    SANDBOX_BRANCH="${sandbox_owner}"
    SANDBOX_PORT="${sandbox_port}"
    ROADTRIP_DATA_VOLUME="roadtrip-data-${data_sha}"
    ROADTRIP_WEB_ROOT_URL="${sandbox_url}"
    export SANDBOX_NAME
    export SANDBOX_SHA
    export SANDBOX_BUILD_SHA
    export SANDBOX_BRANCH
    export SANDBOX_PORT
    export ROADTRIP_DATA_VOLUME
    export ROADTRIP_WEB_ROOT_URL

    echo "Sandbox was: ${sandbox_url}"
    echo "==> tearing down sandbox: ${sandbox_owner} (slot: ${runtime_name}, project: ${compose_project})"
    _sandbox_compose -p "${compose_project}" -f "${REPO_ROOT}/docker-compose.sandbox.yml" down -v

    if [[ -f "${caddy_snippet}" ]]; then
        rm -f "${caddy_snippet}"
        docker exec "${SANDBOX_CADDY_CONTAINER}" \
            caddy reload --config "${SANDBOX_CADDY_CONFIG}" \
            || echo "==> warning: caddy reload failed; snippet was removed"
    fi

    cf_sandbox_down "${sandbox_fqdn}" \
        || echo "==> warning: Cloudflare teardown failed for ${sandbox_owner}"
    rm -f "${marker}"
    _sandbox_lock_release
    "${RECLAIM}" prune --scope host --no-include-anonymous
    echo "==> sandbox ${sandbox_owner} is down"
}

if [[ "${DEPLOY_ENV}" == "sandbox-down" ]]; then
    # Teardown skips the disk check because it frees space, but a pile of hung
    # deploys blocks it just as surely as a full disk does.
    _clear_stale_deploys
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

# ── Host health ───────────────────────────────────────────────────────────────
# Sandboxes share the deploy host with prod, so a sandbox that fills the disk
# deadlocks the daemon for prod too. Teardown is exempt: it frees space.
_clear_stale_deploys
"${RECLAIM}" check-disk --label "sandbox deploy" --scope host --min-gb "${ROADTRIP_MIN_FREE_DISK_GB:-${MIN_FREE_DISK_GB}}" || exit 1

# ── Derive logical sandbox owner ──────────────────────────────────────────────
if [[ -n "${NAME_OVERRIDE}" ]]; then
    SANDBOX_OWNER="${NAME_OVERRIDE}"
elif [[ "${REF}" =~ ^[0-9]+$ ]]; then
    SANDBOX_OWNER="pr${REF}"
else
    SANDBOX_OWNER="$(printf '%s' "${REF}" \
        | tr '[:upper:]' '[:lower:]' \
        | sed 's/[^a-z0-9]\{1,\}/-/g' \
        | sed 's/^-//; s/-$//')"
fi

if [[ -z "${SANDBOX_OWNER}" ]]; then
    echo "error: could not derive a sandbox name from ref '${REF}'" >&2
    exit 1
fi
_require_sandbox_owner_name "${SANDBOX_OWNER}"

# ── Resolve the image SHA ─────────────────────────────────────────────────────
: "${SANDBOX_SHA:?SANDBOX_SHA is required}"
: "${SANDBOX_BRANCH:?SANDBOX_BRANCH is required}"
SANDBOX_BUILD_SHA="${SANDBOX_SHA}"
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
ROADTRIP_DATA_VOLUME="roadtrip-data-${ROADTRIP_DATA_SHA}"
ROADTRIP_DATA_IMAGE="${ROADTRIP_DATA_IMAGE:-ghcr.io/wwchen/roadtrip/data:${ROADTRIP_DATA_SHA}}"

_ensure_data_volume "${ROADTRIP_DATA_SHA}" >/dev/null

# ── Allocate a fixed public slot + free host-local port ──────────────────────
legacy_marker="${SANDBOX_STATE_DIR}/${SANDBOX_OWNER}.meta"
if [[ -f "${legacy_marker}" && -z "$(_marker_field "${legacy_marker}" SLOT)" ]]; then
    echo "==> retiring legacy per-name sandbox before assigning a numbered slot"
    _sandbox_down "${SANDBOX_OWNER}"
fi

_sandbox_lock_acquire
existing_marker="${SANDBOX_STATE_DIR}/${SANDBOX_OWNER}.meta"
SANDBOX_SLOT=""
if [[ -f "${existing_marker}" ]]; then
    SANDBOX_SLOT="$(_marker_field "${existing_marker}" SLOT)"
    case "${SANDBOX_SLOT}" in
        1|2|3|4|5) ;;
        *)
            echo "error: sandbox marker has invalid SLOT: ${existing_marker}" >&2
            exit 1
            ;;
    esac
    conflicting_marker="$(_marker_for_slot "${SANDBOX_SLOT}" || true)"
    if [[ -n "${conflicting_marker}" && "${conflicting_marker}" != "${existing_marker}" ]]; then
        echo "error: slot ${SANDBOX_SLOT} is also claimed by ${conflicting_marker}" >&2
        exit 1
    fi
else
    for slot in "${SANDBOX_SLOT_IDS[@]}"; do
        if _slot_available "${slot}"; then
            SANDBOX_SLOT="${slot}"
            break
        fi
    done
fi
if [[ -z "${SANDBOX_SLOT}" ]]; then
    echo "error: no empty sandbox slots; checked ${SANDBOX_SLOT_IDS[*]}" >&2
    exit 1
fi

SANDBOX_NAME="${SANDBOX_SLOT}"
COMPOSE_PROJECT="roadtrip-sb-${SANDBOX_NAME}"
SANDBOX_FQDN="${SANDBOX_HOST_PREFIX}${SANDBOX_NAME}.${SANDBOX_TUNNEL_ZONE}"
SANDBOX_URL="https://${SANDBOX_FQDN}"
SANDBOX_PORT="$(_allocate_port)"
echo "==> ${DEPLOY_ENV}: ${SANDBOX_OWNER}  (slot: ${SANDBOX_SLOT}, project: ${COMPOSE_PROJECT})"
echo "==> allocated port ${SANDBOX_PORT}"
_write_sandbox_marker "starting"
_sandbox_lock_release

# ── Export vars consumed by docker-compose.sandbox.yml ───────────────────────
export SANDBOX_SHA
export SANDBOX_BUILD_SHA
export SANDBOX_BRANCH
export SANDBOX_PORT
export SANDBOX_NAME
export SANDBOX_OWNER
export SANDBOX_SLOT
export ROADTRIP_DATA_SHA
export ROADTRIP_DATA_VOLUME
export ROADTRIP_WEB_ROOT_URL="${SANDBOX_URL}"

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
    _sandbox_compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        up -d postgres
else
    echo "==> docker compose up (project ${COMPOSE_PROJECT})"
    _sandbox_compose \
        -p "${COMPOSE_PROJECT}" \
        -f "${COMPOSE_FILE}" \
        up -d
fi

# ── Wait for postgres init to COMPLETE, then be healthy ──────────────────────
_postgres_log() {
    _sandbox_compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" logs postgres 2>/dev/null
}
_postgres_healthy() {
    _sandbox_compose \
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

# ── Prepare DB (snapshot restore + PII scrub) ────────────────────────────────
if [[ "${DO_DB_PREP}" == "true" ]]; then
    if [[ "${HAVE_SNAPSHOT}" == "true" ]]; then
        already_initialized="$(_sandbox_compose \
            -p "${COMPOSE_PROJECT}" \
            -f "${COMPOSE_FILE}" \
            exec -T postgres \
            psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -tAc \
            "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" \
            2>/dev/null | tr -d '[:space:]')"

        if [[ "${already_initialized}" == "t" ]]; then
            echo "==> DB already initialized (re-up); skipping restore"
        else
            echo "==> restoring snapshot: ${SANDBOX_SNAPSHOT_PATH}"
            _sandbox_compose \
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
            _sandbox_compose \
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
        fi

        echo "==> docker compose up (remaining services)"
        _sandbox_compose \
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

# ── Mark the reserved slot live ───────────────────────────────────────────────
_write_sandbox_marker "live"

# ── Done ──────────────────────────────────────────────────────────────────────
# The sandbox backend now mounts the data volume, so Docker's own refcount is
# protection enough and the guard can go before we prune.
_release_data_volume
"${RECLAIM}" prune --scope host --no-include-anonymous
echo ""
echo "Sandbox is live: ${SANDBOX_URL}"
