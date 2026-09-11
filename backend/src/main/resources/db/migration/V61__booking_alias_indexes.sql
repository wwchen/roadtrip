-- The alias indexes, built CONCURRENTLY so neither catalog table is locked for
-- the build. Postgres refuses CONCURRENTLY inside a transaction, so this script
-- runs outside one — see the sibling V61__booking_alias_indexes.sql.conf.

CREATE INDEX CONCURRENTLY IF NOT EXISTS campgrounds_booking_aliases_gin ON campgrounds USING GIN (booking_aliases);
CREATE INDEX CONCURRENTLY IF NOT EXISTS campsites_booking_aliases_gin ON campsites USING GIN (booking_aliases);

-- Lets the primary-or-alias OR in RefLinkRepo plan as a BitmapOr over both indexes.
CREATE INDEX CONCURRENTLY IF NOT EXISTS campgrounds_booking_provider_ref_idx ON campgrounds (booking_provider, booking_provider_ref);
CREATE INDEX CONCURRENTLY IF NOT EXISTS campsites_booking_provider_ref_idx ON campsites (booking_provider, booking_provider_ref);
