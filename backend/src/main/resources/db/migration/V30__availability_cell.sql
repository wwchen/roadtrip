-- PR3: availability cube. availability_cell is the current face (upserted every
-- poll); availability_snapshot becomes the edge-triggered depth axis (a row only
-- when a cell's status changes). 'past' is the terminal status for cells whose
-- target_date has elapsed -- the cube stops recording new state for that date.

ALTER TYPE availability_status ADD VALUE IF NOT EXISTS 'past';

CREATE TABLE availability_cell (
  reservable_id    BIGINT      NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  target_date      DATE        NOT NULL,
  status           availability_status NOT NULL,
  last_observed_at TIMESTAMPTZ NOT NULL,
  last_changed_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (reservable_id, target_date)
);

-- Hot path for the cell-matrix panel and any watch-eval read (PR6): one row per
-- reservable's date range.
CREATE INDEX availability_cell_reservable_date_idx
  ON availability_cell (reservable_id, target_date);
