-- Counts the campsite rows V56 will rewrite, per shape, so the deploy can be sized.
-- ALTER COLUMN equipment SET NOT NULL takes an ACCESS EXCLUSIVE lock and scans the table.
-- Runs against a pre-V56 database.
SELECT
  count(*) AS total_live_rows,
  count(*) FILTER (WHERE jsonb_typeof(equipment) = 'array'
    AND EXISTS (SELECT 1 FROM jsonb_array_elements(equipment) e WHERE jsonb_typeof(e) <> 'string')) AS equipment_rewrites,
  count(*) FILTER (WHERE jsonb_typeof(photos) = 'array'
    AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(photos) p
      WHERE NOT jsonb_exists(p, 'url')
         OR jsonb_exists(p, 'large_url') OR jsonb_exists(p, 'medium_url')
         OR jsonb_exists(p, 'small_url') OR jsonb_exists(p, 'original_url')
    )) AS photos_rewrites,
  count(*) FILTER (WHERE source_payload->>'min_capacity' ~ '^[0-9]+$'
         OR source_payload->'_roadtrip_tags'->'capacity'->>'min' ~ '^[0-9]+$') AS min_people_backfill,
  count(*) FILTER (WHERE jsonb_exists(source_payload, 'description')) AS description_backfill,
  count(*) FILTER (WHERE jsonb_typeof(source_payload->'defined_attributes') = 'array'
    OR jsonb_typeof(source_payload->'_roadtrip_tags'->'attributes') = 'object') AS attributes_backfill
FROM campsites WHERE deleted_at IS NULL;
