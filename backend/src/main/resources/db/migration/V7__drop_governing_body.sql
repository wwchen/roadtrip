-- Drop governing_body. RFC 0007 successor: the YAML registry no longer
-- carries a governing_body taxonomy. Per-POI provenance lives in `source`
-- (data_source slug) + the data_provider booking_provider FK; the
-- governing_body axis turned out to be redundant for filtering and never
-- got referenced in the FE.
--
-- The column drop runs first so the table drop doesn't trip the FK.
ALTER TABLE pois DROP COLUMN IF EXISTS governing_body_id;
DROP TABLE IF EXISTS governing_body;
