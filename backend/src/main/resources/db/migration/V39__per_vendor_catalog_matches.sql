-- V39__per_vendor_catalog_matches.sql
-- Per-vendor catalog rows: each ETL source owns its campground/campsite rows.
-- Cross-vendor identity moves from is_primary vendor-ref aliasing to explicit
-- match tables + canonical materialized views (row-level winner per group).

ALTER TABLE campgrounds
  ADD COLUMN etl_source TEXT,
  ADD COLUMN match_group_id BIGINT,
  ADD COLUMN preferred_availability_source TEXT;
ALTER TABLE campsites
  ADD COLUMN etl_source TEXT,
  ADD COLUMN match_group_id BIGINT;

-- Backfill etl_source from the current primary vendor ref (pre-drop).
UPDATE campgrounds cg SET etl_source = vr.vendor
FROM campground_vendor_refs cvr
JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
WHERE cvr.campground_id = cg.id AND cvr.is_primary;

UPDATE campsites cs SET etl_source = vr.vendor
FROM campsite_vendor_refs cvr
JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
WHERE cvr.campsite_id = cs.id AND cvr.is_primary;

-- Rows without a primary ref inherit their only ref's vendor; anything still
-- null gets 'unknown' so NOT NULL can hold (rebuildable data, V38 precedent).
UPDATE campgrounds cg SET etl_source = (
  SELECT vr.vendor FROM campground_vendor_refs cvr
  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
  WHERE cvr.campground_id = cg.id
  ORDER BY cvr.vendor_ref_id LIMIT 1
) WHERE etl_source IS NULL;
UPDATE campsites cs SET etl_source = (
  SELECT vr.vendor FROM campsite_vendor_refs cvr
  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
  WHERE cvr.campsite_id = cs.id
  ORDER BY cvr.vendor_ref_id LIMIT 1
) WHERE etl_source IS NULL;
UPDATE campgrounds SET etl_source = 'unknown' WHERE etl_source IS NULL;
UPDATE campsites SET etl_source = 'unknown' WHERE etl_source IS NULL;

ALTER TABLE campgrounds ALTER COLUMN etl_source SET NOT NULL;
ALTER TABLE campsites ALTER COLUMN etl_source SET NOT NULL;
ALTER TABLE campgrounds
  ADD CONSTRAINT campgrounds_etl_source_check CHECK (length(btrim(etl_source)) > 0);
ALTER TABLE campsites
  ADD CONSTRAINT campsites_etl_source_check CHECK (length(btrim(etl_source)) > 0);

CREATE INDEX campgrounds_match_group_idx ON campgrounds (match_group_id) WHERE match_group_id IS NOT NULL;
CREATE INDEX campsites_match_group_idx ON campsites (match_group_id) WHERE match_group_id IS NOT NULL;

DROP INDEX IF EXISTS campground_vendor_refs_primary_uidx;
DROP INDEX IF EXISTS campsite_vendor_refs_primary_uidx;
ALTER TABLE campground_vendor_refs DROP COLUMN is_primary;
ALTER TABLE campsite_vendor_refs DROP COLUMN is_primary;

CREATE TABLE campground_matches (
  id               BIGSERIAL PRIMARY KEY,
  campground_a_id  BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  campground_b_id  BIGINT NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  heuristic        JSONB  NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campground_matches_order_check CHECK (campground_a_id < campground_b_id),
  CONSTRAINT campground_matches_heuristic_check CHECK (jsonb_typeof(heuristic) = 'object'),
  CONSTRAINT campground_matches_pair_uidx UNIQUE (campground_a_id, campground_b_id)
);
CREATE INDEX campground_matches_b_idx ON campground_matches (campground_b_id);

CREATE TABLE campsite_matches (
  id             BIGSERIAL PRIMARY KEY,
  campsite_a_id  BIGINT NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  campsite_b_id  BIGINT NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  heuristic      JSONB  NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campsite_matches_order_check CHECK (campsite_a_id < campsite_b_id),
  CONSTRAINT campsite_matches_heuristic_check CHECK (jsonb_typeof(heuristic) = 'object'),
  CONSTRAINT campsite_matches_pair_uidx UNIQUE (campsite_a_id, campsite_b_id)
);
CREATE INDEX campsite_matches_b_idx ON campsite_matches (campsite_b_id);

