-- RFC 0008: reservable hierarchy.
--
-- A reservable is anything-you-can-hold-at-a-place — campsite today; permit
-- and ticket types slot in via future RFCs. Composite identity is
-- (type, vendor, vendor_id); we don't store the encoded "site:recgov:330257"
-- composite string because that's a presentation concern rebuilt by the
-- model layer.
--
-- raw preserves the full upstream JSON blob (rec.gov campsite object,
-- Aspira resource detail) for data trust. Refreshed by the catalog ETL,
-- not by request-time availability calls. See docs/booking-providers.md
-- and rfcs/0008-reservable-hierarchy.md.

CREATE TABLE reservables (
  id          BIGSERIAL    PRIMARY KEY,
  type        TEXT         NOT NULL,
  vendor      TEXT         NOT NULL,
  vendor_id   TEXT         NOT NULL,
  name        TEXT,
  loop        TEXT,
  site_type   TEXT,
  raw         JSONB,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (type, vendor, vendor_id)
);

-- N:M reservable→POI. A reservable can belong to multiple POIs (campground
-- POI plus the parent-park POI, when park POIs eventually exist). At v1
-- each reservable has exactly one POI parent; the schema is N:M so future
-- park-POI ingestion is additive — no migration.
CREATE TABLE reservable_pois (
  reservable_id  BIGINT       NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  poi_id         BIGINT       NOT NULL REFERENCES pois(id)        ON DELETE CASCADE,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (reservable_id, poi_id)
);

-- Listing endpoint pattern: "give me all reservables at this POI of this
-- type." Composite index is the obvious one; the (poi_id, type) prefix
-- supports the WHERE clause and the (id) join into reservables stays an
-- index-only scan via the PK.
CREATE INDEX reservable_pois_poi_idx ON reservable_pois (poi_id);
