-- Promote agency from properties JSONB to a first-class POI column.
-- Agency is display/filter metadata for every POI row, not a per-type raw
-- payload detail. This migration adds the column and backfills it from the
-- existing properties.agency location; the duplicate JSONB key is left in
-- place so a rolling deploy can serve the legacy shape from older pods
-- while newer pods read pois.agency. A follow-up migration strips
-- properties.agency once every reader is on the new shape.
ALTER TABLE pois ADD COLUMN IF NOT EXISTS agency text NULL;

UPDATE pois
SET agency = properties ->> 'agency'
WHERE agency IS NULL
  AND properties ? 'agency';
