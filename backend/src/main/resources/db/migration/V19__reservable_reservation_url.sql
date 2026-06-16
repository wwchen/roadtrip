-- Persist stable per-reservable booking URLs in the catalog.
--
-- Date-scoped arrival/check-out URLs are availability-query context and do
-- not belong in the catalog row. This column stores the durable vendor page
-- ETL can derive from the reservable/provider identity.

ALTER TABLE reservables
  ADD COLUMN reservation_url TEXT;

UPDATE reservables
SET reservation_url = 'https://www.recreation.gov/camping/campsites/' || vendor_id
WHERE vendor = 'recgov'
  AND reservation_url IS NULL;

UPDATE reservables
SET reservation_url = concat(
    'https://',
    CASE vendor
      WHEN 'aspira_pc' THEN 'reservation.pc.gc.ca'
      WHEN 'aspira_bc' THEN 'camping.bcparks.ca'
      WHEN 'aspira_wa' THEN 'washington.goingtocamp.com'
    END,
    '/create-booking/results?',
    'transactionLocationId=', provider_ref->>'transactionLocationId',
    '&mapId=', provider_ref->>'mapId',
    CASE
      WHEN provider_ref ? 'resourceLocationId'
        THEN concat('&resourceLocationId=', provider_ref->>'resourceLocationId')
      ELSE ''
    END
  )
WHERE vendor IN ('aspira_pc', 'aspira_bc', 'aspira_wa')
  AND reservation_url IS NULL
  AND provider_ref ? 'transactionLocationId'
  AND provider_ref ? 'mapId';
