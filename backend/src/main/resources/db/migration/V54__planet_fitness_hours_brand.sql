-- Promote two OSM tags the ETL was dropping into canonical columns.
--
-- `payload` holds the whole Overpass element, so hours and brand survived the
-- import but only inside the tag bag. Everything that reads a gym goes through
-- `to_jsonb(planet_fitness_locations)`, which carries columns and not tags, so
-- neither fact ever reached a caller. `name`, `phone` and `info_url` were
-- already promoted the same way; these two were the gap.
ALTER TABLE planet_fitness_locations
  ADD COLUMN opening_hours TEXT,
  ADD COLUMN brand         TEXT;

-- Backfill from the payload already on disk, so the fix lands without waiting
-- for the next Overpass poll. `operator` is OSM's fallback spelling of `brand`.
UPDATE planet_fitness_locations
SET opening_hours = NULLIF(btrim(payload -> 'tags' ->> 'opening_hours'), ''),
    brand = COALESCE(
      NULLIF(btrim(payload -> 'tags' ->> 'brand'), ''),
      NULLIF(btrim(payload -> 'tags' ->> 'operator'), '')
    )
WHERE jsonb_typeof(payload -> 'tags') = 'object';
