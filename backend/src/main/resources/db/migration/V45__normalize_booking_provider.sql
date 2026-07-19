-- Normalize booking_provider values to collapsed enum IDs and rewrite
-- booking_provider_ref to colon-delimited format.
--
-- Before: booking_provider stores per-tenant codes (aspira_bc, aspira_wa,
-- aspira_pc) and booking_provider_ref stores opaque external IDs.
-- After:  booking_provider stores the enum id (aspira, recgov, etc.) and
-- booking_provider_ref stores colon-delimited structured refs.

-- 1. Aspira campgrounds: collapse tenant code and prepend tenant to ref
--    New ref format: {tenant}:{transactionLocationId}:{mapId}:{resourceLocationId}
--    The booking_provider_ref currently holds only transactionLocationId, but
--    the full ref is written by the ETL on next run. For now, prefix with tenant.
UPDATE campgrounds
   SET booking_provider = 'aspira',
       booking_provider_ref = CASE
           WHEN booking_provider = 'aspira_bc' THEN 'bc:' || COALESCE(booking_provider_ref, '')
           WHEN booking_provider = 'aspira_wa' THEN 'wa:' || COALESCE(booking_provider_ref, '')
           WHEN booking_provider = 'aspira_pc' THEN 'pc:' || COALESCE(booking_provider_ref, '')
           ELSE booking_provider_ref
       END
 WHERE booking_provider IN ('aspira_bc', 'aspira_wa', 'aspira_pc');

-- 2. Aspira campsites: same treatment
UPDATE campsites
   SET booking_provider = 'aspira',
       booking_provider_ref = CASE
           WHEN booking_provider = 'aspira_bc' THEN 'bc:' || COALESCE(booking_provider_ref, '')
           WHEN booking_provider = 'aspira_wa' THEN 'wa:' || COALESCE(booking_provider_ref, '')
           WHEN booking_provider = 'aspira_pc' THEN 'pc:' || COALESCE(booking_provider_ref, '')
           ELSE booking_provider_ref
       END
 WHERE booking_provider IN ('aspira_bc', 'aspira_wa', 'aspira_pc');

-- 3. ReserveAmerica: collapse any tenant-specific codes
--    (e.g. alberta-provincial → reserveamerica)
UPDATE campgrounds
   SET booking_provider = 'reserveamerica'
 WHERE booking_provider LIKE '%reserveamerica%'
   AND booking_provider <> 'reserveamerica';

UPDATE campsites
   SET booking_provider = 'reserveamerica'
 WHERE booking_provider LIKE '%reserveamerica%'
   AND booking_provider <> 'reserveamerica';

-- 4. ReserveCalifornia: normalize any variant spelling
UPDATE campgrounds
   SET booking_provider = 'reservecalifornia'
 WHERE booking_provider LIKE '%reservecalifornia%'
   AND booking_provider <> 'reservecalifornia';

UPDATE campsites
   SET booking_provider = 'reservecalifornia'
 WHERE booking_provider LIKE '%reservecalifornia%'
   AND booking_provider <> 'reservecalifornia';
