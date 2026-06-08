-- Drop booking_provider. RFC 0007's "PR 4" (BookingProviderAdapter dispatch
-- on the FK) never landed, and the FE shipped a workaround that reads
-- pois.provider_ref (JSONB) directly instead of joining through the FK.
-- The booking_provider table + pois.booking_provider_id column have been
-- nulls-only for the entire lifetime of the v2 schema. Cut the dead infra.
--
-- The column drop runs first so the table drop doesn't trip the FK.
ALTER TABLE pois DROP COLUMN IF EXISTS booking_provider_id;
DROP INDEX IF EXISTS pois_booking_provider_idx;
DROP TABLE IF EXISTS booking_provider;
