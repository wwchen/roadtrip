-- PR 2: availability_job — one schedulable polling unit per watch.
--
-- The watch row holds intent (what dates, which reservable, what to do on
-- match). The job row holds *scheduler state*: when to run next, who has
-- the row claimed, and a frozen intent_payload so the worker doesn't have
-- to read the watch table to do its job.
--
-- Why a separate table: PR 1 deliberately kept watches as intent-only.
-- Adding next_run_at / claim_token / claimed_until to the watch row would
-- mix scheduler concerns with user-facing fields. A 1:1 split lets the
-- watch table stay user-shaped and the job table stay scheduler-shaped.
--
-- The intent_payload is denormalized on purpose — editing a watch's
-- target_dates after a run started should not retroactively change what
-- that run polled. Watch service rebuilds intent_payload whenever the
-- watch fields change.

CREATE TABLE availability_job (
  id              BIGSERIAL    PRIMARY KEY,
  watch_id        BIGINT       NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  intent_payload  JSONB        NOT NULL CHECK (jsonb_typeof(intent_payload) = 'object'),
  cadence_sec     INT          NOT NULL CHECK (cadence_sec >= 5),
  status          TEXT         NOT NULL DEFAULT 'active'
                                 CHECK (status IN ('active', 'paused', 'done')),
  next_run_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  claimed_until   TIMESTAMPTZ,
  claim_token     TEXT,
  last_run_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One job per watch. Lookup by watch is hot when watches are paused/edited.
CREATE UNIQUE INDEX availability_job_watch_idx
  ON availability_job (watch_id);

-- Hot path for the scheduler tick: "give me up to N rows that are due and
-- unclaimed." Partial index keeps it small even when most rows are paused
-- or done.
CREATE INDEX availability_job_due_idx
  ON availability_job (next_run_at)
  WHERE status = 'active';
