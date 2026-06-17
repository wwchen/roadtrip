CREATE TYPE availability_status AS ENUM (
  'first_come',
  'reserved',
  'available',
  'closed',
  'unknown'
);

ALTER TABLE availability_snapshot
  DROP CONSTRAINT IF EXISTS reservable_availability_log_status_check;

ALTER TABLE availability_snapshot
  ALTER COLUMN status TYPE availability_status
  USING (
    CASE
      WHEN status = 'available' THEN 'available'
      WHEN status = 'booked' THEN 'reserved'
      WHEN status = 'closed' THEN 'closed'
      WHEN status = 'partial' AND available THEN 'available'
      WHEN status = 'partial' AND NOT available THEN 'reserved'
      ELSE 'unknown'
    END
  )::availability_status;
