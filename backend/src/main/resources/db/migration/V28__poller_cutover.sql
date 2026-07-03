ALTER TABLE availability_job_run RENAME TO availability_run;
ALTER TABLE availability_run RENAME COLUMN job_id TO poller_id;
ALTER INDEX availability_job_run_job_started_idx RENAME TO availability_run_poller_started_idx;
DELETE FROM availability_run;
ALTER TABLE availability_run DROP CONSTRAINT availability_job_run_job_id_fkey;
ALTER TABLE availability_run ADD CONSTRAINT availability_run_poller_id_fkey
  FOREIGN KEY (poller_id) REFERENCES availability_poller(id) ON DELETE CASCADE;
DROP TABLE availability_job;
