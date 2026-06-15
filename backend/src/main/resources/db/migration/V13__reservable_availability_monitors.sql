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

-- Append-only per-day availability snapshots captured from reservable
-- availability polls. A single poll window writes one row per returned
-- target_date so history queries can compare "what did rid X look like
-- yesterday vs today for date Y" without parsing whole response blobs.
CREATE TABLE reservable_availability_monitor_log (
  id               BIGSERIAL    PRIMARY KEY,
  monitor_id       BIGINT       REFERENCES reservable_availability_monitors(id) ON DELETE SET NULL,
  reservable_rid   TEXT         NOT NULL CHECK (length(trim(reservable_rid)) > 0),
  provider         TEXT         NOT NULL CHECK (length(trim(provider)) > 0),
  observed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  target_date      DATE         NOT NULL,
  window_start     DATE         NOT NULL,
  window_days      INT          NOT NULL CHECK (window_days > 0),
  min_nights       INT          NOT NULL CHECK (min_nights >= 1),
  force            BOOLEAN      NOT NULL DEFAULT FALSE,
  cache_hit        BOOLEAN      NOT NULL DEFAULT FALSE,
  status           TEXT         NOT NULL CHECK (status IN ('available', 'partial', 'booked', 'closed')),
  available        BOOLEAN      NOT NULL,
  available_count  INT          NOT NULL CHECK (available_count >= 0),
  total            INT          NOT NULL CHECK (total >= 0),
  day_payload      JSONB        NOT NULL CHECK (jsonb_typeof(day_payload) = 'object')
);

CREATE INDEX reservable_availability_monitor_log_rid_target_observed_idx
  ON reservable_availability_monitor_log (reservable_rid, target_date, observed_at DESC);

CREATE INDEX reservable_availability_monitor_log_rid_observed_idx
  ON reservable_availability_monitor_log (reservable_rid, observed_at DESC);

CREATE INDEX reservable_availability_monitor_log_monitor_observed_idx
  ON reservable_availability_monitor_log (monitor_id, observed_at DESC)
  WHERE monitor_id IS NOT NULL;
