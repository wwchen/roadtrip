-- First-class normalized reservable traits.
--
-- raw remains the upstream audit payload. tags is the provider-neutral,
-- queryable projection ETLs build from readable catalog fields or provider
-- dictionaries: capacity, equipment, and named attributes.

ALTER TABLE reservables
  ADD COLUMN tags JSONB;

CREATE INDEX reservables_tags_gin_idx ON reservables USING GIN (tags);
