-- Collapse availability_cell (matrix) + availability_snapshot (history) into one
-- interval table. Each row is a status-run for a (reservable_id, target_date)
-- cell: last_observed_at advances in place on unchanged polls; a status change
-- inserts a new row linked by previous_id. Current state = MAX(last_observed_at)
-- per cell. No backfill (dev DB is reset; history is disposable).

CREATE TABLE availability (
  id               BIGSERIAL PRIMARY KEY,
  reservable_id    BIGINT      NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  target_date      DATE        NOT NULL,
  status           availability_status NOT NULL,
  last_observed_at TIMESTAMPTZ NOT NULL,
  previous_id      BIGINT      REFERENCES availability(id) ON DELETE SET NULL,
  run_id           BIGINT      REFERENCES availability_run(id) ON DELETE SET NULL
);

-- A status-run has at most one successor: keeps the previous_id chain linear.
CREATE UNIQUE INDEX availability_previous_id_uq
  ON availability (previous_id) WHERE previous_id IS NOT NULL;

-- Current-state read + write-path "find current" lookup: top-1 per cell.
CREATE INDEX availability_current_idx
  ON availability (reservable_id, target_date, last_observed_at DESC);

CREATE INDEX availability_run_idx
  ON availability (run_id) WHERE run_id IS NOT NULL;

DROP TABLE availability_cell;
DROP TABLE availability_snapshot;
