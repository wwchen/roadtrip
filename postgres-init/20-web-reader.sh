#!/usr/bin/env bash
set -euo pipefail

: "${WEB_DB_USER:=roadtrip_web}"

if [[ -z "${WEB_DB_PASSWORD_FILE:-}" || ! -s "${WEB_DB_PASSWORD_FILE}" ]]; then
  echo "web-reader: no password mounted; skipping"
  exit 0
fi

WEB_DB_PASSWORD="$(cat "$WEB_DB_PASSWORD_FILE")"
psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
     --set=ON_ERROR_STOP=1 \
     --variable=web_user="$WEB_DB_USER" \
     --variable=web_password="$WEB_DB_PASSWORD" <<'SQL'
SELECT set_config('roadtrip.web_user', :'web_user', false);
SELECT set_config('roadtrip.web_password', :'web_password', false);
DO $$
DECLARE
  u text := current_setting('roadtrip.web_user');
  p text := current_setting('roadtrip.web_password');
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = u) THEN
    EXECUTE format(
      'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
      u, p
    );
  ELSE
    EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', u, p);
  END IF;
END
$$;
SQL
