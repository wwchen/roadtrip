-- Promote subcategory from properties JSONB to a first-class column so
-- POST /api/pois can project it cheaply without a per-row JSONB lookup.
-- Subcategory is the FE's "circle color sub-bucket" for campgrounds
-- (federal / state / local / provincial); other POI categories don't use it.
--
-- Backfill from properties->>'subcategory' for existing rows. The ETL
-- continues to write subcategory into the JSONB blob too — single-source-
-- of-truth cleanup is a follow-up.
ALTER TABLE pois ADD COLUMN IF NOT EXISTS subcategory text NULL;

UPDATE pois
SET subcategory = properties ->> 'subcategory'
WHERE subcategory IS NULL
  AND properties ? 'subcategory';
