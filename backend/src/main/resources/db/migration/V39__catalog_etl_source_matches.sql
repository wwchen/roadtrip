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

CREATE MATERIALIZED VIEW catalog_match_rows AS
SELECT
  'campground'::text                 AS entity_type,
  cm.id                              AS match_id,
  cm.campground_id                   AS left_record_id,
  left_cg.etl_source                 AS left_etl_source,
  left_cg.name                       AS left_name,
  left_primary_ref.vendor            AS left_primary_vendor,
  left_primary_ref.external_id       AS left_primary_external_id,
  cm.matched_campground_id           AS right_record_id,
  right_cg.etl_source                AS right_etl_source,
  right_cg.name                      AS right_name,
  right_primary_ref.vendor           AS right_primary_vendor,
  right_primary_ref.external_id      AS right_primary_external_id,
  cm.match_heuristic                 AS match_heuristic,
  cm.created_at                      AS match_created_at,
  cm.updated_at                      AS match_updated_at
FROM campground_matches cm
JOIN campgrounds left_cg ON left_cg.id = cm.campground_id
JOIN campgrounds right_cg ON right_cg.id = cm.matched_campground_id
LEFT JOIN campground_vendor_refs left_primary_cvr
  ON left_primary_cvr.campground_id = left_cg.id
 AND left_primary_cvr.is_primary
LEFT JOIN vendor_refs left_primary_ref
  ON left_primary_ref.id = left_primary_cvr.vendor_ref_id
 AND left_primary_ref.deleted_at IS NULL
LEFT JOIN campground_vendor_refs right_primary_cvr
  ON right_primary_cvr.campground_id = right_cg.id
 AND right_primary_cvr.is_primary
LEFT JOIN vendor_refs right_primary_ref
  ON right_primary_ref.id = right_primary_cvr.vendor_ref_id
 AND right_primary_ref.deleted_at IS NULL
WHERE left_cg.deleted_at IS NULL
  AND right_cg.deleted_at IS NULL
UNION ALL
SELECT
  'campsite'::text                   AS entity_type,
  cm.id                              AS match_id,
  cm.campsite_id                     AS left_record_id,
  left_c.etl_source                  AS left_etl_source,
  left_c.name                        AS left_name,
  left_primary_ref.vendor            AS left_primary_vendor,
  left_primary_ref.external_id       AS left_primary_external_id,
  cm.matched_campsite_id             AS right_record_id,
  right_c.etl_source                 AS right_etl_source,
  right_c.name                       AS right_name,
  right_primary_ref.vendor           AS right_primary_vendor,
  right_primary_ref.external_id      AS right_primary_external_id,
  cm.match_heuristic                 AS match_heuristic,
  cm.created_at                      AS match_created_at,
  cm.updated_at                      AS match_updated_at
FROM campsite_matches cm
JOIN campsites left_c ON left_c.id = cm.campsite_id
JOIN campsites right_c ON right_c.id = cm.matched_campsite_id
LEFT JOIN campsite_vendor_refs left_primary_cvr
  ON left_primary_cvr.campsite_id = left_c.id
 AND left_primary_cvr.is_primary
LEFT JOIN vendor_refs left_primary_ref
  ON left_primary_ref.id = left_primary_cvr.vendor_ref_id
 AND left_primary_ref.deleted_at IS NULL
LEFT JOIN campsite_vendor_refs right_primary_cvr
  ON right_primary_cvr.campsite_id = right_c.id
 AND right_primary_cvr.is_primary
LEFT JOIN vendor_refs right_primary_ref
  ON right_primary_ref.id = right_primary_cvr.vendor_ref_id
 AND right_primary_ref.deleted_at IS NULL
WHERE left_c.deleted_at IS NULL
  AND right_c.deleted_at IS NULL;

CREATE UNIQUE INDEX catalog_match_rows_uidx
  ON catalog_match_rows (entity_type, left_record_id, right_record_id);

CREATE INDEX catalog_match_rows_etl_source_idx
  ON catalog_match_rows (left_etl_source, right_etl_source);
