#!/usr/bin/env bash
# sandbox_up.sh <ref> [name]
#
# Thin wrapper around deploy.sh for the sandbox environment.
# All logic lives in deploy.sh; this script preserves the original
# public contract so callers (make sandbox, CI, humans) are unaffected.
#
# <ref>   A PR number (numeric) or branch name.  If numeric, the sandbox name
#         is "pr<N>".  Otherwise the branch is slugified (lowercase,
#         non-alphanumeric runs → single dash, leading/trailing dashes trimmed).
# [name]  Optional override for the sandbox name; skips auto-derivation.
#
# Exits non-zero on any failure.  Safe to re-run: the Compose project is
# idempotent; Caddy and state files are overwritten on re-up.
#
# Runtime assumptions: see deploy.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -lt 1 ]]; then
    echo "usage: sandbox_up.sh <ref> [name]" >&2
    exit 1
fi

exec "${SCRIPT_DIR}/deploy.sh" sandbox "$@"
