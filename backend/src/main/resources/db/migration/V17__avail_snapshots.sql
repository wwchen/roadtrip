-- Rename reservable_availability_log to availability_snapshot.
--
-- Replaces stringly-typed reservable_rid with a real reservable_id FK so
-- joins against reservables work without parsing composite RID strings.
-- Adds run_id FK to availability_job_run so snapshots are linkable to
-- the run that produced them; this also makes availability_job_run
-- snapshot_count derivable via SQL when needed.
--
-- Backfill: parse {type}:{vendor}:{vendor_id} from reservable_rid and
-- look up reservables.id. Unmatched rows (vendor renamed, reservable
-- deleted) keep reservable_id NULL — that's data-quality, not migration
-- failure. New rows written by the executor populate reservable_id
-- directly from the in-memory Reservable.

ALTER TABLE reservable_availability_log
  RENAME TO availability_snapshot;

-- Old indexes reference the old table name; rename so future index
-- changes don't fight Postgres' "index name doesn't match table" naming
-- conventions.
ALTER INDEX reservable_availability_log_rid_target_observed_idx
  RENAME TO availability_snapshot_old_rid_target_observed_idx;
ALTER INDEX reservable_availability_log_rid_observed_idx
  RENAME TO availability_snapshot_old_rid_observed_idx;

-- Add the FK columns. Both nullable: reservable_id stays nullable so
-- unmatched backfills don't block the migration; run_id is nullable
-- because ad-hoc availability fetches (the existing route) write
-- snapshots outside any job run.
ALTER TABLE availability_snapshot
  ADD COLUMN reservable_id BIGINT REFERENCES reservables(id) ON DELETE SET NULL,
  ADD COLUMN run_id BIGINT REFERENCES availability_job_run(id) ON DELETE SET NULL;

-- Backfill reservable_id by parsing the composite RID and joining
-- reservables on (type, vendor, vendor_id). split_part with 3 fields
-- handles the standard shape; vendor_ids that contain ':' are rare in
-- existing data but get NULL here (operator can re-fetch if needed).
UPDATE availability_snapshot s
SET reservable_id = r.id
FROM reservables r
WHERE r.type      = split_part(s.reservable_rid, ':', 1)
  AND r.vendor    = split_part(s.reservable_rid, ':', 2)
  AND r.vendor_id = split_part(s.reservable_rid, ':', 3);

-- Drop the stringly-typed column. After this, reservable_rid is no
-- longer queryable; reads go through the FK.
ALTER TABLE availability_snapshot
  DROP COLUMN reservable_rid;

-- New indexes mirror the old query shapes but on the FK. The old
-- "_old_*" indexes from the rename above are now stale (column gone)
-- and Postgres dropped them automatically when reservable_rid was
-- removed; nothing to clean up explicitly.
CREATE INDEX availability_snapshot_reservable_target_observed_idx
  ON availability_snapshot (reservable_id, target_date, observed_at DESC);

CREATE INDEX availability_snapshot_reservable_observed_idx
  ON availability_snapshot (reservable_id, observed_at DESC);

-- Hot path for the future runs dashboard: "what snapshots did this run
-- produce?" Partial index keeps it small for ad-hoc rows where run_id
-- is NULL.
CREATE INDEX availability_snapshot_run_idx
  ON availability_snapshot (run_id)
  WHERE run_id IS NOT NULL;
