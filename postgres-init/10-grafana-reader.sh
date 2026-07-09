#!/usr/bin/env bash
# Bootstrap the grafana_reader role + password on first postgres init.
#
# postgres's docker-entrypoint runs everything in /docker-entrypoint-initdb.d/
# once, when the data directory is empty. Cluster-level roles survive
# `DROP SCHEMA public CASCADE` (make reset-db), so this script never needs to
# re-run — subsequent Flyway migrations own the grants against this role.
#
# If GRAFANA_DB_PASSWORD is unset, use a stable dev default so grafana's
# datasource provisioning (which reads the same env var with the same default)
# lines up. Prod overrides via .env like every other secret.
set -euo pipefail

: "${GRAFANA_DB_USER:=grafana_reader}"
: "${GRAFANA_DB_PASSWORD:=roadtrip}"

psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
     --set=ON_ERROR_STOP=1 \
     --variable=grafana_user="$GRAFANA_DB_USER" \
     --variable=grafana_password="$GRAFANA_DB_PASSWORD" <<'SQL'
SELECT set_config('roadtrip.grafana_user', :'grafana_user', false);
SELECT set_config('roadtrip.grafana_password', :'grafana_password', false);

DO $$
DECLARE
  u text := current_setting('roadtrip.grafana_user');
  p text := current_setting('roadtrip.grafana_password');
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
