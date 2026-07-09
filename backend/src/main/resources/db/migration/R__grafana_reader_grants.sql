-- Repeatable migration: grafana_reader role + read-only grants for Grafana.
--
-- Runs on every Flyway migrate. Repairs any drift automatically — after
-- `make reset-db` (which wipes public), the next backend boot re-applies
-- these grants so Grafana keeps working with no separate sidecar step.
--
-- Placeholders come from Db.migrate() (GRAFANA_DB_USER/GRAFANA_DB_PASSWORD env
-- with 'grafana_reader'/'roadtrip' fallbacks). Substituted as raw text — do
-- not put a single quote in the password.

DO $$
DECLARE
  grafana_user text := '${grafana_user}';
  grafana_password text := '${grafana_password}';
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

  EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I', current_database(), grafana_user);
  EXECUTE format('REVOKE ALL PRIVILEGES ON SCHEMA public FROM %I', grafana_user);
  EXECUTE format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM %I', grafana_user);
  EXECUTE format('REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM %I', grafana_user);
  EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', grafana_user);
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
BEGIN
  EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO PUBLIC', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO PUBLIC;

-- api_cache holds raw vendor response payloads (potentially sensitive routing
-- data). Expose only the metadata to Grafana via this view.
CREATE OR REPLACE VIEW grafana_api_cache_metadata AS
SELECT
  namespace,
  cache_key,
  created_at,
  expires_at,
  pg_column_size(payload) AS payload_bytes
FROM api_cache;

DO $$
DECLARE
  grafana_user text := '${grafana_user}';
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), grafana_user);
  EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', grafana_user);
  EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I', grafana_user);
  EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO %I', grafana_user);
  EXECUTE format('REVOKE SELECT ON api_cache FROM %I', grafana_user);
END
$$;
