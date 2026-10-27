#!/usr/bin/env bash
set -euo pipefail

COMPANION_IMAGE_INPUTS=(companion package.json package-lock.json)

git ls-tree HEAD -- "${COMPANION_IMAGE_INPUTS[@]}" | git hash-object --stdin
