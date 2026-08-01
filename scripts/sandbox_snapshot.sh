#!/usr/bin/env bash
# sandbox_snapshot.sh
#
# Take a pg_dump -Fc (custom-format) snapshot of the main seeded-catalog
# Postgres instance and write it to SANDBOX_SNAPSHOT_PATH.
#
# Intended to run on the deploy host on a schedule.  Example cron entry
# (nightly at 02:00, logged to syslog):
#
#   0 2 * * * root /path/to/scripts/sandbox_snapshot.sh >> /var/log/sandbox-snapshot.log 2>&1
#
# Runtime assumptions (documented):
#   - The main Compose stack is running under project name "roadtrip"
#     (the `name: roadtrip` pin in docker-compose.yml) with the postgres
#     service healthy and accepting connections.
#   - docker compose (v2 plugin) is on PATH.
#   - The snapshot directory is writable by the user running this script.
#   - A half-written .tmp file is cleaned on failure so a mid-dump restart
#     never leaves a corrupt archive that sandbox_up.sh would restore.
set -euo pipefail

# ── Tunables ──────────────────────────────────────────────────────────────────
# Destination path for the finished archive (pg_dump -Fc custom format).
SANDBOX_SNAPSHOT_PATH="${SANDBOX_SNAPSHOT_PATH:-/var/lib/roadtrip-sandboxes/snapshot.dump}"

# Main stack's Compose project name (matches the `name:` field in docker-compose.yml).
SNAPSHOT_SOURCE_PROJECT="${SNAPSHOT_SOURCE_PROJECT:-roadtrip}"

# Path to docker-compose.yml — used to address the right Compose project.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SNAPSHOT_COMPOSE_FILE="${SNAPSHOT_COMPOSE_FILE:-${REPO_ROOT}/docker-compose.yml}"

# Postgres connection settings inside the main stack (must match docker-compose.yml).
SNAPSHOT_POSTGRES_USER="${SNAPSHOT_POSTGRES_USER:-roadtrip}"
SNAPSHOT_POSTGRES_DB="${SNAPSHOT_POSTGRES_DB:-roadtrip}"

# ── Derived paths ─────────────────────────────────────────────────────────────
SNAPSHOT_TMP="${SANDBOX_SNAPSHOT_PATH}.tmp"

# ── Ensure the destination directory exists ───────────────────────────────────
mkdir -p "$(dirname "${SANDBOX_SNAPSHOT_PATH}")"

# ── Remove stale .tmp on unexpected exit (failure or signal) ──────────────────
_cleanup() {
    if [[ -f "${SNAPSHOT_TMP}" ]]; then
        echo "==> snapshot_snapshot: cleaning up incomplete tmp file: ${SNAPSHOT_TMP}" >&2
        rm -f "${SNAPSHOT_TMP}"
    fi
}
trap _cleanup EXIT

# ── Build --exclude-table-data flags for private (PII/session) tables ─────────
# sandbox-private-tables.txt lists tables whose ROWS must never be cloned into
# a sandbox (user identity, sessions, roles, settings).  The DDL is kept so the
# restored flyway_schema_history stays consistent and Flyway is a no-op on boot.
PRIVATE_TABLES_FILE="${SCRIPT_DIR}/sandbox-private-tables.txt"
EXCLUDE_TABLE_DATA_FLAGS=()
if [[ -f "${PRIVATE_TABLES_FILE}" ]]; then
    while IFS= read -r line; do
        # Skip blank lines and comment lines.
        [[ -z "${line}" || "${line}" == \#* ]] && continue
        EXCLUDE_TABLE_DATA_FLAGS+=("--exclude-table-data=${line}")
    done < "${PRIVATE_TABLES_FILE}"
else
    echo "warning: ${PRIVATE_TABLES_FILE} not found; snapshot will include all table data" >&2
fi

# ── Dump ──────────────────────────────────────────────────────────────────────
echo "==> dumping ${SNAPSHOT_POSTGRES_DB} (project: ${SNAPSHOT_SOURCE_PROJECT}) → ${SNAPSHOT_TMP}"
if [[ ${#EXCLUDE_TABLE_DATA_FLAGS[@]} -gt 0 ]]; then
    echo "==> excluding table data for: ${EXCLUDE_TABLE_DATA_FLAGS[*]}"
fi

# Mirror the exec style used by sandbox_up.sh's pg_restore invocation:
#   docker compose -p <project> -f <file> exec -T <service> <cmd>
# -Fc produces a custom-format archive (the format sandbox_up.sh pg_restores).
# --exclude-table-data=<table> omits ROWS but keeps the CREATE TABLE DDL so the
# restored flyway_schema_history stays consistent (Flyway won't re-run those
# migrations, and queries against those tables won't hit "relation does not exist").
# stdout is redirected to the .tmp file; errors go to stderr.
docker compose \
    -p "${SNAPSHOT_SOURCE_PROJECT}" \
    -f "${SNAPSHOT_COMPOSE_FILE}" \
    exec -T postgres \
    pg_dump \
        -Fc \
        -U "${SNAPSHOT_POSTGRES_USER}" \
        "${EXCLUDE_TABLE_DATA_FLAGS[@]}" \
        "${SNAPSHOT_POSTGRES_DB}" \
    > "${SNAPSHOT_TMP}"

# ── Atomic rename ─────────────────────────────────────────────────────────────
# Only promote to the final path after a successful dump.  A sandbox doing
# `pg_restore < SANDBOX_SNAPSHOT_PATH` mid-run sees either the previous
# complete archive or the new one — never a partial write.
mv "${SNAPSHOT_TMP}" "${SANDBOX_SNAPSHOT_PATH}"

# Disarm the trap: the .tmp is gone (renamed), nothing to clean up.
trap - EXIT

echo "==> snapshot written: ${SANDBOX_SNAPSHOT_PATH} ($(du -sh "${SANDBOX_SNAPSHOT_PATH}" | cut -f1))"
