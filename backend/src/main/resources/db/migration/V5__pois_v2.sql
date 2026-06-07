-- RFC 0007 / PR 2: full schema reset for POIs.
--
-- The old `pois` table was tied to merged-geojson imports, with source-
-- specific quirks leaking through (`recgov_id` in JSONB, `aspira` block,
-- `reservable` as a static column). RFC 0007 decision #17 calls this a
-- full rewrite — drop the table, recreate against the sealed Poi model
-- (Section 4). No data preservation: the next PR (PR 3) repopulates
-- from the raw captures already on disk under data/raw/.
--
-- Two new dimension tables land here too — governing_body
-- (NPS/USFS/BC Parks/etc.) and booking_provider (RecGov/Aspira × host).
-- See V6 for seed rows.
--
-- Compatibility note: PoiRoutes.kt's `SELECT … reserve_url … FROM pois`
-- and Importer.kt's UPSERT both reference this table. The table reset
-- breaks them at runtime; PR 3 (Kotlin ETL) replaces the importer, and
-- PR 6 reshapes PoiRoutes. Between this PR and PR 3, the importer
-- is a no-op and `pois` stays empty.

DROP TABLE IF EXISTS pois CASCADE;

CREATE TABLE governing_body (
  id            BIGSERIAL PRIMARY KEY,
  -- 'agency-shorthand' — stable identifier referenced by ETL transformers.
  -- e.g. 'nps', 'usfs', 'bc-parks', 'parks-canada', 'tesla', 'pf-corp'.
  slug          TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL,                   -- display label
  -- Kind buckets the FE category filter renders against (federal/state/
  -- provincial/local/private). Distinct from `country` because a Canadian
  -- federal body is still 'federal' for filter purposes.
  kind          TEXT NOT NULL,
  country       CHAR(2),                          -- ISO 3166-1 alpha-2; null for global (Tesla)
  CHECK (kind IN ('federal', 'state', 'provincial', 'local', 'private', 'corporate'))
);

CREATE TABLE booking_provider (
  id              BIGSERIAL PRIMARY KEY,
  -- 'aspira', 'recgov', 'camis', 'none' — ETL dispatches adapters by this.
  vendor          TEXT NOT NULL,
  name            TEXT NOT NULL,                  -- display label
  -- Aspira gets one row per host (PC/BC/WA share `vendor='aspira'` but
  -- different hosts). Null when the provider is single-host (RecGov).
  host            TEXT,
  -- The Kotlin adapter class name the dispatcher loads for this row.
  -- Empty string means "no adapter implemented yet" — the row exists
  -- so curated data can FK to it, but availability returns no_provider.
  adapter_class   TEXT NOT NULL DEFAULT '',
  UNIQUE (vendor, host)
);

CREATE TABLE pois (
  id                   BIGSERIAL PRIMARY KEY,
  source               TEXT NOT NULL,                       -- 'uscampgrounds', 'osm-pf', 'aspira-maps-pc', ...
  source_id            TEXT NOT NULL,                       -- stable id from upstream
  category             TEXT NOT NULL,
  name                 TEXT NOT NULL,
  geom                 geometry(Geometry, 4326) NOT NULL,   -- Point | Polygon | MultiPolygon
  -- Base (shared across POI types per RFC #12):
  region               TEXT,                                -- US state / Canadian province
  country              CHAR(2),                             -- ISO 3166-1 alpha-2; FE dispatches park-search fallback off this
  unit_name            TEXT,                                -- containing park / forest (legacy; ETL may stop populating)
  phone                TEXT,
  address              JSONB,                               -- {street, city, postcode}
  info_url             TEXT,                                -- non-booking informational link (Park info on …, Visit website)
  governing_body_id    BIGINT REFERENCES governing_body(id) ON DELETE RESTRICT,
  booking_provider_id  BIGINT REFERENCES booking_provider(id) ON DELETE RESTRICT,
  -- Per-provider lookup payload. Sealed in Kotlin (RecGov/Aspira/Camis);
  -- payload-only on disk — booking_provider_id is the discriminator
  -- (RFC decision #24).
  provider_ref         JSONB,
  -- Per-type extras (Campground.amenities/activities/sites/season,
  -- Supercharger.stallCount/maxPowerKw, Park.designation/acres, etc.).
  -- Sealed Kotlin Poi types parse this; the column carries whatever
  -- shape the per-type transformer emits.
  properties           JSONB NOT NULL DEFAULT '{}',
  -- TRANSITIONAL: reserve_url survives this migration so the legacy
  -- Importer.kt + PoiRoutes.kt keep compiling against the regenerated
  -- jOOQ types. PR 3 (Kotlin ETL) writes provider_ref + info_url instead
  -- and stops populating reserve_url; PR 6 reshapes PoiRoutes to read the
  -- new fields; PR 8 drops this column. Keeping it through the rewrite
  -- keeps build green and the interim importer/route surface working
  -- without a dual-deploy dance.
  reserve_url          TEXT,
  fetched_at           TIMESTAMPTZ NOT NULL,                -- when raw capture was pulled from upstream
  last_verified        DATE,                                -- editorial confirmation (RFC #10)
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  -- Provenance: which import wrote this row (existing) + which poller
  -- run produced the raw capture that fed it (RFC #5). The two are not
  -- redundant — a row can be re-imported without a fresh fetch (replay)
  -- and a fresh fetch can fail to import (validation). The pair lets us
  -- distinguish.
  last_seen_run_id     BIGINT REFERENCES import_runs(id),
  last_poller_run_id   BIGINT REFERENCES ingest_runs(id),
  deleted_at           TIMESTAMPTZ,                          -- mark-and-sweep
  UNIQUE (source, source_id),
  CHECK (category IN ('campground', 'state-park', 'national-park', 'planet-fitness', 'supercharger')),
  CHECK (source_id ~ '^[a-z0-9:_-]+$')
);

CREATE INDEX pois_geom_idx              ON pois USING GIST (geom);
CREATE INDEX pois_category_idx          ON pois (category);
CREATE INDEX pois_governing_body_idx    ON pois (governing_body_id);
CREATE INDEX pois_booking_provider_idx  ON pois (booking_provider_id);
-- Active-only partial index keeps bbox queries fast without scanning
-- soft-deleted rows. (Same trade as V1.)
CREATE INDEX pois_active_idx            ON pois (source, source_id) WHERE deleted_at IS NULL;
