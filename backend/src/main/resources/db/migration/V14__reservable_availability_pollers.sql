-- Rename monitor registrations to pollers and add intent/run provenance.
-- Pollers store user intent. Each execution creates a run, and each run
-- appends lowest-unit reservable/day facts into reservable_availability_log.

ALTER TABLE reservable_availability_monitors
  RENAME TO reservable_availability_pollers;

ALTER INDEX reservable_availability_monitors_active_idx
  RENAME TO reservable_availability_pollers_active_idx;

ALTER INDEX reservable_availability_monitors_reservable_idx
  RENAME TO reservable_availability_pollers_reservable_idx;

ALTER TABLE reservable_availability_pollers
  RENAME CONSTRAINT reservable_availability_monitors_pkey TO reservable_availability_pollers_pkey;

ALTER TABLE reservable_availability_pollers
  ALTER COLUMN reservable_id DROP NOT NULL,
  ADD COLUMN poi_id BIGINT REFERENCES pois(id) ON DELETE CASCADE,
  ADD COLUMN reservable_filters JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN target_dates DATE[],
  ADD COLUMN min_nights INT NOT NULL DEFAULT 1 CHECK (min_nights >= 1),
  ADD COLUMN next_poll_after TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN claimed_until TIMESTAMPTZ,
  ADD COLUMN claim_token TEXT;

UPDATE reservable_availability_pollers
SET target_dates = ARRAY[current_date]
WHERE target_dates IS NULL;

ALTER TABLE reservable_availability_pollers
  ALTER COLUMN target_dates SET NOT NULL,
  ADD CONSTRAINT reservable_availability_pollers_scope_check
    CHECK (
      (poi_id IS NOT NULL AND reservable_id IS NULL)
      OR (poi_id IS NULL AND reservable_id IS NOT NULL)
    ),
  ADD CONSTRAINT reservable_availability_pollers_target_dates_check
    CHECK (cardinality(target_dates) > 0);

CREATE INDEX reservable_availability_pollers_due_idx
  ON reservable_availability_pollers (status, next_poll_after)
  WHERE status = 'active';

CREATE INDEX reservable_availability_pollers_poi_idx
  ON reservable_availability_pollers (poi_id)
  WHERE poi_id IS NOT NULL;

CREATE TABLE reservable_availability_runs (
  id               BIGSERIAL   PRIMARY KEY,
  source_kind      TEXT        NOT NULL CHECK (source_kind IN ('query', 'poller')),
  poller_id        BIGINT      REFERENCES reservable_availability_pollers(id) ON DELETE SET NULL,
  intent_payload   JSONB       NOT NULL,
  status           TEXT        NOT NULL CHECK (status IN ('started', 'completed', 'failed')),
  candidate_count  INT         NOT NULL DEFAULT 0,
  log_count        INT         NOT NULL DEFAULT 0,
  error            TEXT,
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at     TIMESTAMPTZ
);

CREATE INDEX reservable_availability_runs_poller_idx
  ON reservable_availability_runs (poller_id, started_at DESC);

ALTER TABLE reservable_availability_log
  ADD COLUMN run_id BIGINT REFERENCES reservable_availability_runs(id) ON DELETE SET NULL;

CREATE INDEX reservable_availability_log_run_idx
  ON reservable_availability_log (run_id);
