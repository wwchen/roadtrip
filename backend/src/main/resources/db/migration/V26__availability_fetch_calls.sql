-- availability_fetch_call — one row per upstream availability fetch a poll run
-- issued, at the (provider, campground/map) group granularity produced by
-- CatalogAvailabilityBatcher. This is the trace layer between a run
-- ("failed: rate_limited") and the raw upstream calls: it shows how one watch
-- became N API calls and how each fared. Written only when a real upstream
-- call was made (skipped/no-future-date groups produce no row).
--
-- Retention: indefinite, same as availability_job_run. Hot query is
-- "all fetch calls for this run_id" (dashboard drill) and
-- "rate_limited count by provider,parent_ref over last 1h" (monitor).

CREATE TABLE availability_fetch_call (
  id                BIGSERIAL   PRIMARY KEY,
  run_id            BIGINT      NOT NULL REFERENCES availability_job_run(id) ON DELETE CASCADE,
  provider          TEXT        NOT NULL,
  parent_ref        TEXT        NOT NULL,
  reservable_count  INT         NOT NULL DEFAULT 0 CHECK (reservable_count >= 0),
  window_start      DATE        NOT NULL,
  window_end        DATE        NOT NULL,
  outcome           TEXT        NOT NULL
                                  CHECK (outcome IN ('ok','rate_limited','upstream_5xx','blocked','other')),
  duration_ms       INT         CHECK (duration_ms IS NULL OR duration_ms >= 0),
  error             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX availability_fetch_call_run_idx
  ON availability_fetch_call (run_id);

-- Monitor path: rate-limited calls by provider/target over a recent window.
CREATE INDEX availability_fetch_call_outcome_created_idx
  ON availability_fetch_call (outcome, created_at DESC);
