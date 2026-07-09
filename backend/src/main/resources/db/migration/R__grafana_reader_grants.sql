-- Repeatable migration: read-only grants for the grafana_reader role.
--
-- The role itself (and its password) is bootstrapped by postgres-init/, which
-- is infra-layer. This migration only owns schema-adjacent concerns: what the
-- role can SELECT, and the grafana_api_cache_metadata view.
--
-- Idempotent, and runs on every Flyway migrate — so drift from `make reset-db`
-- (which DROPs and recreates public but leaves cluster-level roles intact)
-- auto-repairs on the next backend boot. No sidecar container required.

-- Safety net for environments where postgres-init hasn't run (e.g. the
-- generateJooq testcontainer). No password is set here on purpose; passwords
-- are infra-layer state that the schema migration must not encode.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana_reader') THEN
    CREATE ROLE grafana_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
END
$$;

REVOKE ALL PRIVILEGES ON SCHEMA public FROM grafana_reader;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM grafana_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM grafana_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM grafana_reader;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
BEGIN
  EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM grafana_reader', current_database());
  EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_reader', current_database());
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

GRANT USAGE ON SCHEMA public TO grafana_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_reader;
REVOKE SELECT ON api_cache FROM grafana_reader;
