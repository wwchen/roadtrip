-- Convert the deprecated alert model's range + stay-length pair into the new
-- exact half-open date window. The old "any N nights within this range" shape
-- cannot be represented after removing min_nights; preserving the original
-- stay length from start_date is the least lossy migration for active rows.
UPDATE alerts
SET end_date = start_date + GREATEST(min_nights, 1)
WHERE end_date <> start_date + GREATEST(min_nights, 1);

ALTER TABLE alerts
  DROP COLUMN min_nights;
