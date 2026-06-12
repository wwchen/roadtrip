-- RFC 0008 follow-up: make reservable imports reconcile disappearances.
--
-- POIs already use last_seen_run_id + deleted_at mark-and-sweep. Reservables
-- now get the same shape, scoped by the terminal reservable_data ETL slug in
-- `source`.

ALTER TABLE reservables
  ADD COLUMN source TEXT,
  ADD COLUMN last_seen_run_id BIGINT REFERENCES import_runs(id),
  ADD COLUMN deleted_at TIMESTAMPTZ;

-- Backfill rows already imported by the RFC 0008 rollout. Future rows set
-- source at import time via ReservableRepo.runImport().
UPDATE reservables
SET source = CASE vendor
  WHEN 'recgov' THEN 'federal-campsites'
  WHEN 'aspira_bc' THEN 'aspira-resources-bc'
  WHEN 'aspira_pc' THEN 'aspira-resources-pc'
  WHEN 'aspira_wa' THEN 'aspira-resources-wa'
  ELSE vendor
END
WHERE source IS NULL;

ALTER TABLE reservables
  ALTER COLUMN source SET NOT NULL;

CREATE INDEX reservables_active_source_idx ON reservables (source) WHERE deleted_at IS NULL;
CREATE INDEX reservables_active_identity_idx ON reservables (type, vendor, vendor_id) WHERE deleted_at IS NULL;
