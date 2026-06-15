-- RFC 0008 follow-up: per-reservable availability monitor registrations.
--
-- This stores the user's intent to keep checking one reservable at a given
-- cadence. The polling worker can consume active rows later; these API
-- endpoints only create and list monitor registrations.

CREATE TABLE reservable_availability_monitors (
  id                   BIGSERIAL    PRIMARY KEY,
  reservable_id        BIGINT       NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  cadence_sec          INT          NOT NULL CHECK (cadence_sec >= 5),
  trigger_action       TEXT         NOT NULL CHECK (length(trim(trigger_action)) > 0),
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
