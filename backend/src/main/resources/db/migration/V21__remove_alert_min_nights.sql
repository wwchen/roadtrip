-- Compatibility cleanup for databases that still have the deprecated alert
-- table. Current master drops that legacy table in V19, so this is a no-op on
-- fresh schemas.
DO $$
BEGIN
  IF to_regclass('public.alerts') IS NULL THEN
    RETURN;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'alerts'
      AND column_name = 'min_nights'
  ) THEN
    -- Convert the deprecated alert model's range + stay-length pair into the
    -- new exact half-open date window.
    UPDATE alerts
    SET end_date = start_date + GREATEST(min_nights, 1)
    WHERE end_date <> start_date + GREATEST(min_nights, 1);

    ALTER TABLE alerts
      DROP COLUMN min_nights;
  END IF;
END $$;
