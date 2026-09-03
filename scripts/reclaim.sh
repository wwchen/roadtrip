#!/usr/bin/env bash
# The single implementation of disk reclaim for prod deploys, the sandbox
# sweep, and local development. Three copies of this logic used to exist and
# the local one was simply never written, which is how ~21GB accumulated on a
# developer machine with a healthy deploy host.
#
# The host is shared with unrelated Docker stacks, so every destructive call
# stays inside `ca.floo.roadtrip.managed=true`. The one exception is the
# anonymous-volume prune, which by definition cannot filter on a label; it is
# gated behind --include-anonymous, on for --scope=host and off for
# --scope=local.
set -euo pipefail

MANAGED_LABEL="ca.floo.roadtrip.managed=true"

: "${ROADTRIP_IMAGE_RETENTION:=336h}"
: "${RECLAIM_FREE_TARGET_GB:=20}"

# Rollback depth on the host; pure disk pressure locally. A laptop keeps two
# because Tilt rewrites a tag per build and four tags already sit inside a
# keep-5 window, which would free nothing.
HOST_IMAGE_KEEP=5
LOCAL_IMAGE_KEEP=2

# Repository list rather than a label filter. The keep-N window is a
# per-repository idea, and the explicit allowlist is itself the safety
# boundary for these `image rm` calls — they take a bare reference, so
# unlike every other destructive call here they are scoped by name, not
# by label. Keep the list tight. The dangling prune below is label-scoped.
ROADTRIP_REPOSITORIES="
ghcr.io/wwchen/roadtrip/backend
ghcr.io/wwchen/roadtrip/recgov-companion
ghcr.io/wwchen/roadtrip/data
roadtrip/backend
roadtrip/recgov-companion
ghcr.io/wwchen/roadtrip/deploy
"
# The deploy image is rebuilt per release and never rolled back to.
NEVER_KEEP_REPOSITORY="ghcr.io/wwchen/roadtrip/deploy"

SCOPE=host
DRY_RUN=0
INCLUDE_ANONYMOUS=""
MIN_GB=""
DISK_PATH="${HOME}"
LABEL="reclaim"

_usage() {
    cat >&2 <<'USAGE'
usage: reclaim.sh <command> [options]

commands:
  prune        label-scoped reclaim of containers, images, and volumes
  check-disk   exit non-zero when free space is under the floor
  report       print what prune would remove, change nothing

options:
  --scope local|host     default: host
  --dry-run
  --min-gb N
  --path PATH
  --label TEXT
  --include-anonymous
USAGE
}

_need_value() {
    (( $# >= 2 )) || { echo "error: $1 requires a value" >&2; _usage; exit 2; }
}

_parse_args() {
    COMMAND="${1:-}"
    [[ -n "${COMMAND}" ]] || { _usage; exit 2; }
    shift
    while (( $# )); do
        case "$1" in
            --scope) _need_value "$@"; SCOPE="$2"; shift 2 ;;
            --dry-run) DRY_RUN=1; shift ;;
            --min-gb) _need_value "$@"; MIN_GB="$2"; shift 2 ;;
            --path) _need_value "$@"; DISK_PATH="$2"; shift 2 ;;
            --label) _need_value "$@"; LABEL="$2"; shift 2 ;;
            --include-anonymous) INCLUDE_ANONYMOUS=1; shift ;;
            *) echo "error: unknown option $1" >&2; _usage; exit 2 ;;
        esac
    done
    case "${SCOPE}" in
        host|local) ;;
        *) echo "error: --scope must be 'local' or 'host'" >&2; exit 2 ;;
    esac
}

_apply_scope_defaults() {
    if [[ "${SCOPE}" == host ]]; then
        : "${ROADTRIP_IMAGE_KEEP:=${HOST_IMAGE_KEEP}}"
        [[ -n "${INCLUDE_ANONYMOUS}" ]] || INCLUDE_ANONYMOUS=1
    else
        : "${ROADTRIP_IMAGE_KEEP:=${LOCAL_IMAGE_KEEP}}"
        [[ -n "${INCLUDE_ANONYMOUS}" ]] || INCLUDE_ANONYMOUS=0
    fi
    : "${MIN_GB:=${ROADTRIP_MIN_FREE_DISK_GB:-${RECLAIM_FREE_TARGET_GB}}}"
}

