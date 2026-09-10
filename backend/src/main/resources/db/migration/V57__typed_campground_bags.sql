-- Typed campground bags, plus a parent_name column: canonicalization only, so every
-- legacy shape decodes strictly between migrate and the import that follows deploy.

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
                                       THEN to_jsonb(amenities->>'toilet_kind')
                                     WHEN jsonb_typeof(e.v) = 'string' THEN e.v END))
             ELSE jsonb_build_object(
                    'key', 'other',
                    'present', CASE WHEN jsonb_typeof(e.v) = 'boolean' THEN e.v = 'true'::jsonb ELSE true END,
                    'detail', upper(left(replace(e.k, '_', ' '), 1)) || substr(replace(e.k, '_', ' '), 2))
           END AS entry
    FROM jsonb_each(amenities) WITH ORDINALITY AS e(k, v, ord)
    WHERE e.k <> 'toilet_kind'
      AND jsonb_typeof(e.v) <> 'null'
    UNION ALL
    SELECT 0,
           jsonb_build_object('key', 'toilets', 'present', true, 'detail', to_jsonb(amenities->>'toilet_kind'))
    WHERE jsonb_typeof(amenities->'toilet_kind') = 'string'
      AND COALESCE(jsonb_typeof(amenities->'toilets'), 'null') = 'null'
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

UPDATE campgrounds SET amenities = '[]'::jsonb WHERE jsonb_typeof(amenities) <> 'array';
UPDATE campgrounds SET cell_service = '[]'::jsonb WHERE jsonb_typeof(cell_service) <> 'array';

ALTER TABLE campgrounds ALTER COLUMN amenities SET DEFAULT '[]'::jsonb;
ALTER TABLE campgrounds ALTER COLUMN cell_service SET DEFAULT '[]'::jsonb;

-- NOT VALID first, so the validating scan runs without ACCESS EXCLUSIVE.
ALTER TABLE campgrounds ADD CONSTRAINT campgrounds_amenities_check CHECK (jsonb_typeof(amenities) = 'array') NOT VALID;
ALTER TABLE campgrounds ADD CONSTRAINT campgrounds_cell_service_check CHECK (jsonb_typeof(cell_service) = 'array') NOT VALID;
ALTER TABLE campgrounds VALIDATE CONSTRAINT campgrounds_amenities_check;
ALTER TABLE campgrounds VALIDATE CONSTRAINT campgrounds_cell_service_check;

-- metadata keeps activities, rating and last_updated; the provenance extras the
-- HTML-scraping vendors duplicated out of source_payload go away.
UPDATE campgrounds SET metadata = jsonb_strip_nulls(jsonb_build_object(
  'activities', CASE WHEN jsonb_typeof(metadata->'activities') = 'array' THEN (
                       SELECT jsonb_agg(a.v ORDER BY a.ord)
                       FROM jsonb_array_elements(metadata->'activities') WITH ORDINALITY AS a(v, ord)
                       WHERE jsonb_typeof(a.v) = 'string') END,
  'rating', CASE
              WHEN jsonb_typeof(metadata#>'{rating,average}') = 'number'
                   AND jsonb_typeof(metadata#>'{rating,count}') = 'number'
                THEN jsonb_build_object('average', metadata#>'{rating,average}',
                                        'count', metadata#>'{rating,count}')
              WHEN jsonb_typeof(metadata#>'{rating_reviews,avg}') = 'number'
                   AND jsonb_typeof(metadata#>'{rating_reviews,count}') = 'number'
                THEN jsonb_build_object('average', metadata#>'{rating_reviews,avg}',
                                        'count', metadata#>'{rating_reviews,count}')
            END,
  'last_updated', CASE WHEN jsonb_typeof(metadata->'last_updated') = 'string' THEN metadata->'last_updated' END))
WHERE jsonb_typeof(metadata) = 'object'
  AND (
    EXISTS (
      SELECT 1 FROM jsonb_object_keys(metadata) k
      WHERE k NOT IN ('activities', 'rating', 'last_updated'))
    OR (jsonb_exists(metadata, 'activities') AND jsonb_typeof(metadata->'activities') <> 'array')
    OR (jsonb_typeof(metadata->'activities') = 'array'
        AND EXISTS (
          SELECT 1 FROM jsonb_array_elements(metadata->'activities') a
          WHERE jsonb_typeof(a) <> 'string'))
    OR (jsonb_exists(metadata, 'last_updated') AND jsonb_typeof(metadata->'last_updated') <> 'string')
    OR (jsonb_exists(metadata, 'rating')
        AND (jsonb_typeof(metadata#>'{rating,average}') IS DISTINCT FROM 'number'
             OR jsonb_typeof(metadata#>'{rating,count}') IS DISTINCT FROM 'number')));

UPDATE campgrounds SET price = jsonb_strip_nulls(jsonb_build_object(
  'minimum', CASE WHEN jsonb_typeof(price->'minimum') = 'number' THEN price->'minimum' END,
  'maximum', CASE WHEN jsonb_typeof(price->'maximum') = 'number' THEN price->'maximum' END,
  'currency', CASE WHEN jsonb_typeof(price->'currency_code') = 'string' THEN price->'currency_code'
                   WHEN jsonb_typeof(price->'currency') = 'string' THEN price->'currency' END))
WHERE jsonb_typeof(price) = 'object'
  AND (
    EXISTS (
      SELECT 1 FROM jsonb_object_keys(price) k
      WHERE k NOT IN ('minimum', 'maximum', 'currency'))
    OR (jsonb_exists(price, 'minimum') AND jsonb_typeof(price->'minimum') <> 'number')
    OR (jsonb_exists(price, 'maximum') AND jsonb_typeof(price->'maximum') <> 'number')
    OR (jsonb_exists(price, 'currency') AND jsonb_typeof(price->'currency') <> 'string'));

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
  AND (
    EXISTS (
      SELECT 1 FROM jsonb_object_keys(default_campsite_schedule) k
      WHERE k NOT IN ('check_in', 'check_out'))
    OR (jsonb_exists(default_campsite_schedule, 'check_in')
        AND jsonb_typeof(default_campsite_schedule->'check_in') <> 'string')
    OR (jsonb_exists(default_campsite_schedule, 'check_out')
        AND jsonb_typeof(default_campsite_schedule->'check_out') <> 'string'));

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
       OR jsonb_typeof(a->'title') NOT IN ('string', 'null')
       OR jsonb_typeof(a->'ends_on') NOT IN ('string', 'null')
       OR jsonb_typeof(a->'source_url') NOT IN ('string', 'null')
       OR jsonb_exists(a, 'content')
       OR jsonb_exists(a, 'end_date'));
