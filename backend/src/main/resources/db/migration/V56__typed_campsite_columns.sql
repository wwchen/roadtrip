-- Typed campsite columns. equipment becomes an array of strings, photos an
-- array of {url}, and three facts the drawer used to dig out of source_payload
-- get columns. Idempotent; canonical rows map to themselves.

ALTER TABLE campsites
  ADD COLUMN IF NOT EXISTS attributes JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS description TEXT,
  ADD COLUMN IF NOT EXISTS min_people INT;

ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_attributes_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_attributes_check CHECK (jsonb_typeof(attributes) = 'array');

ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_min_people_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_min_people_check CHECK (min_people IS NULL OR min_people >= 0);

UPDATE campsites SET equipment = COALESCE((
  SELECT jsonb_agg(s.label ORDER BY s.ord)
  FROM (
    SELECT e.ord,
           NULLIF(btrim(CASE WHEN jsonb_typeof(e.v) = 'string' THEN e.v #>> '{}'
                             WHEN jsonb_typeof(e.v) = 'object' THEN COALESCE(e.v->>'name', e.v->>'label', e.v->>'equipment_name') END), '') AS label
    FROM jsonb_array_elements(equipment) WITH ORDINALITY AS e(v, ord)
  ) s WHERE s.label IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(equipment) = 'array';

UPDATE campsites SET equipment = '[]'::jsonb WHERE equipment IS NULL OR jsonb_typeof(equipment) <> 'array';
ALTER TABLE campsites ALTER COLUMN equipment SET DEFAULT '[]'::jsonb;
ALTER TABLE campsites ALTER COLUMN equipment SET NOT NULL;

-- V38's check allowed NULL; the column is NOT NULL now, so it is array-only.
ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_equipment_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_equipment_check CHECK (jsonb_typeof(equipment) = 'array');

UPDATE campsites SET photos = COALESCE((
  SELECT jsonb_agg(jsonb_build_object('url', s.url) ORDER BY s.ord)
  FROM (
    SELECT p.ord, NULLIF(btrim(COALESCE(p.v->>'url', p.v->>'large_url', p.v->>'medium_url', p.v->>'small_url', p.v->>'original_url')), '') AS url
    FROM jsonb_array_elements(photos) WITH ORDINALITY AS p(v, ord)
    WHERE jsonb_typeof(p.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(photos) = 'array';

UPDATE campsites SET min_people = COALESCE(
    (source_payload->>'min_capacity')::int,
    (source_payload->'_roadtrip_tags'->'capacity'->>'min')::int)
WHERE min_people IS NULL
  AND (source_payload->>'min_capacity' ~ '^[0-9]+$' OR source_payload->'_roadtrip_tags'->'capacity'->>'min' ~ '^[0-9]+$');

UPDATE campsites SET description = NULLIF(btrim(regexp_replace(regexp_replace(source_payload->>'description', '<[^>]*>', ' ', 'g'), '\s+', ' ', 'g')), '')
WHERE description IS NULL AND jsonb_exists(source_payload, 'description');

UPDATE campsites SET attributes = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('name', s.name, 'value', s.value)) ORDER BY s.ord)
  FROM (
    SELECT a.ord, NULLIF(btrim(a.v->>'name'), '') AS name,
           NULLIF(btrim(COALESCE(a.v->'value_labels'->>0, a.v->>'value')), '') AS value
    FROM jsonb_array_elements(source_payload->'defined_attributes') WITH ORDINALITY AS a(v, ord)
    WHERE jsonb_typeof(a.v) = 'object'
  ) s WHERE s.name IS NOT NULL), '[]'::jsonb)
WHERE attributes = '[]'::jsonb AND jsonb_typeof(source_payload->'defined_attributes') = 'array';

UPDATE campsites SET attributes = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('name', initcap(replace(t.key, '_', ' ')), 'value', NULLIF(btrim(t.value), ''))) ORDER BY t.key)
  FROM jsonb_each_text(source_payload->'_roadtrip_tags'->'attributes') AS t(key, value)), '[]'::jsonb)
WHERE attributes = '[]'::jsonb AND jsonb_typeof(source_payload->'_roadtrip_tags'->'attributes') = 'object';
