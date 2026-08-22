-- BC rows recorded the inverse of the truth. Other readers survive that on their
-- own: loadOrFetch refetches anything past the 2h TTL. WatchAlertDispatcher's
-- dispatchInitial does not -- it reads persisted rows ungated, so a new watch
-- would fire a false "already available" off them and could close immediately.
-- Scoped to BC: PC and WA rows were already correct.

DELETE FROM availability a
USING campsites c
  LEFT JOIN campgrounds cg ON cg.id = c.campground_id
WHERE a.campsite_id = c.id
  AND 'aspira' IN (c.booking_provider, cg.booking_provider)
  AND (c.booking_provider_ref LIKE 'bc:%' OR cg.booking_provider_ref LIKE 'bc:%');