# A full disk does not fail a Docker call, it deadlocks the daemon: Docker
# wedges on its own ENOSPC and every later `docker` invocation blocks forever
# on a socket that accepts connections but never answers. That took prod down
# for 14h once.
# Reads go straight to docker; only destructive calls route through here, so
# --dry-run still sees a real image list and reports real decisions.
_docker() {
    if (( DRY_RUN )); then
        echo "dry-run: docker $*"
        return 0
    fi
    docker "$@"
}

# Silences the real call's output but still announces itself under
# --dry-run, which is the whole point of `report`.
_docker_quiet() {
    if (( DRY_RUN )); then
        echo "dry-run: docker $*"
        return 0
    fi
    docker "$@" >/dev/null 2>&1
}

_prune_images() {
    local repository reference image_id index repository_keep
    local references

    echo "==> pruning unused Roadtrip images (keep ${ROADTRIP_IMAGE_KEEP} tags per active repository)"
    for repository in ${ROADTRIP_REPOSITORIES}; do
        repository_keep="${ROADTRIP_IMAGE_KEEP}"
        [[ "${repository}" == "${NEVER_KEEP_REPOSITORY}" ]] && repository_keep=0
        references=()
        while IFS= read -r reference; do
            [[ -n "${reference}" ]] && references+=("${reference}")
        done < <(
            docker image ls "${repository}" --format '{{.Repository}}:{{.Tag}}' \
                | awk '$0 !~ /:<none>$/ && !seen[$0]++'
        )
        (( ${#references[@]} )) || continue
        for index in "${!references[@]}"; do
            (( index < repository_keep )) && continue
            reference="${references[$index]}"
            image_id="$(docker image inspect --format '{{.Id}}' "${reference}" 2>/dev/null || true)"
            [[ -n "${image_id}" ]] || continue
            if [[ -z "$(docker ps -aq --filter "ancestor=${image_id}")" ]]; then
                _docker_quiet image rm "${reference}" || true
            fi
        done
    done

    _docker_quiet image prune -f \
        --filter "label=${MANAGED_LABEL}" \
        --filter "until=${ROADTRIP_IMAGE_RETENTION}"
}

# One roadtrip-data-<sha> volume per data tree SHA, which the image prune never
# touches. --all because these are named. Rollback depth is the image
# retention's job: deploy.sh repopulates a missing volume, so these are a
# cache, not the record.
_prune_volumes() {
    echo "==> pruning unused Roadtrip data volumes"
    _docker volume prune --force --all --filter "label=${MANAGED_LABEL}" | tail -1
    if (( INCLUDE_ANONYMOUS )); then
        echo "==> pruning anonymous volumes"
        _docker volume prune --force | tail -1
    fi
}

_prune_containers() {
    _docker container prune --force --filter "label=${MANAGED_LABEL}" | tail -1
}

cmd_prune() {
    _prune_containers
    _prune_images
    _prune_volumes
}

cmd_report() {
    DRY_RUN=1
    cmd_prune
    docker system df
}

cmd_check_disk() {
    local free_kb free_gb
    free_kb="$(df -Pk "${DISK_PATH}" 2>/dev/null | awk 'NR==2 {print $4}' || true)"
    if [[ -z "${free_kb}" ]]; then
        echo "warning: could not read free space on ${DISK_PATH}; skipping ${LABEL} disk check" >&2
        return 0
    fi
    free_gb=$(( free_kb / 1024 / 1024 ))
    if (( free_gb < MIN_GB )); then
        echo "error: ${LABEL} needs ${MIN_GB}GB free on ${DISK_PATH}, found ${free_gb}GB" >&2
        echo "       a full disk deadlocks the Docker daemon; reclaim space before deploying" >&2
        echo "       (run 'scripts/reclaim.sh prune', or 'docker image prune -f' on the host)" >&2
        return 1
    fi
    echo "==> disk check: ${free_gb}GB free on ${DISK_PATH} (minimum ${MIN_GB}GB)"
}

main() {
    _parse_args "$@"
    _apply_scope_defaults
    case "${COMMAND}" in
        check-disk) cmd_check_disk ;;
        prune) cmd_prune ;;
        report) cmd_report ;;
        *) echo "error: unknown command ${COMMAND}" >&2; _usage; exit 2 ;;
    esac
}

main "$@"
