#!/usr/bin/env bash
# sandbox_reap.sh
#
# Tear down sandboxes whose START_EPOCH is older than SANDBOX_TTL_HOURS.
#
# Reads every ${SANDBOX_STATE_DIR}/*.meta marker written by sandbox_up.sh,
# parses START_EPOCH, and calls sandbox_down.sh for any sandbox whose age
# exceeds the TTL.  Fresh sandboxes are left running.
#
# Intended to run on a schedule.  Example cron entry (hourly):
#
#   0 * * * * root /path/to/scripts/sandbox_reap.sh >> /var/log/sandbox-reap.log 2>&1
#
# Runtime assumptions (documented):
#   - sandbox_down.sh lives alongside this script (same directory).
#   - Marker files are in the format written by sandbox_up.sh:
#       NAME=<name>
#       PORT=<port>
#       START_EPOCH=<unix seconds>
set -euo pipefail

# ── Tunables ──────────────────────────────────────────────────────────────────
# Maximum sandbox lifetime in hours before it is reaped.
SANDBOX_TTL_HOURS="${SANDBOX_TTL_HOURS:-24}"

# Directory containing *.meta marker files (must match sandbox_up.sh).
SANDBOX_STATE_DIR="${SANDBOX_STATE_DIR:-/var/lib/roadtrip-sandboxes}"

# ── Derived values ────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SANDBOX_TTL_SECONDS=$(( SANDBOX_TTL_HOURS * 3600 ))
NOW="$(date +%s)"

echo "==> reap run: TTL=${SANDBOX_TTL_HOURS}h  state_dir=${SANDBOX_STATE_DIR}  now=${NOW}"

# ── Guard: no marker files → nothing to reap ─────────────────────────────────
# Use a glob-existence check (compatible with bash's default globbing) so the
# loop body never runs with a literal "*.meta" string when the directory is empty.
shopt -s nullglob
META_FILES=( "${SANDBOX_STATE_DIR}"/*.meta )
shopt -u nullglob

if [[ ${#META_FILES[@]} -eq 0 ]]; then
    echo "==> no sandboxes found — nothing to reap"
    exit 0
fi

# ── Walk each marker ──────────────────────────────────────────────────────────
REAPED=0
SKIPPED=0
ERRORS=0

for meta in "${META_FILES[@]}"; do
    # Parse the marker.  Source in a subshell so variables don't leak and a
    # malformed file can't clobber our own vars.  On any parse error, warn and
    # continue — don't let one bad marker abort the whole reap.
    NAME=""
    START_EPOCH=""

    # Read only the NAME and START_EPOCH lines; ignore PORT and anything extra.
    while IFS='=' read -r key val; do
        case "${key}" in
            NAME)        NAME="${val}"        ;;
            START_EPOCH) START_EPOCH="${val}" ;;
        esac
    done < "${meta}"

    # Validate.
    if [[ -z "${NAME}" || -z "${START_EPOCH}" ]]; then
        echo "==> WARNING: malformed marker (missing NAME or START_EPOCH): ${meta} — skipping" >&2
        ERRORS=$(( ERRORS + 1 ))
        continue
    fi

    # START_EPOCH must be a positive integer.
    if ! [[ "${START_EPOCH}" =~ ^[0-9]+$ ]]; then
        echo "==> WARNING: non-numeric START_EPOCH '${START_EPOCH}' in ${meta} — skipping" >&2
        ERRORS=$(( ERRORS + 1 ))
        continue
    fi

    AGE=$(( NOW - START_EPOCH ))

    if [[ ${AGE} -gt ${SANDBOX_TTL_SECONDS} ]]; then
        echo "==> reaping '${NAME}' (age=${AGE}s > TTL=${SANDBOX_TTL_SECONDS}s)"
        "${SCRIPT_DIR}/sandbox_down.sh" "${NAME}"
        REAPED=$(( REAPED + 1 ))
    else
        REMAINING=$(( SANDBOX_TTL_SECONDS - AGE ))
        echo "==> keeping  '${NAME}' (age=${AGE}s, ${REMAINING}s until TTL)"
        SKIPPED=$(( SKIPPED + 1 ))
    fi
done

echo "==> reap complete: reaped=${REAPED}  kept=${SKIPPED}  errors=${ERRORS}"

# Exit non-zero if any marker was malformed so cron/systemd can alert on it.
if [[ ${ERRORS} -gt 0 ]]; then
    exit 1
fi
