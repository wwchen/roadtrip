-- V1 schema for the POIs (Points of Interest) backend.
-- Replaces Phase 1's static GeoJSON files with a Postgres+PostGIS source of
-- truth that the importer rebuilds idempotently from data/raw/. The webapp
-- moves from "fetch giant GeoJSON" to "fetch /api/pois?bbox=..." with a
-- 2000-row cap.
--
-- Two design choices worth flagging:
--   1. geom is geometry(Geometry,4326) — Geometry, not Point. state-parks
--      and national-parks are Polygon/MultiPolygon and must NOT be silently
--      downcast to centroids.
--   2. last_seen_run_id + deleted_at is mark-and-sweep. Importer.kt opens
--      an import_runs row, UPSERTs each source row marking last_seen_run_id,
--      then sweeps anything older. A tripwire (seen_count < 0.5*existing)
--      aborts the run before sweep so a bad fetch can't wipe the table.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE import_runs (
  id            BIGSERIAL PRIMARY KEY,
  source        TEXT NOT NULL,
  status        TEXT NOT NULL,          -- 'started', 'completed', 'failed'
  started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at  TIMESTAMPTZ,
  seen_count    INTEGER,
  notes         TEXT
);

CREATE TABLE pois (
  id               BIGSERIAL PRIMARY KEY,
  source           TEXT NOT NULL,                    -- 'uscampgrounds', 'bc-parks', 'parks-canada', 'alberta-parks', 'osm-pf'
  source_id        TEXT NOT NULL,                    -- stable id from the source
  category         TEXT NOT NULL,
  name             TEXT NOT NULL,
  geom             geometry(Geometry, 4326) NOT NULL, -- Point OR Polygon/MultiPolygon
  region           TEXT,                             -- US state / Canadian province
  unit_name        TEXT,                             -- containing park / forest
  properties       JSONB NOT NULL DEFAULT '{}',
  reserve_url      TEXT,
  fetched_at       TIMESTAMPTZ NOT NULL,             -- when raw data was pulled from source
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_run_id BIGINT REFERENCES import_runs(id),
  deleted_at       TIMESTAMPTZ,
  UNIQUE (source, source_id),
  CHECK (category IN ('campground', 'state-park', 'national-park', 'planet-fitness')),
  CHECK (source_id ~ '^[a-z0-9:_-]+$')
);

CREATE INDEX pois_geom_idx     ON pois USING GIST (geom);
CREATE INDEX pois_category_idx ON pois (category);
-- Active-only partial index keeps bbox queries fast without scanning soft-deleted rows.
CREATE INDEX pois_active_idx   ON pois (source, source_id) WHERE deleted_at IS NULL;
