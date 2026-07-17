#!/usr/bin/env bash
set -euo pipefail

env_example=".env.example"
workflow=".github/workflows/deploy.yml"
begin_marker="# BEGIN ROADTRIP SECRET ENV"
end_marker="# END ROADTRIP SECRET ENV"

name=""
comment=""
after=""
github_env=""
set_secret=false

usage() {
  cat <<'EOF'
Usage:
  scripts/add-secret-env.sh NAME [options]

Adds a secret-backed runtime env var to:
  - .env.example
  - .github/workflows/deploy.yml GitHub-secrets-to-.env block

Options:
  --comment TEXT       Add a comment above the .env.example entry.
  --after NAME         Insert in .env.example after an existing var.
  --env-example PATH   Override .env.example path.
  --workflow PATH      Override workflow path.
  --github-env NAME    Print/use an environment-scoped gh secret command.
  --set-secret         Run gh secret set after updating files.
  -h, --help           Show this help.

Examples:
  scripts/add-secret-env.sh NEW_API_KEY --comment "Used by the Foo client."
  scripts/add-secret-env.sh NEW_API_KEY --after RESEND_API_KEY --github-env production
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

normalize_name() {
  local raw="$1"
  local normalized
  normalized="$(printf '%s' "$raw" | tr '[:lower:]' '[:upper:]')"
  if [[ ! "$normalized" =~ ^[A-Z_][A-Z0-9_]*$ ]]; then
    die "'$raw' is not a valid secret/env name; use uppercase letters, digits, and underscores, and do not start with a digit"
  fi
  if [[ "$normalized" == GITHUB_* ]]; then
    die "GitHub Actions secret names must not start with GITHUB_"
  fi
  printf '%s\n' "$normalized"
}

while (($#)); do
  case "$1" in
    --comment)
      (($# >= 2)) || die "--comment requires a value"
      comment="$2"
      shift 2
      ;;
    --after)
      (($# >= 2)) || die "--after requires a value"
      after="$2"
      shift 2
      ;;
    --env-example)
      (($# >= 2)) || die "--env-example requires a value"
      env_example="$2"
      shift 2
      ;;
    --workflow)
      (($# >= 2)) || die "--workflow requires a value"
      workflow="$2"
      shift 2
      ;;
    --github-env)
      (($# >= 2)) || die "--github-env requires a value"
      github_env="$2"
      shift 2
      ;;
    --set-secret)
      set_secret=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      if [[ -n "$name" ]]; then
        die "unexpected extra argument: $1"
      fi
      name="$1"
      shift
      ;;
  esac
done

if (($#)); then
  die "unexpected extra argument: $1"
fi

[[ -n "$name" ]] || die "NAME is required"
[[ -f "$env_example" ]] || die "$env_example does not exist"
[[ -f "$workflow" ]] || die "$workflow does not exist"

name="$(normalize_name "$name")"
if [[ -n "$after" ]]; then
  after="$(normalize_name "$after")"
fi

env_updated=false
workflow_updated=false

if ! grep -Eq "^[[:space:]]*${name}=" "$env_example"; then
  tmp_env="${env_example}.tmp.$$"
  trap 'rm -f "$tmp_env" "${workflow}.tmp.$$"' EXIT

  if [[ -n "$after" ]]; then
    grep -Eq "^[[:space:]]*${after}=" "$env_example" || die "$after was not found in $env_example"
    awk -v after="$after" -v name="$name" -v comment="$comment" '
      {
        print
        if ($0 ~ "^[[:space:]]*" after "=") {
          if (comment != "") {
            print "# " comment
          }
          print name "="
        }
      }
    ' "$env_example" > "$tmp_env"
  else
    cp "$env_example" "$tmp_env"
    if [[ -s "$tmp_env" ]] && [[ -n "$(tail -n 1 "$tmp_env")" ]]; then
      printf '\n' >> "$tmp_env"
    fi
    if [[ -n "$comment" ]]; then
      printf '# %s\n' "$comment" >> "$tmp_env"
    fi
    printf '%s=\n' "$name" >> "$tmp_env"
  fi

  mv "$tmp_env" "$env_example"
  chmod 644 "$env_example"
  env_updated=true
fi

if ! grep -Fq "${name}=\${{ secrets.${name} }}" "$workflow"; then
  begin_line="$(awk -v marker="$begin_marker" 'index($0, marker) { print NR; exit }' "$workflow")"
  end_line="$(awk -v marker="$end_marker" 'index($0, marker) { print NR; exit }' "$workflow")"
  [[ -n "$begin_line" && -n "$end_line" && "$begin_line" -lt "$end_line" ]] || \
    die "could not find ordered secret-env markers in $workflow"

  end_text="$(awk -v marker="$end_marker" 'index($0, marker) { print; exit }' "$workflow")"
  indent="${end_text%%#*}"
  secret_line="${indent}${name}=\${{ secrets.${name} }}"
  tmp_workflow="${workflow}.tmp.$$"
  trap 'rm -f "${env_example}.tmp.$$" "$tmp_workflow"' EXIT

  awk -v end_marker="$end_marker" -v secret_line="$secret_line" '
    index($0, end_marker) {
      print secret_line
    }
    {
      print
    }
  ' "$workflow" > "$tmp_workflow"

  mv "$tmp_workflow" "$workflow"
  chmod 644 "$workflow"
  workflow_updated=true
fi

env_flag=()
if [[ -n "$github_env" ]]; then
  env_flag=(--env "$github_env")
fi

if [[ "$set_secret" == true ]]; then
  gh_args=(secret set "$name" "${env_flag[@]}")
  if [[ ${!name+x} == x ]]; then
    gh_args+=(--body "${!name}")
  fi
  gh "${gh_args[@]}"
fi

env_status="already had"
workflow_status="already had"
[[ "$env_updated" == true ]] && env_status="added"
[[ "$workflow_updated" == true ]] && workflow_status="added"

printf '%s: %s %s\n' "$env_example" "$env_status" "$name"
printf '%s: %s %s\n' "$workflow" "$workflow_status" "$name"

if [[ "$set_secret" != true ]]; then
  printf 'Set the GitHub secret with either command:\n'
  printf '  gh secret set %q' "$name"
  if [[ -n "$github_env" ]]; then
    printf ' --env %q' "$github_env"
  fi
  printf '\n'
  printf '  gh secret set %q' "$name"
  if [[ -n "$github_env" ]]; then
    printf ' --env %q' "$github_env"
  fi
  printf ' --body "$%s"  # when $%s is already set in your shell\n' "$name" "$name"
fi
