-- Per-alert poll cadence + system schedules table.
--
-- Replaces the single global `settings.poll_interval` with two concerns:
--   1. alerts.cadence_sec — how often this alert should be polled. Per-alert
--      so wide-open campgrounds can be polled less aggressively than near-term
--      sniped ones. The global rec.gov rate limiter (1.5s mutex in
--      AvailabilityClient) still throttles outbound traffic.
--   2. schedules table — system-level event triggers (token refresh, lease
--      sweep, companion sweep, liveness tick). Each row maps to one coroutine
--      job that publishes the named CampsiteEvent on its cadence.
--
-- See rfcs/0001-event-driven-recgov.md for the architecture.

ALTER TABLE alerts
    ADD COLUMN cadence_sec INT NOT NULL DEFAULT 60 CHECK (cadence_sec >= 5);

-- Carry forward existing global cadence onto each alert. settings.poll_interval
-- stays in place for one release as a fallback default for new alerts; the
-- global UI for it is removed in PR 4 when AvailabilityManager ships.
UPDATE alerts
SET cadence_sec = COALESCE(
    (SELECT NULLIF(value, '')::INT FROM settings WHERE key = 'poll_interval'),
    60
);

CREATE TABLE schedules (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT        UNIQUE NOT NULL,
    event_type   TEXT        NOT NULL,
    payload_json JSONB       NOT NULL DEFAULT '{}'::jsonb,
    cadence_sec  INT         NOT NULL CHECK (cadence_sec >= 1),
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed the four system schedules. event_type values match
-- CampsiteEvent subclass names (see CampsiteEvent.kt).
--   token_refresh   — keep recgov JWT fresh well ahead of expiry
--   lease_sweep     — flip CLAIMED matches with expired leases back to unclaimed
--   companion_sweep — emit CompanionOffline when last_seen exceeds threshold
--   liveness_tick   — SSE heartbeat so the frontend's connection indicator works
INSERT INTO schedules (name, event_type, cadence_sec) VALUES
    ('token_refresh',   'TokenRefreshDue',   240),
    ('lease_sweep',     'LeaseSweepDue',       5),
    ('companion_sweep', 'CompanionSweepDue',   5),
    ('liveness_tick',   'LivenessTick',       10);
