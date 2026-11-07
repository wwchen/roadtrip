#!/usr/bin/env bash
set -euo pipefail

deploy_host="${DEPLOY_SSH_HOST:-mini@mini-ca}"
origin_port="${ORIGIN_PORT:-80}"
curl_args="$(printf '%q ' "$@")"

exec ssh -o BatchMode=yes "$deploy_host" \
    "cd \"\$HOME/.roadtrip/current\" && docker compose --profile tunnel --profile pois exec -T caddy curl --connect-to ::127.0.0.1:${origin_port} ${curl_args}"
