#!/usr/bin/env sh
set -eu

: "${POSTGRES_DB:=roadtrip}"
: "${POSTGRES_USER:=roadtrip}"
: "${POSTGRES_PASSWORD:=roadtrip}"
: "${GRAFANA_DB_USER:=grafana_reader}"
: "${GRAFANA_DB_PASSWORD:=roadtrip}"
: "${GRAFANA_DATA_DIR:=/var/lib/grafana}"
: "${GRAFANA_DB_REPAIR_DROP:=false}"

is_truthy() {
  case "$1" in
    1 | true | TRUE | yes | YES | on | ON) return 0 ;;
    *) return 1 ;;
  esac
}

drop_grafana_sqlite_state_if_requested() {
  if ! is_truthy "$GRAFANA_DB_REPAIR_DROP"; then
    return 0
  fi

  if [ ! -d "$GRAFANA_DATA_DIR" ]; then
    echo "Grafana data dir does not exist, skipping repair drop: $GRAFANA_DATA_DIR"
    return 0
  fi

  echo "Dropping Grafana SQLite state in $GRAFANA_DATA_DIR"
  for path in \
    "$GRAFANA_DATA_DIR/grafana.db" \
    "$GRAFANA_DATA_DIR/grafana.db-shm" \
    "$GRAFANA_DATA_DIR/grafana.db-wal" \
    "$GRAFANA_DATA_DIR/grafana-apiserver" \
    "$GRAFANA_DATA_DIR/unified-search"
  do
    if [ -e "$path" ]; then
      rm -rf "$path"
    fi
  done
}

drop_grafana_sqlite_state_if_requested

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
  inherited_role text;
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

  FOR inherited_role IN
    SELECT r.rolname
    FROM pg_auth_members m
    JOIN pg_roles r ON r.oid = m.roleid
    JOIN pg_roles u ON u.oid = m.member
    WHERE u.rolname = grafana_user
  LOOP
    EXECUTE format('REVOKE %I FROM %I', inherited_role, grafana_user);
  END LOOP;
END
$$;

REVOKE ALL PRIVILEGES ON DATABASE :"postgres_db" FROM :"grafana_user";
REVOKE ALL PRIVILEGES ON SCHEMA public FROM :"grafana_user";
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM :"grafana_user";
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM :"grafana_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM :"grafana_user";

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE :"postgres_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"postgres_db" TO PUBLIC;
GRANT USAGE ON SCHEMA public TO PUBLIC;

CREATE OR REPLACE VIEW grafana_api_cache_metadata AS
SELECT
  namespace,
  cache_key,
  created_at,
  expires_at,
  pg_column_size(payload) AS payload_bytes
FROM api_cache;

GRANT CONNECT ON DATABASE :"postgres_db" TO :"grafana_user";
GRANT USAGE ON SCHEMA public TO :"grafana_user";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"grafana_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"grafana_user";
REVOKE SELECT ON api_cache FROM :"grafana_user";
SQL
