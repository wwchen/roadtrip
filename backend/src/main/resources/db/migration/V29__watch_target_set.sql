-- PR2: watch scope widens from "exactly one of poi_id/reservable_id" to a
-- SET of targets. One row per (POI or reservable) the watch covers. Backfill
-- one row per existing watch's single-scope column before dropping it, so no
-- watch silently loses its scope. Coalescing (PR1's poller layer) is
-- unaffected — WatchScopeResolver still hands AvailabilityPollerMembership a
-- flat List<Reservable>; only how that list is derived changes.

CREATE TABLE availability_watch_target (
  id             BIGSERIAL NOT NULL PRIMARY KEY,
  watch_id       BIGINT    NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  poi_id         BIGINT    REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id  BIGINT    REFERENCES reservables(id) ON DELETE CASCADE,
  CHECK ((poi_id IS NOT NULL) <> (reservable_id IS NOT NULL))
);

CREATE INDEX availability_watch_target_watch_idx
  ON availability_watch_target (watch_id);
CREATE INDEX availability_watch_target_poi_idx
  ON availability_watch_target (poi_id)
  WHERE poi_id IS NOT NULL;
CREATE INDEX availability_watch_target_reservable_idx
  ON availability_watch_target (reservable_id)
  WHERE reservable_id IS NOT NULL;

-- Backfill: one target row per existing watch's single scope column.
INSERT INTO availability_watch_target (watch_id, poi_id, reservable_id)
SELECT id, poi_id, reservable_id
FROM availability_watch
WHERE poi_id IS NOT NULL OR reservable_id IS NOT NULL;

ALTER TABLE availability_watch
  DROP CONSTRAINT availability_watch_scope_check,
  DROP COLUMN poi_id,
  DROP COLUMN reservable_id;
