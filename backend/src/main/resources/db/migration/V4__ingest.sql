-- Ingestion controller — observability + remote trigger layer around the
-- existing Python fetchers and Kotlin importer (RFC 0004 / issue #44).
--
-- Every refresh now leaves a queryable record. A target is a unit of refresh
-- producing one coherent on-disk artifact (e.g. campgrounds = uscampgrounds +
-- bc-parks + parks-canada + recgov-enrichment merged into campgrounds.geojson,
-- then imported). Each target's run is a parent row plus one phase row per
-- step; phase_kind disambiguates 'shell' (ProcessBuilder over a Python script)
-- from 'kotlin' (Importer.run on an in-process Source).
--
-- Why a separate table from import_runs:
--   - Phase rows include shell-only fields (exit_code, stderr tail in notes)
--     and parent/child relationships that don't fit the per-Importer.run
--     audit shape. import_runs stays untouched as the Importer's own audit
--     log; ingest_runs records the wrapper.

CREATE TABLE ingest_runs (
    id              BIGSERIAL PRIMARY KEY,
    target          TEXT        NOT NULL,        -- 'campgrounds', 'planet-fitness', ...
    phase           TEXT        NOT NULL,        -- 'target' for parent rows, otherwise the phase label
    phase_kind      TEXT        NOT NULL,        -- 'target' | 'shell' | 'kotlin'
    parent_run_id   BIGINT REFERENCES ingest_runs(id) ON DELETE CASCADE,
    status          TEXT        NOT NULL,        -- 'started' | 'completed' | 'failed' | 'aborted'
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    exit_code       INT,                         -- shell phases only
    counts          JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- {seen, swept, import_run_id, ...}
    notes           TEXT,                        -- failure reason, stderr tail (last 4kb)
    triggered_by    TEXT        NOT NULL,        -- 'admin-api' | 'tilt' | 'cli' | 'cron' | 'boot-recovery'
    CONSTRAINT ingest_runs_phase_kind_check CHECK (phase_kind IN ('target', 'shell', 'kotlin')),
    CONSTRAINT ingest_runs_status_check     CHECK (status     IN ('started', 'completed', 'failed', 'aborted'))
);

-- Hot path: GET /api/admin/ingest/runs?target=X ORDER BY started_at DESC LIMIT 50.
CREATE INDEX ingest_runs_target_started_idx ON ingest_runs (target, started_at DESC);

-- Joining phase rows back to their parent for GET /runs/{id}.
CREATE INDEX ingest_runs_parent_idx ON ingest_runs (parent_run_id);

-- Boot-recovery sweep: parent rows in 'started' for too long must be
-- finalized as 'aborted' on next backend boot. Partial index keeps the
-- sweep cheap even as the table grows.
CREATE INDEX ingest_runs_started_only_idx ON ingest_runs (started_at)
    WHERE status = 'started' AND phase_kind = 'target';
