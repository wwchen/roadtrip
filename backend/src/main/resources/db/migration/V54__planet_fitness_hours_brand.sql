-- Two OSM tags the ETL was dropping. Readers go through
-- `to_jsonb(planet_fitness_locations)`, which carries columns and not tags, so
-- hours and brand survived the import but reached no caller.
ALTER TABLE planet_fitness_locations
  ADD COLUMN opening_hours TEXT,
  ADD COLUMN brand         TEXT;

-- Backfill from the payload already on disk, so the fix lands without waiting
-- for the next Overpass poll.
UPDATE planet_fitness_locations
SET opening_hours = NULLIF(btrim(payload -> 'tags' ->> 'opening_hours'), ''),
    brand = COALESCE(
      NULLIF(btrim(payload -> 'tags' ->> 'brand'), ''),
      NULLIF(btrim(payload -> 'tags' ->> 'operator'), '')
    )
WHERE jsonb_typeof(payload -> 'tags') = 'object';
