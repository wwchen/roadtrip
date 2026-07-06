-- Aspira resource-level availability code interpretation changed in the
-- adapter: resource code 0 is bookable, nonzero codes are not. Existing
-- availability rows store the interpreted status, not the upstream code, so
-- they cannot be reclassified in place. Drop Aspira cache/history rows and let
-- the loader repopulate them on the next poll or drawer request.

DELETE FROM availability a
USING reservables r
WHERE a.reservable_id = r.id
  AND r.vendor LIKE 'aspira\_%' ESCAPE '\';
