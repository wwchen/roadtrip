-- Geometry provenance: how each campground's pin was found — exact, fuzzy or
-- parent-park match, against which geometry feed, at which name and score.
-- Aspira-backed ETLs write it; every other provider leaves it NULL.
--
-- No backfill. `make data-import` fills the column on the next run of the three
-- Aspira rows, which is a deploy step rather than a migration concern.

ALTER TABLE campgrounds
  ADD COLUMN IF NOT EXISTS geometry_provenance JSONB;

-- Every other JSONB column on the table is shape-checked; a scalar or an array
-- here would read back as "no provenance" rather than failing.
ALTER TABLE campgrounds DROP CONSTRAINT IF EXISTS campgrounds_geometry_provenance_check;
ALTER TABLE campgrounds
  ADD CONSTRAINT campgrounds_geometry_provenance_check
  CHECK (geometry_provenance IS NULL OR jsonb_typeof(geometry_provenance) = 'object');

-- The review query filters on the match kind and only a minority of rows carry
-- the column at all, so the index is partial.
CREATE INDEX IF NOT EXISTS campgrounds_geometry_provenance_match_kind_idx
  ON campgrounds ((geometry_provenance->>'match_kind'))
  WHERE geometry_provenance IS NOT NULL;
