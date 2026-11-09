#!/usr/bin/env bash
set -euo pipefail
WEB_DB_USER="${WEB_WRITER_DB_USER:-roadtrip_web_writer}" \
WEB_DB_PASSWORD_FILE="${WEB_WRITER_DB_PASSWORD_FILE:-}" \
  exec bash /docker-entrypoint-initdb.d/20-web-reader.sh
