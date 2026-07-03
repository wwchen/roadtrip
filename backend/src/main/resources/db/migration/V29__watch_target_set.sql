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

-- Preserve the pre-V29 invariant: a watch with no scope should not exist.
-- Before V29, availability_watch.poi_id/reservable_id cascaded, so deleting the
-- scoped POI/reservable deleted the watch. Now the scope lives in
-- availability_watch_target; when a target's POI/reservable is deleted the target
-- row cascades away, and if it was the watch's last target we delete the watch too.
-- Deleting the watch cascades to availability_watch_poller (ON DELETE CASCADE, V27),
-- so poller links are cleaned and the executor reaps the now-empty poller. A
-- multi-target watch that loses ONE target survives (still has other targets).
--
-- DEFERRABLE INITIALLY DEFERRED: AvailabilityWatchTargetRepo.replaceForWatch
-- deletes a watch's targets and re-inserts the new set as two separate
-- statements. If this fired immediately (end of the DELETE statement), a
-- same-watch replace would see zero targets mid-flight and prune the watch
-- before the re-insert lands. Deferring the check to COMMIT means it only
-- sees the target set actually in effect once the whole unit of work is
-- done. AvailabilityWatchRepo.update()/create() and this repo's own
-- replaceForWatch/deleteForWatch all run inside a single transaction per
-- call (jOOQ default: no explicit BEGIN means each top-level statement is
-- its own transaction, so DELETE+INSERTs from one replaceForWatch call must
-- be wrapped in ctx.transaction{} for the deferred check to span both).
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

CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty
    AFTER DELETE ON availability_watch_target
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION availability_watch_prune_when_no_targets();
