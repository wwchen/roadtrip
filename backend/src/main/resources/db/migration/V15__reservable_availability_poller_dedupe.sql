-- Prevent duplicate active pollers for the same logical intent.
-- Historical duplicates are preserved as done so old runs/logs keep their FK.

WITH ranked AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY
        coalesce(poi_id, -1),
        coalesce(reservable_id, -1),
        reservable_filters,
        target_dates,
        min_nights,
        cadence_sec,
        trigger_actions,
        stop_when_triggered
      ORDER BY created_at DESC, id DESC
    ) AS rn
  FROM reservable_availability_pollers
  WHERE status IN ('active', 'paused')
)
UPDATE reservable_availability_pollers p
SET status = 'done',
    claimed_until = NULL,
    claim_token = NULL,
    updated_at = now()
FROM ranked r
WHERE p.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX reservable_availability_pollers_open_unique_idx
  ON reservable_availability_pollers (
    coalesce(poi_id, -1),
    coalesce(reservable_id, -1),
    reservable_filters,
    target_dates,
    min_nights,
    cadence_sec,
    trigger_actions,
    stop_when_triggered
  )
  WHERE status IN ('active', 'paused');
