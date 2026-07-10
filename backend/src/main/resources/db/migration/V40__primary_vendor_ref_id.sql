-- Primary vendor_ref pointer for canonical catalog rows.
--
-- Every canonical campground/campsite belongs to exactly one owning
-- vendor_ref within its data_source (the vendor_ref where
-- vendor_refs.vendor = data_source). Today that link is only discoverable
-- via the many-to-many link tables (campground_vendor_refs /
-- campsite_vendor_refs), which forces the catalog writer to run a
-- per-record SELECT before every upsert.
--
-- Promoting the owning vendor_ref to a first-class column lets the
-- writer collapse "SELECT existing? then INSERT or UPDATE" into a single
-- INSERT ... ON CONFLICT (data_source, primary_vendor_ref_id) DO UPDATE
-- statement, which is a prerequisite for bulk (multi-row VALUES) upserts.
--
-- Backfill sources each row's primary vendor_ref from the existing link
-- table, matching on vendor_refs.vendor = data_source. If any row is left
-- without an owning ref we abort — the invariant "every catalog row has
-- a primary vendor_ref" is enforced by the writer today and we don't want
-- to lose that guarantee on the way through the migration.

ALTER TABLE campgrounds
  ADD COLUMN primary_vendor_ref_id BIGINT
    REFERENCES vendor_refs (id) ON DELETE RESTRICT;

ALTER TABLE campsites
  ADD COLUMN primary_vendor_ref_id BIGINT
    REFERENCES vendor_refs (id) ON DELETE RESTRICT;

UPDATE campgrounds cg
   SET primary_vendor_ref_id = link.vendor_ref_id
  FROM (
    SELECT DISTINCT ON (cvr.campground_id)
      cvr.campground_id,
      cvr.vendor_ref_id
    FROM campground_vendor_refs cvr
    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
    JOIN campgrounds cg2 ON cg2.id = cvr.campground_id
    WHERE vr.vendor = cg2.data_source
    ORDER BY cvr.campground_id, cvr.vendor_ref_id
  ) AS link
 WHERE link.campground_id = cg.id;

UPDATE campsites cs
   SET primary_vendor_ref_id = link.vendor_ref_id
  FROM (
    SELECT DISTINCT ON (cvr.campsite_id)
      cvr.campsite_id,
      cvr.vendor_ref_id
    FROM campsite_vendor_refs cvr
    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
    JOIN campsites cs2 ON cs2.id = cvr.campsite_id
    WHERE vr.vendor = cs2.data_source
    ORDER BY cvr.campsite_id, cvr.vendor_ref_id
  ) AS link
 WHERE link.campsite_id = cs.id;

DO $$
DECLARE
  orphan_campgrounds INT;
  orphan_campsites   INT;
BEGIN
  SELECT count(*) INTO orphan_campgrounds
    FROM campgrounds WHERE primary_vendor_ref_id IS NULL;
  SELECT count(*) INTO orphan_campsites
    FROM campsites   WHERE primary_vendor_ref_id IS NULL;
  IF orphan_campgrounds > 0 OR orphan_campsites > 0 THEN
    RAISE EXCEPTION
      'V40 backfill left rows without a primary_vendor_ref_id: campgrounds=%, campsites=%. '
      'This means one or more rows had no matching vendor_ref where vendor = data_source, '
      'which violates the invariant enforced by the catalog writer. Repair the data '
      'before applying this migration.',
      orphan_campgrounds, orphan_campsites;
  END IF;
END $$;

ALTER TABLE campgrounds
  ALTER COLUMN primary_vendor_ref_id SET NOT NULL;

ALTER TABLE campsites
  ALTER COLUMN primary_vendor_ref_id SET NOT NULL;

CREATE UNIQUE INDEX campgrounds_data_source_primary_ref_uidx
  ON campgrounds (data_source, primary_vendor_ref_id)
  WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX campsites_data_source_primary_ref_uidx
  ON campsites (data_source, primary_vendor_ref_id)
  WHERE deleted_at IS NULL;
