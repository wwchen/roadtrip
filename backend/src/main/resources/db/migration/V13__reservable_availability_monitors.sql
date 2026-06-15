-- RFC 0008 follow-up: per-reservable availability monitor registrations.
--
-- This stores the user's intent to keep checking one reservable at a given
-- cadence. The polling worker can consume active rows later; these API
-- endpoints only create and list monitor registrations.

CREATE TABLE reservable_availability_monitors (
  id                   BIGSERIAL    PRIMARY KEY,
  reservable_id        BIGINT       NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  cadence_sec          INT          NOT NULL CHECK (cadence_sec >= 5),
  trigger_actions      JSONB        NOT NULL CHECK (
                                      jsonb_typeof(trigger_actions) = 'array'
                                      AND jsonb_array_length(trigger_actions) > 0
                                    ),
  stop_when_triggered  BOOLEAN      NOT NULL DEFAULT TRUE,
  status               TEXT         NOT NULL DEFAULT 'active'
                                      CHECK (status IN ('active', 'paused', 'done')),
  last_checked_at      TIMESTAMPTZ,
  last_triggered_at    TIMESTAMPTZ,
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX reservable_availability_monitors_active_idx
  ON reservable_availability_monitors (status, cadence_sec)
  WHERE status = 'active';

CREATE INDEX reservable_availability_monitors_reservable_idx
  ON reservable_availability_monitors (reservable_id);

-- Append-only per-day single-night availability snapshots captured from
-- reservable availability polls. A single poll window writes one row per
-- returned target_date so history queries can compare "what did rid X look
-- like yesterday vs today for date Y" without parsing whole response blobs.
-- Multi-night availability is derived by combining consecutive target_date
-- rows from the same observed_at batch.
CREATE TABLE reservable_availability_log (
  id               BIGSERIAL    PRIMARY KEY,
  reservable_rid   TEXT         NOT NULL CHECK (length(trim(reservable_rid)) > 0),
  observed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  target_date      DATE         NOT NULL,
  status           TEXT         NOT NULL CHECK (status IN ('available', 'partial', 'booked', 'closed')),
  available        BOOLEAN      NOT NULL,
  day_payload      JSONB        NOT NULL CHECK (jsonb_typeof(day_payload) = 'object')
);

CREATE INDEX reservable_availability_log_rid_target_observed_idx
  ON reservable_availability_log (reservable_rid, target_date, observed_at DESC);

CREATE INDEX reservable_availability_log_rid_observed_idx
  ON reservable_availability_log (reservable_rid, observed_at DESC);
