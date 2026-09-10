-- Typed campground bags, plus a parent_name column. Canonicalization only, so
-- every legacy shape in the catalog decodes strictly in the window between
-- migrate and `make data-import`; no backfill, the import right after deploy
-- rewrites every live row. Each UPDATE is guarded to be a no-op on a row the
-- typed repo wrote, which also makes the script idempotent.

ALTER TABLE campgrounds
  ADD COLUMN IF NOT EXISTS parent_name TEXT;

-- amenities and cell_service become arrays, so their object-shaped CHECKs go
-- first and the array-shaped ones land once the rows are rewritten.
ALTER TABLE campgrounds DROP CONSTRAINT IF EXISTS campgrounds_amenities_check;
ALTER TABLE campgrounds DROP CONSTRAINT IF EXISTS campgrounds_cell_service_check;

UPDATE campgrounds SET amenities = COALESCE((
  SELECT jsonb_agg(s.entry ORDER BY s.ord)
  FROM (
    SELECT e.ord,
           CASE
             WHEN e.k = ANY (ARRAY['camp_store', 'dump_station', 'electric_hookups', 'fires_allowed',
                                   'pets_allowed', 'sewer_hookups', 'showers', 'toilets', 'trash',
                                   'water', 'water_hookups', 'wifi'])
               THEN jsonb_strip_nulls(jsonb_build_object(
                      'key', e.k,
                      'present', CASE WHEN jsonb_typeof(e.v) = 'boolean' THEN e.v = 'true'::jsonb ELSE true END,
                      'detail', CASE WHEN e.k = 'toilets' AND jsonb_typeof(amenities->'toilet_kind') = 'string'
                                       THEN to_jsonb(amenities->>'toilet_kind') END))
             ELSE jsonb_build_object('key', 'other', 'present', true, 'detail', e.k)
           END AS entry
    FROM jsonb_each(amenities) WITH ORDINALITY AS e(k, v, ord)
    WHERE e.k <> 'toilet_kind'
      AND jsonb_typeof(e.v) <> 'null'
    UNION ALL
    SELECT 0,
           jsonb_build_object('key', 'toilets', 'present', true, 'detail', to_jsonb(amenities->>'toilet_kind'))
    WHERE jsonb_typeof(amenities->'toilet_kind') = 'string'
      AND NOT jsonb_exists(amenities, 'toilets')
  ) s), '[]'::jsonb)
WHERE jsonb_typeof(amenities) = 'object';

UPDATE campgrounds SET cell_service = COALESCE((
  SELECT jsonb_agg(s.entry ORDER BY s.ord)
  FROM (
    SELECT e.ord,
           jsonb_strip_nulls(jsonb_build_object(
             'carrier', e.k,
             'average', CASE WHEN jsonb_typeof(e.v) = 'number' THEN e.v
                             WHEN jsonb_typeof(e.v->'avg') = 'number' THEN e.v->'avg' END,
             'count', CASE WHEN jsonb_typeof(e.v->'count') = 'number' THEN e.v->'count' END)) AS entry
    FROM jsonb_each(cell_service) WITH ORDINALITY AS e(k, v, ord)
    WHERE e.k = ANY (ARRAY['verizon', 'att', 'tmobile', 'sprint', 'uscell'])
      AND (jsonb_typeof(e.v) = 'number' OR jsonb_typeof(e.v->'avg') = 'number')
  ) s), '[]'::jsonb)
WHERE jsonb_typeof(cell_service) = 'object';

UPDATE campgrounds SET amenities = '[]'::jsonb WHERE amenities IS NULL OR jsonb_typeof(amenities) <> 'array';
UPDATE campgrounds SET cell_service = '[]'::jsonb WHERE cell_service IS NULL OR jsonb_typeof(cell_service) <> 'array';

ALTER TABLE campgrounds ALTER COLUMN amenities SET DEFAULT '[]'::jsonb;
ALTER TABLE campgrounds ALTER COLUMN cell_service SET DEFAULT '[]'::jsonb;

ALTER TABLE campgrounds ADD CONSTRAINT campgrounds_amenities_check CHECK (jsonb_typeof(amenities) = 'array');
ALTER TABLE campgrounds ADD CONSTRAINT campgrounds_cell_service_check CHECK (jsonb_typeof(cell_service) = 'array');

