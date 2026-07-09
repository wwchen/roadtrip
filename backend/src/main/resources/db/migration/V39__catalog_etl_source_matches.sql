-- Keep one campground/campsite record per ETL source. Cross-source identity is
-- represented explicitly in *_matches instead of by merging rows through
-- shared vendor refs.

ALTER TABLE campgrounds
  ADD COLUMN etl_source TEXT;

UPDATE campgrounds cg
SET etl_source = COALESCE(
  (
    SELECT vr.vendor
    FROM campground_vendor_refs cvr
    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
    WHERE cvr.campground_id = cg.id
      AND cvr.is_primary
      AND vr.deleted_at IS NULL
    ORDER BY cvr.vendor_ref_id
    LIMIT 1
  ),
  'unknown'
);

ALTER TABLE campgrounds
  ALTER COLUMN etl_source SET NOT NULL,
  ADD CONSTRAINT campgrounds_etl_source_check CHECK (length(btrim(etl_source)) > 0);

CREATE INDEX campgrounds_active_etl_source_idx
  ON campgrounds (etl_source)
  WHERE deleted_at IS NULL;

ALTER TABLE campsites
  ADD COLUMN etl_source TEXT;

UPDATE campsites c
SET etl_source = COALESCE(
  (
    SELECT vr.vendor
    FROM campsite_vendor_refs cvr
    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
    WHERE cvr.campsite_id = c.id
      AND cvr.is_primary
      AND vr.deleted_at IS NULL
    ORDER BY cvr.vendor_ref_id
    LIMIT 1
  ),
  'unknown'
);

ALTER TABLE campsites
  ALTER COLUMN etl_source SET NOT NULL,
  ADD CONSTRAINT campsites_etl_source_check CHECK (length(btrim(etl_source)) > 0);

CREATE INDEX campsites_active_etl_source_idx
  ON campsites (etl_source)
  WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS campground_vendor_refs_vendor_ref_uidx;

CREATE UNIQUE INDEX campground_vendor_refs_primary_vendor_ref_uidx
  ON campground_vendor_refs (vendor_ref_id)
  WHERE is_primary;

DROP INDEX IF EXISTS campsite_vendor_refs_vendor_ref_uidx;

CREATE UNIQUE INDEX campsite_vendor_refs_primary_vendor_ref_uidx
  ON campsite_vendor_refs (vendor_ref_id)
  WHERE is_primary;

CREATE TABLE campground_matches (
  id                       BIGSERIAL PRIMARY KEY,
  campground_id            BIGINT      NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  matched_campground_id    BIGINT      NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  match_heuristic          JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campground_matches_distinct_check CHECK (campground_id <> matched_campground_id),
  CONSTRAINT campground_matches_order_check CHECK (campground_id < matched_campground_id),
  CONSTRAINT campground_matches_heuristic_check CHECK (jsonb_typeof(match_heuristic) = 'object')
);

CREATE UNIQUE INDEX campground_matches_pair_uidx
  ON campground_matches (campground_id, matched_campground_id);

CREATE INDEX campground_matches_matched_idx
  ON campground_matches (matched_campground_id);

CREATE TABLE campsite_matches (
  id                    BIGSERIAL PRIMARY KEY,
  campsite_id           BIGINT      NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  matched_campsite_id   BIGINT      NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  match_heuristic       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campsite_matches_distinct_check CHECK (campsite_id <> matched_campsite_id),
  CONSTRAINT campsite_matches_order_check CHECK (campsite_id < matched_campsite_id),
  CONSTRAINT campsite_matches_heuristic_check CHECK (jsonb_typeof(match_heuristic) = 'object')
);

CREATE UNIQUE INDEX campsite_matches_pair_uidx
  ON campsite_matches (campsite_id, matched_campsite_id);

CREATE INDEX campsite_matches_matched_idx
  ON campsite_matches (matched_campsite_id);
