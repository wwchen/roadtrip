-- Aspira map, map-link, and resource codes were classified with the sense
-- inverted (0 read as no-data, nonzero read as available), so every cached
-- Aspira row records the opposite of what upstream said. Availability rows
-- store the interpreted status, not the upstream code, so they cannot be
-- reclassified in place. Drop them and let the loader repopulate on the next
-- poll or drawer request.
--
-- Dispatch keys off the campground's booking provider (a BC Parks campsite
-- carries data_provider 'bcparks-strapi' and often no booking_provider of its
-- own), so match on either side of the link.

DELETE FROM availability a
USING campsites c
  LEFT JOIN campgrounds cg ON cg.id = c.campground_id
WHERE a.campsite_id = c.id
  AND 'aspira' IN (c.booking_provider, cg.booking_provider);
