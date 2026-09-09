-- Counts the campsite rows V56 will rewrite, per shape, so the deploy can be sized.
SELECT
  count(*) FILTER (WHERE jsonb_typeof(equipment) = 'array' AND EXISTS (SELECT 1 FROM jsonb_array_elements(equipment) e WHERE jsonb_typeof(e) = 'object')) AS equipment_objects,
  count(*) FILTER (WHERE jsonb_typeof(photos) = 'array' AND EXISTS (SELECT 1 FROM jsonb_array_elements(photos) p WHERE p ? 'large_url' OR p ? 'medium_url' OR p ? 'small_url' OR p ? 'original_url')) AS photos_vendor_keys,
  count(*) FILTER (WHERE source_payload ? 'min_capacity' OR source_payload->'_roadtrip_tags' ? 'capacity') AS min_people_backfill,
  count(*) FILTER (WHERE source_payload ? 'description') AS description_backfill,
  count(*) FILTER (WHERE jsonb_typeof(source_payload->'defined_attributes') = 'array' OR jsonb_typeof(source_payload->'_roadtrip_tags'->'attributes') = 'object') AS attributes_backfill
FROM campsites WHERE deleted_at IS NULL;
