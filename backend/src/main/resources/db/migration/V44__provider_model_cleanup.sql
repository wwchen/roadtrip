-- Provider model cleanup.
--
-- Replaces the indirection through vendor_refs with direct provider columns
-- on campgrounds and campsites. Each row now carries:
--   data_provider      — which ETL populated it
--   data_provider_ref  — external ID within that ETL/data source
--   booking_provider   — who handles reservations (nullable)
--   booking_provider_ref — external ID at the booking provider (nullable)
--
-- This migration is DESTRUCTIVE: it drops vendor_refs, the join tables,
-- and the canonical materialized views. A re-ETL is required after applying.

-- 1. Drop materialized views (they reference columns we're about to change)
DROP MATERIALIZED VIEW IF EXISTS campsite_canonical;
DROP MATERIALIZED VIEW IF EXISTS campground_canonical;

-- 2. Rename data_source -> data_provider on both tables
ALTER TABLE campgrounds RENAME COLUMN data_source TO data_provider;
ALTER TABLE campsites RENAME COLUMN data_source TO data_provider;

-- 3. Add new provider columns to campgrounds
ALTER TABLE campgrounds
  ADD COLUMN data_provider_ref TEXT,
  ADD COLUMN booking_provider TEXT,
  ADD COLUMN booking_provider_ref TEXT;

-- 4. Add new provider columns to campsites
ALTER TABLE campsites
  ADD COLUMN data_provider_ref TEXT,
  ADD COLUMN booking_provider TEXT,
  ADD COLUMN booking_provider_ref TEXT;

-- 5. Backfill data_provider_ref from the primary vendor_ref
UPDATE campgrounds cg
   SET data_provider_ref = vr.external_id
  FROM vendor_refs vr
 WHERE vr.id = cg.primary_vendor_ref_id;

UPDATE campsites cs
   SET data_provider_ref = vr.external_id
  FROM vendor_refs vr
 WHERE vr.id = cs.primary_vendor_ref_id;

-- 6. Backfill booking_provider/booking_provider_ref from non-primary vendor_refs
UPDATE campgrounds cg
   SET booking_provider = booking.vendor,
       booking_provider_ref = booking.external_id
  FROM (
    SELECT DISTINCT ON (cvr.campground_id)
      cvr.campground_id,
      vr.vendor,
      vr.external_id
    FROM campground_vendor_refs cvr
    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
    JOIN campgrounds cg2 ON cg2.id = cvr.campground_id
    WHERE vr.vendor <> cg2.data_provider
      AND vr.entity_type = 'campground'
      AND vr.deleted_at IS NULL
    ORDER BY cvr.campground_id, cvr.vendor_ref_id ASC
  ) booking
 WHERE booking.campground_id = cg.id;

UPDATE campsites cs
   SET booking_provider = booking.vendor,
       booking_provider_ref = booking.external_id
  FROM (
    SELECT DISTINCT ON (cvr.campsite_id)
      cvr.campsite_id,
      vr.vendor,
      vr.external_id
    FROM campsite_vendor_refs cvr
    JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
    JOIN campsites cs2 ON cs2.id = cvr.campsite_id
    WHERE vr.vendor <> cs2.data_provider
      AND vr.entity_type = 'campsite'
      AND vr.deleted_at IS NULL
    ORDER BY cvr.campsite_id, cvr.vendor_ref_id ASC
  ) booking
 WHERE booking.campsite_id = cs.id;

-- 7. Make data_provider_ref NOT NULL (every row must have one)
ALTER TABLE campgrounds ALTER COLUMN data_provider_ref SET NOT NULL;
ALTER TABLE campsites ALTER COLUMN data_provider_ref SET NOT NULL;

-- 8. Drop old columns and constraints
ALTER TABLE campgrounds DROP COLUMN primary_vendor_ref_id;
ALTER TABLE campsites DROP COLUMN primary_vendor_ref_id;

-- 9. Drop old indexes that referenced removed columns/tables
DROP INDEX IF EXISTS campgrounds_data_source_primary_ref_uidx;
DROP INDEX IF EXISTS campsites_data_source_primary_ref_uidx;

-- 10. Create new unique indexes
CREATE UNIQUE INDEX campgrounds_provider_uidx
  ON campgrounds (data_provider, data_provider_ref)
  WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX campsites_provider_uidx
  ON campsites (data_provider, data_provider_ref)
  WHERE deleted_at IS NULL;

-- 11. Drop join tables and vendor_refs
DROP TABLE IF EXISTS campground_vendor_refs CASCADE;
DROP TABLE IF EXISTS campsite_vendor_refs CASCADE;
DROP TABLE IF EXISTS vendor_refs CASCADE;
