-- Cutover: availability_job -> availability_poller. Renames the run table and
-- repoints it at pollers. NOTE: `DELETE FROM availability_run` below discards all
-- pre-migration runs (they reference now-dead jobs and can't be repointed to a
-- poller). That DELETE cascades to `availability_fetch_call` (its run_id FK is
-- ON DELETE CASCADE), so pre-migration fetch-call trace rows are intentionally
-- discarded too — unlike `availability_snapshot`, whose run_id FK is ON DELETE
-- SET NULL, so snapshot history survives with a null run_id. This is deliberate:
-- fetch-call rows are per-run diagnostics with no value once their run is gone.
ALTER TABLE availability_job_run RENAME TO availability_run;
ALTER TABLE availability_run RENAME COLUMN job_id TO poller_id;
ALTER INDEX availability_job_run_job_started_idx RENAME TO availability_run_poller_started_idx;
DELETE FROM availability_run;
ALTER TABLE availability_run DROP CONSTRAINT availability_job_run_job_id_fkey;
ALTER TABLE availability_run ADD CONSTRAINT availability_run_poller_id_fkey
  FOREIGN KEY (poller_id) REFERENCES availability_poller(id) ON DELETE CASCADE;
DROP TABLE availability_job;
