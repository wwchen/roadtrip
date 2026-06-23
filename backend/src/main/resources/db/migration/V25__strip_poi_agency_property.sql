-- Post-rollout cleanup for V24__poi_agency.sql.
--
-- V24 kept properties.agency during the rolling deploy so old backend pods
-- could still serve the legacy POI detail shape. New readers use pois.agency,
-- and the ETLs no longer write agency into the per-type JSONB properties.
-- Strip only the duplicate key; leave the rest of each properties payload
-- untouched.
UPDATE pois
SET properties = properties - 'agency'
WHERE properties ? 'agency';
