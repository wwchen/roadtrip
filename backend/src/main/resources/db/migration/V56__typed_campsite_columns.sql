-- Typed campsite columns, plus canonicalized equipment and photos so legacy
-- rows decode strictly in the window between migrate and `make data-import`.
-- No backfill: the import right after deploy rewrites every live row.
-- equipment stays nullable; NOT NULL waits for a later migration, once a
-- rollback can no longer put the old jar back.

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
WHERE jsonb_typeof(equipment) = 'array'
  AND EXISTS (SELECT 1 FROM jsonb_array_elements(equipment) e WHERE jsonb_typeof(e) <> 'string');

UPDATE campsites SET equipment = '[]'::jsonb WHERE equipment IS NULL OR jsonb_typeof(equipment) <> 'array';
ALTER TABLE campsites ALTER COLUMN equipment SET DEFAULT '[]'::jsonb;

ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_equipment_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_equipment_check CHECK (equipment IS NULL OR jsonb_typeof(equipment) = 'array');

UPDATE campsites SET photos = COALESCE((
  SELECT jsonb_agg(jsonb_build_object('url', s.url) ORDER BY s.ord)
  FROM (
    SELECT p.ord,
           NULLIF(btrim(COALESCE(CASE WHEN jsonb_typeof(p.v->'url') = 'string' THEN p.v->>'url' END,
                                 p.v->>'large_url', p.v->>'medium_url', p.v->>'small_url', p.v->>'original_url')), '') AS url
    FROM jsonb_array_elements(photos) WITH ORDINALITY AS p(v, ord)
    WHERE jsonb_typeof(p.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(photos) = 'array'
  AND EXISTS (
    SELECT 1 FROM jsonb_array_elements(photos) p
    WHERE jsonb_typeof(p) <> 'object'
       OR jsonb_typeof(p->'url') <> 'string'
       OR jsonb_exists(p, 'large_url') OR jsonb_exists(p, 'medium_url')
       OR jsonb_exists(p, 'small_url') OR jsonb_exists(p, 'original_url')
  );
