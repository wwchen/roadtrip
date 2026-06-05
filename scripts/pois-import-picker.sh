#!/usr/bin/env bash
# Interactive picker over the 6 known POI importer sources. Runs `gradle
# importer --args="<sources>"` against local Postgres with whatever the user
# selects. Tab to multi-select in fzf; Enter to confirm.
#
# Falls back to bash `select` (numbered prompt) when fzf isn't on PATH so a
# fresh clone without fzf still gets a working picker.

set -euo pipefail

# Keep this list in sync with backend/.../importer/Importer.kt sourceFor().
# The SOURCES array is the source of truth for the picker; the backend
# rejects unknown names, so a typo here surfaces immediately.
SOURCES=(
  "uscampgrounds"
  "alberta-provincial"
  "parks-canada"
  "state-parks"
  "national-parks"
  "osm-pf"
)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

select_with_fzf() {
  # "all" is a special row at the top — picking it (with or without other
  # rows) expands to every source. Saves the user from Tab-toggling six
  # times when they want everything.
  printf '%s\n' "all" "${SOURCES[@]}" \
    | fzf --multi \
          --height=40% \
          --reverse \
          --border \
          --prompt='POI sources to import > ' \
          --header='Tab=toggle, Enter=confirm, Esc=cancel. "all" expands to every source.'
}

select_with_bash() {
  echo "fzf not found — using numbered prompt." >&2
  echo "Pick a source (or type 'all'):" >&2
  PS3='> '
  select choice in "${SOURCES[@]}" "all"; do
    if [[ -n "${choice:-}" ]]; then
      if [[ "$choice" == "all" ]]; then
        printf '%s\n' "${SOURCES[@]}"
      else
        printf '%s\n' "$choice"
      fi
      return 0
    fi
  done
}

if command -v fzf >/dev/null 2>&1; then
  picked=$(select_with_fzf || true)
else
  picked=$(select_with_bash || true)
fi

if [[ -z "${picked:-}" ]]; then
  echo "Nothing picked — aborting." >&2
  exit 1
fi

# "all" wins: if the user picked it (alone or with others), pass "all" to the
# importer so it expands once on the backend side instead of us listing
# every source. Otherwise pass the picked names verbatim.
if echo "$picked" | grep -qx 'all'; then
  sources_arg='all'
else
  sources_arg=$(echo "$picked" | tr '\n' ' ' | sed 's/[[:space:]]*$//')
fi

echo ">>> running importer with sources: $sources_arg" >&2
cd "$ROOT/backend"
ROADTRIP_DATA_DIR="$ROOT/data" ./gradlew importer --args="$sources_arg"
