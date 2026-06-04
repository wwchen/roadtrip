-- Campsite v0.X schema. Three tables: alerts, matches, settings.
-- Mirrors the legacy data.json shape; the migrate task ports rows in.

CREATE TABLE alerts (
    id                BIGSERIAL PRIMARY KEY,
    campground_id     TEXT        NOT NULL,
    campground_name   TEXT        NOT NULL,
    parent_name       TEXT,
    parent_id         TEXT,
    start_date        DATE        NOT NULL,
    end_date          DATE        NOT NULL,
    min_nights        INT         NOT NULL DEFAULT 1,
    campsite_types    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    equipment_types   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    max_people        INT,
    specific_sites    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    notify_slack      BOOLEAN     NOT NULL DEFAULT TRUE,
    auto_cart         BOOLEAN     NOT NULL DEFAULT FALSE,
    stop_after_match  BOOLEAN     NOT NULL DEFAULT TRUE,
    status            TEXT        NOT NULL DEFAULT 'active',
    last_checked      TIMESTAMPTZ,
    last_match        TIMESTAMPTZ,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE matches (
    id              BIGSERIAL PRIMARY KEY,
    alert_id        BIGINT      NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
    campground_id   TEXT        NOT NULL,
    campsite_id     TEXT        NOT NULL,
    campsite_site   TEXT,
    campsite_loop   TEXT,
    campsite_type   TEXT,
    available_dates JSONB       NOT NULL,
    first_date      DATE        NOT NULL,
    nights          INT         NOT NULL,
    found_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified        BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Lease state for the companion claim flow. NULL = unclaimed.
    claimed_by      TEXT,
    claimed_at      TIMESTAMPTZ,
    lease_expires   TIMESTAMPTZ,
    -- Result reported by companion after the ATC attempt.
    cart_added      BOOLEAN,
    result_at       TIMESTAMPTZ,
    -- Soft-dismiss so the UI can hide a match without losing history.
    dismissed_at    TIMESTAMPTZ
);

CREATE INDEX idx_matches_alert_id ON matches(alert_id);
CREATE INDEX idx_matches_found_at ON matches(found_at DESC);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
