-- BC Parks resource rows were classified with the code sense inverted (0 read
-- as no-data, nonzero as available), so every cached BC row records the
-- opposite of what upstream said. Availability rows store the interpreted
-- status, not the upstream code, so they cannot be reclassified in place.
--
-- Scoped to BC on purpose. Parks Canada and Washington resource rows already
-- went through the 0-is-bookable classifier, so their bookability was correct;
-- only code 2 changes (reserved -> closed), which is not a bookable transition
-- and heals on the next poll. Deleting them would make the next poll treat
-- every cell as first-sight, and AvailabilityRepo.recordObservations emits a
-- CellTransition whenever no prior row exists -- which WatchAlertDispatcher
-- turns into an opening alert. Narrow the blast radius to the rows that are
-- actually wrong.
--
-- Park-rollup and map-link observations never reach this table
-- (AvailabilityRunService drops observations with a null campsite id), so only
-- per-campsite rows need clearing.

DELETE FROM availability a
USING campsites c
  LEFT JOIN campgrounds cg ON cg.id = c.campground_id
WHERE a.campsite_id = c.id
  AND 'aspira' IN (c.booking_provider, cg.booking_provider)
  AND (c.booking_provider_ref LIKE 'bc:%' OR cg.booking_provider_ref LIKE 'bc:%');
