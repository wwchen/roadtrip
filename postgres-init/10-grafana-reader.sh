#!/usr/bin/env bash
# Bootstrap the grafana_reader role + password on first postgres init.
#
# postgres's docker-entrypoint runs everything in /docker-entrypoint-initdb.d/
# once, when the data directory is empty. Cluster-level roles survive
# `DROP SCHEMA public CASCADE` (make reset-db), so this script never needs to
# re-run — subsequent Flyway migrations own the grants against this role.
#
# The password comes from the mounted secret named in secrets/registry.yaml,
# the same one grafana's datasource reads with $__file{} — one definition, so
# the role and the connection that uses it cannot drift apart. The image's own
# file_env() helper only covers POSTGRES_*, so read the file here.
#
# Required rather than defaulted: a fallback would silently create the role
# with a password grafana never uses, and the failure would surface much later
# as an opaque datasource auth error.
set -euo pipefail

: "${GRAFANA_DB_USER:=grafana_reader}"
: "${GRAFANA_DB_PASSWORD_FILE:?must be set by docker-compose.yml}"
[ -r "$GRAFANA_DB_PASSWORD_FILE" ] || {
  echo "grafana-reader: cannot read $GRAFANA_DB_PASSWORD_FILE" >&2
  exit 1
}
GRAFANA_DB_PASSWORD="$(cat "$GRAFANA_DB_PASSWORD_FILE")"

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
