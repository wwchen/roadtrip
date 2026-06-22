#!/usr/bin/env sh
set -eu

: "${POSTGRES_DB:=roadtrip}"
: "${POSTGRES_USER:=roadtrip}"
: "${POSTGRES_PASSWORD:=roadtrip}"
: "${GRAFANA_DB_USER:=grafana_reader}"
: "${GRAFANA_DB_PASSWORD:=roadtrip}"

export PGPASSWORD="$POSTGRES_PASSWORD"

psql \
  -h postgres \
  -U "$POSTGRES_USER" \
  -d "$POSTGRES_DB" \
  -v ON_ERROR_STOP=1 \
  -v grafana_user="$GRAFANA_DB_USER" \
  -v grafana_password="$GRAFANA_DB_PASSWORD" \
  -v postgres_db="$POSTGRES_DB" <<'SQL'
SELECT set_config('roadtrip.grafana_user', :'grafana_user', false);
SELECT set_config('roadtrip.grafana_password', :'grafana_password', false);

DO $$
DECLARE
  grafana_user text := current_setting('roadtrip.grafana_user');
  grafana_password text := current_setting('roadtrip.grafana_password');
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = grafana_user) THEN
    EXECUTE format(
      'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
      grafana_user,
      grafana_password
    );
  ELSE
    EXECUTE format(
      'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
      grafana_user,
      grafana_password
    );
  END IF;
END
$$;

GRANT CONNECT ON DATABASE :"postgres_db" TO :"grafana_user";
GRANT USAGE ON SCHEMA public TO :"grafana_user";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"grafana_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"grafana_user";
SQL
