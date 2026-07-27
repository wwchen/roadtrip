#!/usr/bin/env bash
# Bootstrap the grafana_reader role + password on first postgres init.
#
# postgres's docker-entrypoint runs everything in /docker-entrypoint-initdb.d/
# once, when the data directory is empty. Cluster-level roles survive
# `DROP SCHEMA public CASCADE` (make reset-db), so this script never needs to
# re-run — subsequent Flyway migrations own the grants against this role.
#
# Both variables come from docker-compose.yml's x-grafana-reader-credentials
# anchor, which grafana's datasource provisioning reads from too — one
# definition, so the role's password and the connection that uses it cannot
# drift apart. Requiring them here rather than defaulting again keeps that
# single definition honest: a second copy of the default would silently paper
# over a compose change and hand grafana a password postgres never set.
set -euo pipefail

: "${GRAFANA_DB_USER:?must be set by docker-compose.yml (x-grafana-reader-credentials)}"
: "${GRAFANA_DB_PASSWORD:?must be set by docker-compose.yml (x-grafana-reader-credentials)}"

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
