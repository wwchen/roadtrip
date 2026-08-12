#!/usr/bin/env python3
"""Reject edits to versioned migrations that master already carries.

Flyway checksums a migration's whole file, comments included, so rewriting one
that a database has already run makes that database refuse to validate on the
next boot. The remedy for a schema change is always a new migration.
"""

from __future__ import annotations

import argparse
import subprocess
import sys

MIGRATION_DIR = "backend/src/main/resources/db/migration"

# Repeatable migrations re-run whenever their checksum changes, which is the
# point of them; only versioned migrations are frozen once applied.
VERSIONED_PREFIX = "V"

MIGRATION_SUFFIX = ".sql"

# git --name-status codes that mean the file existed before this branch.
EDIT_STATUSES = {
    "M": "modified",
    "D": "deleted",
    "R": "renamed",
    "C": "copied",
    "T": "type-changed",
}

REMEDY = """
A versioned migration is frozen the moment any database runs it: Flyway
checksums the file's bytes, comments included, so an edit strands every
database that recorded the old checksum and the backend stops booting.

Change schema by adding a new V<next>__*.sql. If a database is already
stranded, repair its flyway_schema_history checksum rather than editing the
migration back — editing it back only strands the databases that agreed.
""".strip()


def is_versioned_migration(path: str) -> bool:
    """True for db/migration/V*.sql, which Flyway records by checksum."""
    if not path.startswith(f"{MIGRATION_DIR}/"):
        return False
    name = path.rsplit("/", 1)[-1]
    return name.startswith(VERSIONED_PREFIX) and name.endswith(MIGRATION_SUFFIX)


def violations(name_status_output: str) -> list[tuple[str, str]]:
    """Pick pre-existing versioned migrations out of `git diff --name-status`."""
    found = []
    for line in name_status_output.splitlines():
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        # Rename and copy codes carry a similarity score, e.g. "R100".
        code = fields[0][:1]
        if code not in EDIT_STATUSES:
            continue
        # A rename lists the old path first; that is the path whose recorded
        # checksum no longer resolves.
        path = fields[1]
        if is_versioned_migration(path):
            found.append((EDIT_STATUSES[code], path))
    return found


def changed_migrations(base_ref: str) -> str:
    result = subprocess.run(
        ["git", "diff", "--name-status", f"{base_ref}...HEAD", "--", MIGRATION_DIR],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "base_ref",
        help="commit this branch is measured against, e.g. the PR's base SHA",
    )
    args = parser.parse_args(argv)

    found = violations(changed_migrations(args.base_ref))
    if not found:
        print(f"migrations: no versioned migration was edited since {args.base_ref}")
        return 0

    for verb, path in found:
        print(f"error: {path} was {verb}; an applied migration is immutable")
    print(REMEDY)
    return 1


if __name__ == "__main__":
    sys.exit(main())
