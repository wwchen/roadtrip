#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <before-sha> <after-sha>" >&2
  exit 2
fi

before_sha="$1"
after_sha="$2"
backend_image="${BACKEND_IMAGE:-roadtrip/backend}"
compose=(docker compose --profile tunnel --profile pois)

./gradlew :backend:shadowJar
docker build -t "$backend_image" --target backend .
"${compose[@]}" up -d

if git diff --name-only "$before_sha" "$after_sha" -- grafana/dashboards grafana/provisioning | grep -q .; then
  echo "Grafana dashboard/provisioning files changed; restarting grafana."
  "${compose[@]}" restart grafana
else
  echo "No Grafana dashboard/provisioning file changes detected; leaving grafana running."
fi
