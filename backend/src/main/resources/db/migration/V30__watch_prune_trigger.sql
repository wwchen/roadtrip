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

CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty
    AFTER DELETE ON availability_watch_target
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION availability_watch_prune_when_no_targets();
