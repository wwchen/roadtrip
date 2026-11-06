#!/usr/bin/env bash
set -euo pipefail

COMPANION_IMAGE_INPUTS=(companion package.json package-lock.json)

entries="$(git ls-tree --full-tree HEAD -- "${COMPANION_IMAGE_INPUTS[@]}")"
found="$(printf '%s\n' "${entries}" | grep -c . || true)"

if [ "${found}" -ne "${#COMPANION_IMAGE_INPUTS[@]}" ]; then
    echo "companion-image-key: expected ${#COMPANION_IMAGE_INPUTS[@]} tree entries for" \
        "${COMPANION_IMAGE_INPUTS[*]}, found ${found}" >&2
    exit 1
fi

printf '%s\n' "${entries}" | git hash-object --stdin
