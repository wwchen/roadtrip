-- PR4: per-POI cadence override. NULL = no override, so cadence derivation
-- falls through to the watch's own cadence_sec and ultimately GLOBAL_DEFAULT_SEC.
-- A hot ground gets a tight override (e.g. 30s); a sleepy ground stays on the
-- global default. Resolved per poller via the poller's representative poi_id.
ALTER TABLE pois ADD COLUMN cadence_override_sec INT
  CHECK (cadence_override_sec IS NULL OR cadence_override_sec >= 5);
