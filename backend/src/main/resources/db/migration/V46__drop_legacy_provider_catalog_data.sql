-- Drop legacy campground/campsite catalog data after the provider-ref cleanup.
--
-- V44/V45 changed provider identity shape, and existing rows may still carry
-- old terminal source slugs or legacy external refs. The catalog is rebuildable
-- from ETL, so reset campground data instead of translating those rows forward.

DELETE FROM pois
 WHERE poi_type = 'campground';

DELETE FROM campgrounds;

DELETE FROM availability_watch aw
 WHERE NOT EXISTS (
   SELECT 1
     FROM availability_watch_target awt
    WHERE awt.watch_id = aw.id
 );
