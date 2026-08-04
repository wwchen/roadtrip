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

# ── Compute FK-dependent closure of PII root tables ───────────────────────────
# sandbox-private-tables.txt lists only the PII/identity/session ROOT tables.
# We query the live DB to compute every table that has a foreign-key path
# leading back to any root, then exclude the entire closure from the dump.
# This means a future user-owned table is auto-excluded without hand-editing.
#
# The watch subtree is NOT in the roots file — its PII is scrubbed in-place
# at restore time (scripts/sandbox_scrub.sql) rather than excluded.
PRIVATE_TABLES_FILE="${SCRIPT_DIR}/sandbox-private-tables.txt"

if [[ ! -f "${PRIVATE_TABLES_FILE}" ]]; then
    echo "error: ${PRIVATE_TABLES_FILE} not found; cannot build exclusion list" >&2
    exit 1
fi

# Read root table names from the file (skip blanks and comments).
ROOT_TABLES=()
while IFS= read -r line; do
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    ROOT_TABLES+=("${line}")
done < "${PRIVATE_TABLES_FILE}"

if [[ ${#ROOT_TABLES[@]} -eq 0 ]]; then
    echo "error: ${PRIVATE_TABLES_FILE} contains no table names" >&2
    exit 1
fi

echo "==> PII roots (from file): ${ROOT_TABLES[*]}"

# Build the VALUES list for the recursive CTE: ('app_user'),('user_identity'),...
VALUES_LIST=""
for tbl in "${ROOT_TABLES[@]}"; do
    if [[ -n "${VALUES_LIST}" ]]; then
        VALUES_LIST="${VALUES_LIST},"
    fi
    VALUES_LIST="${VALUES_LIST}('${tbl}')"
done

# Recursive CTE: starting from the roots, walk forward through FK constraints
# to find every table that has a FK path leading back to a root.  This
# computes the transitive closure of "tables whose data would be orphaned if
# root data is excluded".
CLOSURE_SQL="WITH RECURSIVE roots(tbl) AS (
    VALUES ${VALUES_LIST}
),
closure(tbl) AS (
    -- Cast the anchor column to name so its collation ('C') matches the
    -- recursive term's c.relname (also type name/'C').  Without the cast the
    -- roots VALUES literals carry the database default collation and Postgres
    -- rejects the UNION with a collation-mismatch error.
    SELECT tbl::name FROM roots
    UNION
    SELECT c.relname
    FROM closure cl
    JOIN pg_constraint con ON con.contype = 'f'
    JOIN pg_class pf ON pf.oid = con.confrelid AND pf.relname = cl.tbl
    JOIN pg_class c  ON c.oid  = con.conrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
)
SELECT DISTINCT tbl FROM closure ORDER BY tbl;"

echo "==> computing FK-closure via catalog query..."
# Capture stdout only — do NOT merge stderr (2>&1) into the value.
# psql warnings (locale notes, "could not change directory", NOTICEs) go to
# the terminal/log via their own stderr path; folding them in would produce
# bogus table names that could carry glob chars and silently over-exclude
# real catalog tables from the dump.
# The || block still catches a non-zero exit so failures are loud.
CLOSURE_OUTPUT="$(docker compose \
    -p "${SNAPSHOT_SOURCE_PROJECT}" \
    -f "${SNAPSHOT_COMPOSE_FILE}" \
    exec -T postgres \
    psql \
        -U "${SNAPSHOT_POSTGRES_USER}" \
        -d "${SNAPSHOT_POSTGRES_DB}" \
        -tA \
        -c "${CLOSURE_SQL}")" || {
    echo "error: failed to query FK closure from DB — is the stack running?" >&2
    exit 1
}

if [[ -z "${CLOSURE_OUTPUT}" ]]; then
    echo "error: FK closure query returned no rows; expected at least the root tables" >&2
    exit 1
fi

# Parse the closure output into an array of table names.
# Each line must be a legal unquoted Postgres identifier ([a-zA-Z_][a-zA-Z0-9_]*).
# An unexpected line (e.g. a stray warning that made it to stdout despite -tA)
# means the query output is not what we expect — abort rather than silently
# passing a malformed name to --exclude-table-data, which could match nothing
# OR, if the line contains glob chars (* ? [), silently over-exclude real tables.
CLOSURE_TABLES=()
while IFS= read -r tbl; do
    [[ -z "${tbl}" ]] && continue
    if [[ ! "${tbl}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
        echo "error: FK closure query returned a non-identifier line: '${tbl}'" >&2
        echo "       Expected only unquoted Postgres table names.  Aborting to prevent" >&2
        echo "       a malformed --exclude-table-data flag in the pg_dump invocation." >&2
        exit 1
    fi
    CLOSURE_TABLES+=("${tbl}")
done <<< "${CLOSURE_OUTPUT}"

echo "==> FK closure (roots + descendants): ${CLOSURE_TABLES[*]}"

# ── Catalog safety assertion ──────────────────────────────────────────────────
# Fail loud if the computed closure intersects the core catalog tables.
# A new FK from a catalog table to an identity root would silently gut the
# sandbox map — this check prevents that.
CATALOG_DENYLIST=(pois campgrounds campsites reservables availability)
OFFENDING_TABLES=()
for tbl in "${CLOSURE_TABLES[@]}"; do
    for denied in "${CATALOG_DENYLIST[@]}"; do
        if [[ "${tbl}" == "${denied}" ]]; then
            OFFENDING_TABLES+=("${tbl}")
        fi
    done
done

if [[ ${#OFFENDING_TABLES[@]} -gt 0 ]]; then
    echo "error: FK closure intersects core catalog tables: ${OFFENDING_TABLES[*]}" >&2
    echo "       A new FK has coupled the catalog to an identity/session root." >&2
    echo "       A human must decide whether to exclude this table or restructure the FK." >&2
    echo "       Aborting snapshot to prevent a gutted sandbox." >&2
    exit 1
fi

# ── Build --exclude-table-data flags ─────────────────────────────────────────
EXCLUDE_TABLE_DATA_FLAGS=()
for tbl in "${CLOSURE_TABLES[@]}"; do
    EXCLUDE_TABLE_DATA_FLAGS+=("--exclude-table-data=${tbl}")
done

# ── Dump ──────────────────────────────────────────────────────────────────────
echo "==> dumping ${SNAPSHOT_POSTGRES_DB} (project: ${SNAPSHOT_SOURCE_PROJECT}) → ${SNAPSHOT_TMP}"
echo "==> excluding table data for: ${EXCLUDE_TABLE_DATA_FLAGS[*]}"

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
