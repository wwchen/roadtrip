-- Backfill the canonical management->>'agency' key for Campflare campgrounds.
--
-- Campflare ships management as {agency_name, agency_id, agency_website}, but
-- the serving query (PoiServingRepo) and every other vendor ETL read
-- management->>'agency'. Rows written before CampflareCampgroundsEtl started
-- promoting agency_name therefore serve agency = NULL, so the map legend
-- buckets them all under "Uncategorized" even though the detail drawer still
-- shows an agency (it falls back to raw.management.agency_name from
-- source_payload, which the slim map payload does not carry).
--
-- The ETL fix only reaches existing rows on the next import, so promote the
-- key in place here. Upstream keys are preserved alongside the new one, which
-- matches what the ETL writes. Rows whose upstream names no agency (~1.4% of
-- Campflare) stay NULL and keep rendering under "Uncategorized" by design.

UPDATE campgrounds
   SET management = management || jsonb_build_object('agency', TRIM(management ->> 'agency_name'))
 WHERE data_provider = 'campflare'
   AND management ? 'agency_name'
   AND NOT management ? 'agency'
   AND NULLIF(TRIM(management ->> 'agency_name'), '') IS NOT NULL;
