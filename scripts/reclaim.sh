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

_parse_args() {
    COMMAND="${1:-}"
    [[ -n "${COMMAND}" ]] || { _usage; exit 2; }
    shift
    while (( $# )); do
        case "$1" in
            --scope) SCOPE="$2"; shift 2 ;;
            --dry-run) DRY_RUN=1; shift ;;
            --min-gb) MIN_GB="$2"; shift 2 ;;
            --path) DISK_PATH="$2"; shift 2 ;;
            --label) LABEL="$2"; shift 2 ;;
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
cmd_check_disk() {
    local free_kb free_gb
    free_kb="$(df -Pk "${DISK_PATH}" | awk 'NR==2 {print $4}')"
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
        *) echo "error: unknown command ${COMMAND}" >&2; _usage; exit 2 ;;
    esac
}

main "$@"
