DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM availability_watch
    WHERE target_dates IS NULL OR cardinality(target_dates) = 0
  ) THEN
    RAISE EXCEPTION 'availability_watch target_dates must be non-empty before V19 migration';
  END IF;
END $$;

ALTER TABLE availability_watch
  ADD COLUMN start_date DATE,
  ADD COLUMN end_date DATE;

UPDATE availability_watch
SET
  start_date = (
    SELECT min(value)
    FROM unnest(target_dates) AS dates(value)
  ),
  end_date = (
    SELECT max(value)
    FROM unnest(target_dates) AS dates(value)
  ) + min_nights;

ALTER TABLE availability_watch
  ALTER COLUMN start_date SET NOT NULL,
  ALTER COLUMN end_date SET NOT NULL,
  ADD CONSTRAINT availability_watch_date_window_check CHECK (end_date > start_date),
  DROP COLUMN target_dates,
  DROP COLUMN min_nights;