-- Canonical views: one row per match group, the richest member's columns whole.
-- Groups key on COALESCE(match_group_id, id); the matcher maintains
-- match_group_id = MIN(member id) per connected component.
CREATE MATERIALIZED VIEW campground_canonical AS
WITH scored AS (
  SELECT cg.*,
         COALESCE(cg.match_group_id, cg.id) AS group_key,
         (
           (cg.status IS NOT NULL)::int + (cg.kind IS NOT NULL)::int +
           (cg.short_description IS NOT NULL)::int + (cg.medium_description IS NOT NULL)::int +
           (cg.long_description IS NOT NULL)::int + (cg.reservation_url IS NOT NULL)::int +
           (cg.max_rv_length IS NOT NULL)::int + (cg.has_pull_through_sites IS NOT NULL)::int +
           (cg.big_rig_friendly IS NOT NULL)::int +
           (cg.location <> '{}'::jsonb)::int + (cg.amenities <> '{}'::jsonb)::int +
           (cg.links <> '[]'::jsonb)::int + (cg.photos <> '[]'::jsonb)::int +
           (cg.price <> '{}'::jsonb)::int + (cg.cell_service <> '{}'::jsonb)::int +
           (cg.management <> '{}'::jsonb)::int + (cg.contact <> '{}'::jsonb)::int +
           (cg.connections <> '{}'::jsonb)::int
         ) * 1000
         + (SELECT count(*) FROM campsites cs
            WHERE cs.campground_id = cg.id AND cs.deleted_at IS NULL) AS richness
  FROM campgrounds cg
  WHERE cg.deleted_at IS NULL
),
winners AS (
  SELECT DISTINCT ON (group_key) *
  FROM scored
  ORDER BY group_key, richness DESC, id ASC
)
SELECT w.*,
       ARRAY(SELECT s.id FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_ids,
       ARRAY(SELECT s.etl_source FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_sources
FROM winners w;

CREATE UNIQUE INDEX campground_canonical_id_uidx ON campground_canonical (id);
CREATE INDEX campground_canonical_group_idx ON campground_canonical (group_key);

CREATE MATERIALIZED VIEW campsite_canonical AS
WITH scored AS (
  SELECT cs.*,
         COALESCE(cs.match_group_id, cs.id) AS group_key,
         (
           (cs.loop_name IS NOT NULL)::int + (cs.latitude IS NOT NULL)::int +
           (cs.reservation_url IS NOT NULL)::int + (cs.kind_listed IS NOT NULL)::int +
           (cs.firepit IS NOT NULL)::int + (cs.picnic_table IS NOT NULL)::int +
           (cs.ada_accessible IS NOT NULL)::int + (cs.water_hookups IS NOT NULL)::int +
           (cs.electric_hookups IS NOT NULL)::int + (cs.sewer_hookups IS NOT NULL)::int +
           (cs.max_people IS NOT NULL)::int + (cs.max_cars IS NOT NULL)::int +
           (cs.pull_through IS NOT NULL)::int + (cs.driveway_length IS NOT NULL)::int +
           (cs.max_rv_length IS NOT NULL)::int +
           (COALESCE(cs.equipment, '[]'::jsonb) <> '[]'::jsonb)::int +
           (cs.schedule <> '{}'::jsonb)::int + (cs.price <> '{}'::jsonb)::int +
           (cs.photos <> '[]'::jsonb)::int
         ) AS richness
  FROM campsites cs
  WHERE cs.deleted_at IS NULL
),
winners AS (
  SELECT DISTINCT ON (group_key) *
  FROM scored
  ORDER BY group_key, richness DESC, id ASC
)
SELECT w.*,
       ARRAY(SELECT s.id FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_ids,
       ARRAY(SELECT s.etl_source FROM scored s WHERE s.group_key = w.group_key ORDER BY s.id) AS member_sources
FROM winners w;

CREATE UNIQUE INDEX campsite_canonical_id_uidx ON campsite_canonical (id);
CREATE INDEX campsite_canonical_group_idx ON campsite_canonical (group_key);
CREATE INDEX campsite_canonical_campground_idx ON campsite_canonical (campground_id);
