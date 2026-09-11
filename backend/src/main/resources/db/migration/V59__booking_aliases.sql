-- Booking aliases: one primary booking ref per row, plus other vendors' identities for the same inventory.

ALTER TABLE campgrounds
  ADD COLUMN IF NOT EXISTS booking_aliases JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE campsites
  ADD COLUMN IF NOT EXISTS booking_aliases JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE campgrounds DROP CONSTRAINT IF EXISTS campgrounds_booking_aliases_check;
ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_booking_aliases_check;

ALTER TABLE campgrounds
  ADD CONSTRAINT campgrounds_booking_aliases_check CHECK (jsonb_typeof(booking_aliases) = 'array');
ALTER TABLE campsites
  ADD CONSTRAINT campsites_booking_aliases_check CHECK (jsonb_typeof(booking_aliases) = 'array');

-- Campflare rows carrying rec.gov as their primary become Campflare primary with rec.gov as an alias;
-- the WHERE already excludes a rewritten row, so a rerun changes nothing.
UPDATE campgrounds
SET booking_aliases = jsonb_build_array(jsonb_build_object('provider', 'recgov', 'ref', booking_provider_ref)),
    booking_provider = 'campflare',
    booking_provider_ref = data_provider_ref
WHERE data_provider = 'campflare'
  AND booking_provider = 'recgov'
  AND booking_provider_ref IS NOT NULL;

UPDATE campsites
SET booking_aliases = jsonb_build_array(jsonb_build_object('provider', 'recgov', 'ref', booking_provider_ref)),
    booking_provider = 'campflare',
    booking_provider_ref = data_provider_ref
WHERE data_provider = 'campflare'
  AND booking_provider = 'recgov'
  AND booking_provider_ref IS NOT NULL;
