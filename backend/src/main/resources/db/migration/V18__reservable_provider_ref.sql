-- Durable provider-specific catalog metadata for reservables.
--
-- `raw` stays the verbatim upstream/audit payload. `provider_ref` is the
-- normalized booking/query reference the request path may depend on.
-- This mirrors pois.provider_ref and keeps relationship semantics out of raw.

ALTER TABLE reservables
  ADD COLUMN provider_ref JSONB;

WITH refs AS (
  SELECT
    id,
    jsonb_strip_nulls(
      jsonb_build_object(
        'transactionLocationId',
        CASE
          WHEN raw->>'_parent_aspira_txn_loc' ~ '^-?[0-9]+$'
            THEN (raw->>'_parent_aspira_txn_loc')::bigint
        END,
        'mapId',
        CASE
          WHEN raw->>'_parent_aspira_map_id' ~ '^-?[0-9]+$'
            THEN (raw->>'_parent_aspira_map_id')::bigint
        END,
        'resourceLocationId',
        CASE
          WHEN raw->>'_parent_aspira_resource_loc' ~ '^-?[0-9]+$'
            THEN (raw->>'_parent_aspira_resource_loc')::bigint
        END
      )
    ) AS provider_ref
  FROM reservables
  WHERE vendor LIKE 'aspira\_%' ESCAPE '\'
    AND raw IS NOT NULL
)
UPDATE reservables r
SET provider_ref = refs.provider_ref
FROM refs
WHERE r.id = refs.id
  AND refs.provider_ref <> '{}'::jsonb;
