-- Restore the pre-V29 invariant: a watch with no scope should not exist.
-- V29 moved scope onto availability_watch_target; when a target's POI/reservable is
-- deleted the target row cascades away, and if it was the watch's last target we
-- delete the watch too (which cascades to availability_watch_poller via the V27 FK,
-- so poller links are cleaned and the executor reaps the empty poller). A multi-target
-- watch that loses ONE target survives.
--
-- DEFERRABLE INITIALLY DEFERRED: AvailabilityWatchTargetRepo.replaceForWatch deletes a
-- watch's targets and re-inserts the new set as separate statements; an immediate
-- trigger would see zero targets mid-replace and prune the watch before the re-insert.
-- Deferring to COMMIT means it only sees the final target set. This REQUIRES
-- replaceForWatch's delete+insert to run in one transaction (done below).
CREATE OR REPLACE FUNCTION availability_watch_prune_when_no_targets()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM availability_watch_target WHERE watch_id = OLD.watch_id
    ) THEN
        DELETE FROM availability_watch WHERE id = OLD.watch_id;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
--> statement-breakpoint
CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty
    AFTER DELETE ON availability_watch_target
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION availability_watch_prune_when_no_targets();
--> statement-breakpoint
CREATE FUNCTION campground_site_summary(cg_id BIGINT)
RETURNS TABLE (site_total INT, site_counts JSONB, max_people INT)
LANGUAGE sql STABLE AS $$
  SELECT SUM(kind_count)::int, jsonb_object_agg(kind, kind_count), MAX(max_people)::int
  FROM (
    SELECT kind, COUNT(*)::int AS kind_count, MAX(max_people) AS max_people
    FROM campsites c
    WHERE c.campground_id = cg_id AND c.deleted_at IS NULL
    GROUP BY kind
  ) per_kind
$$;
--> statement-breakpoint
-- One-shot migration: read-only grants for the grafana_reader role.
--
-- The role itself (and its password) is bootstrapped by postgres-init/, which
-- is infra-layer. This migration only owns schema-adjacent concerns: what the
-- role can SELECT, and the grafana_api_cache_metadata view.
--
-- Idempotent. Under Flyway this ran on every migrate, so drift from `make
-- reset-db` (which DROPs and recreates public but leaves cluster-level roles
-- intact) auto-repaired on the next backend boot. As a Drizzle migration it runs
-- exactly once: a later migration that recreates or renames api_cache gets
-- grafana_reader's SELECT back from the default privileges below, and must
-- repeat the REVOKE itself. No sidecar container required.

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
--> statement-breakpoint
-- Grafana's DB Stats dashboard needs query text, state, waits, and blockers
-- for backend sessions. PostgreSQL masks other users' query text without this
-- predefined role. Keep the privilege narrow: stats visibility only, still no
-- writes and no raw api_cache payload access.
GRANT pg_read_all_stats TO grafana_reader;
--> statement-breakpoint
REVOKE ALL PRIVILEGES ON SCHEMA public FROM grafana_reader;
--> statement-breakpoint
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM grafana_reader;
--> statement-breakpoint
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM grafana_reader;
--> statement-breakpoint
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM grafana_reader;
--> statement-breakpoint
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
--> statement-breakpoint
DO $$
BEGIN
  EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM grafana_reader', current_database());
  EXECUTE format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_reader', current_database());
END
$$;
--> statement-breakpoint
GRANT USAGE ON SCHEMA public TO PUBLIC;
--> statement-breakpoint
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
--> statement-breakpoint
GRANT USAGE ON SCHEMA public TO grafana_reader;
--> statement-breakpoint
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_reader;
--> statement-breakpoint
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_reader;
--> statement-breakpoint
REVOKE SELECT ON api_cache FROM grafana_reader;
--> statement-breakpoint
ALTER SEQUENCE public.availability_job_run_id_seq OWNED BY public.availability_run.id;
