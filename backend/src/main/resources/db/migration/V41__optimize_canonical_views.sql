-- Optimize canonical materialized views for large campsite catalogs.
--
-- The original views used correlated ARRAY() subqueries to collect
-- member_ids and member_sources per group winner. With 300k campsites
-- this is O(n²) — each winner re-scans the scored CTE. Replacing with
-- a grouped join + array_agg collapses it to a single pass.
--
-- Also adds an index on campgrounds.location for the geo-name matcher's
-- ST_DWithin cross-join, which was doing 60M point-to-point distance
-- calculations without spatial index support.

DROP MATERIALIZED VIEW IF EXISTS campsite_canonical;
DROP MATERIALIZED VIEW IF EXISTS campground_canonical;

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
),
members AS (
  SELECT s.group_key,
         array_agg(s.id ORDER BY s.id) AS member_ids,
         array_agg(s.data_source ORDER BY s.id) AS member_sources
  FROM scored s
  GROUP BY s.group_key
)
SELECT w.*, m.member_ids, m.member_sources
FROM winners w
JOIN members m ON m.group_key = w.group_key;

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
),
members AS (
  SELECT s.group_key,
         array_agg(s.id ORDER BY s.id) AS member_ids,
         array_agg(s.data_source ORDER BY s.id) AS member_sources
  FROM scored s
  GROUP BY s.group_key
)
SELECT w.*, m.member_ids, m.member_sources
FROM winners w
JOIN members m ON m.group_key = w.group_key;

CREATE UNIQUE INDEX campsite_canonical_id_uidx ON campsite_canonical (id);
CREATE INDEX campsite_canonical_group_idx ON campsite_canonical (group_key);

-- Functional index for the geo-name matcher's ST_DWithin cross-join.
-- Without this, every pair of the 11k campgrounds computes geography
-- distance on the fly from JSONB extraction.
CREATE INDEX campgrounds_location_geog_idx
  ON campgrounds USING gist (
    (ST_SetSRID(ST_MakePoint(
      (location->>'longitude')::double precision,
      (location->>'latitude')::double precision
    ), 4326)::geography)
  )
  WHERE deleted_at IS NULL
    AND (location->>'latitude') IS NOT NULL
    AND (location->>'longitude') IS NOT NULL;
