-- PR1: poller coalescing (additive). The schedulable unit becomes the vendor call
-- unit (provider, parent_ref). Tables added here; the rename of availability_job_run
-- and the drop of availability_job land in a later cutover migration so this
-- migration keeps the module compiling.

CREATE TABLE availability_poller (
  id             BIGSERIAL   PRIMARY KEY,
  provider       TEXT        NOT NULL,
  parent_ref     TEXT        NOT NULL,
  poi_id         BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  active         BOOLEAN     NOT NULL DEFAULT TRUE,
  next_run_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_until  TIMESTAMPTZ,
  claim_token    TEXT,
  last_run_at    TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, parent_ref)
);

CREATE INDEX availability_poller_due_idx
  ON availability_poller (next_run_at) WHERE active;

CREATE TABLE availability_watch_poller (
  watch_id   BIGINT NOT NULL REFERENCES availability_watch(id)  ON DELETE CASCADE,
  poller_id  BIGINT NOT NULL REFERENCES availability_poller(id) ON DELETE CASCADE,
  PRIMARY KEY (watch_id, poller_id)
);
CREATE INDEX availability_watch_poller_poller_idx
  ON availability_watch_poller (poller_id);
