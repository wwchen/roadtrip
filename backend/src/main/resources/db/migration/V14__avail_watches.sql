-- PR 1: Replace reservable_availability_monitors with availability_watch.
--
-- The watch table is user intent only. Scheduler state (next_run_at, claim
-- token, lease) lives on a separate availability_job table introduced in
-- PR 2. Watch scope widens from "one reservable" to "POI-with-filters OR
-- one reservable" so a single watch can cover all child sites of a
-- campground.
--
-- The old reservable_availability_log table (V13) is left intact and gets
-- renamed to availability_snapshot in PR 4.

DROP TABLE IF EXISTS reservable_availability_monitors;

CREATE TABLE availability_watch (
  id                    BIGSERIAL    PRIMARY KEY,
  poi_id                BIGINT       REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id         BIGINT       REFERENCES reservables(id) ON DELETE CASCADE,
  reservable_filters    JSONB        NOT NULL DEFAULT '{}'::jsonb
                                       CHECK (jsonb_typeof(reservable_filters) = 'object'),
  target_dates          DATE[]       NOT NULL
                                       CHECK (cardinality(target_dates) > 0),
  min_nights            INT          NOT NULL DEFAULT 1
                                       CHECK (min_nights >= 1),
  cadence_sec           INT          NOT NULL
                                       CHECK (cadence_sec >= 5),
  trigger_kinds         TEXT[]       NOT NULL
                                       CHECK (cardinality(trigger_kinds) > 0),
  trigger_config        JSONB        NOT NULL DEFAULT '{}'::jsonb
                                       CHECK (jsonb_typeof(trigger_config) = 'object'),
  stop_when_triggered   BOOLEAN      NOT NULL DEFAULT TRUE,
  status                TEXT         NOT NULL DEFAULT 'active'
                                       CHECK (status IN ('active', 'paused', 'done')),
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT availability_watch_scope_check CHECK (
    (poi_id IS NOT NULL AND reservable_id IS NULL)
    OR (poi_id IS NULL AND reservable_id IS NOT NULL)
  )
);

CREATE INDEX availability_watch_active_idx
  ON availability_watch (status)
  WHERE status = 'active';

CREATE INDEX availability_watch_poi_idx
  ON availability_watch (poi_id)
  WHERE poi_id IS NOT NULL;

CREATE INDEX availability_watch_reservable_idx
  ON availability_watch (reservable_id)
  WHERE reservable_id IS NOT NULL;
