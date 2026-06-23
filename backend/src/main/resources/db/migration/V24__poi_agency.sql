-- Promote agency from properties JSONB to a first-class POI column.
-- Agency is display/filter metadata for every POI row, not a per-type
-- raw payload detail. Existing rows are backfilled from the previous
-- properties.agency location and then that duplicate JSONB key is removed;
-- new ETL imports set the column directly.
ALTER TABLE pois ADD COLUMN IF NOT EXISTS agency text NULL;

UPDATE pois
SET agency = properties ->> 'agency'
WHERE agency IS NULL
  AND properties ? 'agency';

UPDATE pois
SET properties = properties - 'agency'
WHERE properties ? 'agency';
