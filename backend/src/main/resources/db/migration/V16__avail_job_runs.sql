-- PR 3: availability_job_run — one row per poll execution.
--
-- Every time the scheduler claims an availability_job and hands it to
-- AvailabilityPollExecutor, the executor writes one row here. The row
-- records the run's outcome (started / completed / failed), how many
-- snapshot rows it produced, and any error string for failed runs.
--
-- Why a separate table: PR 2 deliberately kept availability_job as
-- just-the-current-state. Per-run history (was this run successful, what
-- did it return, how long did it take) is append-only and unbounded;
-- mixing it onto the job row would conflate "what's the latest state"
-- with "what's the audit log of every execution."
--
-- Retention: indefinite for now. PR 4+ may add a sweeper if row count
-- becomes operationally annoying. The hot index supports per-job
-- "give me the N most recent runs" queries (the dashboard's load-bearing
-- query in a later PR).

CREATE TABLE availability_job_run (
  id              BIGSERIAL    PRIMARY KEY,
  job_id          BIGINT       NOT NULL REFERENCES availability_job(id) ON DELETE CASCADE,
  status          TEXT         NOT NULL DEFAULT 'started'
                                 CHECK (status IN ('started', 'completed', 'failed')),
  snapshot_count  INT          NOT NULL DEFAULT 0
                                 CHECK (snapshot_count >= 0),
  duration_ms     INT          CHECK (duration_ms IS NULL OR duration_ms >= 0),
  error           TEXT,
  started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ
);

-- Hot path: "show me the last N runs for this job, newest first" is what
-- the dashboard's per-job drill-down will query. Composite index on
-- (job_id, started_at DESC) supports it without a sort step.
CREATE INDEX availability_job_run_job_started_idx
  ON availability_job_run (job_id, started_at DESC);