-- metadata keeps activities, rating and last_updated; the provenance extras the
-- HTML-scraping vendors duplicated out of source_payload go away.
UPDATE campgrounds SET metadata = jsonb_strip_nulls(jsonb_build_object(
  'activities', CASE WHEN jsonb_typeof(metadata->'activities') = 'array' THEN (
                       SELECT jsonb_agg(a.v ORDER BY a.ord)
                       FROM jsonb_array_elements(metadata->'activities') WITH ORDINALITY AS a(v, ord)
                       WHERE jsonb_typeof(a.v) = 'string') END,
  'rating', CASE
              WHEN jsonb_typeof(metadata->'rating') = 'object' THEN metadata->'rating'
              WHEN jsonb_typeof(metadata#>'{rating_reviews,avg}') = 'number'
                   AND jsonb_typeof(metadata#>'{rating_reviews,count}') = 'number'
                THEN jsonb_build_object('average', metadata#>'{rating_reviews,avg}',
                                        'count', metadata#>'{rating_reviews,count}')
            END,
  'last_updated', CASE WHEN jsonb_typeof(metadata->'last_updated') = 'string' THEN metadata->'last_updated' END))
WHERE jsonb_typeof(metadata) = 'object'
  AND EXISTS (
    SELECT 1 FROM jsonb_object_keys(metadata) k
    WHERE k NOT IN ('activities', 'rating', 'last_updated'));

UPDATE campgrounds SET price = jsonb_strip_nulls(jsonb_build_object(
  'minimum', CASE WHEN jsonb_typeof(price->'minimum') = 'number' THEN price->'minimum' END,
  'maximum', CASE WHEN jsonb_typeof(price->'maximum') = 'number' THEN price->'maximum' END,
  'currency', CASE WHEN jsonb_typeof(price->'currency') = 'string' THEN price->'currency'
                   WHEN jsonb_typeof(price->'currency_code') = 'string' THEN price->'currency_code' END))
WHERE jsonb_typeof(price) = 'object'
  AND EXISTS (
    SELECT 1 FROM jsonb_object_keys(price) k
    WHERE k NOT IN ('minimum', 'maximum', 'currency'));

UPDATE campgrounds SET default_campsite_schedule = jsonb_strip_nulls(jsonb_build_object(
  'check_in', CASE WHEN jsonb_typeof(default_campsite_schedule->'check_in') = 'string'
                     THEN default_campsite_schedule->'check_in'
                   WHEN jsonb_typeof(default_campsite_schedule->'check_in_time') = 'string'
                     THEN default_campsite_schedule->'check_in_time' END,
  'check_out', CASE WHEN jsonb_typeof(default_campsite_schedule->'check_out') = 'string'
                      THEN default_campsite_schedule->'check_out'
                    WHEN jsonb_typeof(default_campsite_schedule->'check_out_time') = 'string'
                      THEN default_campsite_schedule->'check_out_time' END))
WHERE jsonb_typeof(default_campsite_schedule) = 'object'
  AND EXISTS (
    SELECT 1 FROM jsonb_object_keys(default_campsite_schedule) k
    WHERE k NOT IN ('check_in', 'check_out'));

UPDATE campgrounds SET alerts = COALESCE((
  SELECT jsonb_agg(s.entry ORDER BY s.ord)
  FROM (
    SELECT a.ord,
           jsonb_strip_nulls(jsonb_build_object(
             'title', CASE WHEN jsonb_typeof(a.v->'title') = 'string' THEN a.v->'title' END,
             'body', CASE WHEN jsonb_typeof(a.v->'body') = 'string' THEN a.v->'body'
                          WHEN jsonb_typeof(a.v->'content') = 'string' THEN a.v->'content' END,
             'ends_on', CASE WHEN jsonb_typeof(a.v->'ends_on') = 'string' THEN a.v->'ends_on'
                             WHEN jsonb_typeof(a.v->'end_date') = 'string' THEN a.v->'end_date' END,
             'source_url', CASE WHEN jsonb_typeof(a.v->'source_url') = 'string' THEN a.v->'source_url' END)) AS entry
    FROM jsonb_array_elements(alerts) WITH ORDINALITY AS a(v, ord)
    WHERE jsonb_typeof(a.v) = 'object'
      AND (jsonb_typeof(a.v->'body') = 'string' OR jsonb_typeof(a.v->'content') = 'string')
  ) s), '[]'::jsonb)
WHERE jsonb_typeof(alerts) = 'array'
  AND EXISTS (
    SELECT 1 FROM jsonb_array_elements(alerts) a
    WHERE jsonb_typeof(a) <> 'object'
       OR jsonb_typeof(a->'body') IS DISTINCT FROM 'string'
       OR jsonb_exists(a, 'content')
       OR jsonb_exists(a, 'end_date'));
