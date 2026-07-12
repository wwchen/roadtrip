-- Remove catalog matching as a product feature.
--
-- The canonical views remain as serving views, but they no longer collapse
-- multiple vendor rows into one identity. Each active campground/campsite row
-- is its own canonical row.

DROP MATERIALIZED VIEW IF EXISTS campsite_canonical;
DROP MATERIALIZED VIEW IF EXISTS campground_canonical;

DROP TABLE IF EXISTS campsite_matches;
DROP TABLE IF EXISTS campground_matches;

DROP INDEX IF EXISTS campgrounds_match_group_idx;
DROP INDEX IF EXISTS campsites_match_group_idx;
DROP INDEX IF EXISTS campgrounds_location_geog_idx;

ALTER TABLE campgrounds
  DROP COLUMN IF EXISTS match_group_id,
  DROP COLUMN IF EXISTS preferred_availability_source;

ALTER TABLE campsites
  DROP COLUMN IF EXISTS match_group_id;

CREATE MATERIALIZED VIEW campground_canonical AS
SELECT cg.*,
       cg.id AS group_key,
       ARRAY[cg.id]::BIGINT[] AS member_ids,
       ARRAY[cg.data_source]::TEXT[] AS member_sources
FROM campgrounds cg
WHERE cg.deleted_at IS NULL;

CREATE UNIQUE INDEX campground_canonical_id_uidx ON campground_canonical (id);
CREATE INDEX campground_canonical_group_idx ON campground_canonical (group_key);

CREATE MATERIALIZED VIEW campsite_canonical AS
SELECT cs.*,
       cs.id AS group_key,
       ARRAY[cs.id]::BIGINT[] AS member_ids,
       ARRAY[cs.data_source]::TEXT[] AS member_sources
FROM campsites cs
WHERE cs.deleted_at IS NULL;

CREATE UNIQUE INDEX campsite_canonical_id_uidx ON campsite_canonical (id);
CREATE INDEX campsite_canonical_group_idx ON campsite_canonical (group_key);
