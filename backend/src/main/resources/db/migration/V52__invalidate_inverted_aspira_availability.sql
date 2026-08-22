-- BC Parks rows were classified with the code sense inverted, and store the
-- interpreted status rather than the upstream code, so they cannot be fixed in
-- place. Scoped to BC: PC and WA already used the correct classifier, and
-- deleting their rows would make the next poll re-alert on every open cell.

DELETE FROM availability a
USING campsites c
  LEFT JOIN campgrounds cg ON cg.id = c.campground_id
WHERE a.campsite_id = c.id
  AND 'aspira' IN (c.booking_provider, cg.booking_provider)
  AND (c.booking_provider_ref LIKE 'bc:%' OR cg.booking_provider_ref LIKE 'bc:%');
