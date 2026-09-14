-- Per-campground campsite aggregates, computed on read. A SQL function rather
-- than a view: Postgres inlines it as a LATERAL join, so the aggregate stays
-- correlated to one campground and walks the covering index below, where a
-- view's GROUP BY cannot take the join key and aggregates the whole table.
CREATE INDEX campsites_summary_idx
  ON campsites (campground_id, kind, max_people)
  WHERE deleted_at IS NULL;

CREATE FUNCTION campground_site_summary(cg_id BIGINT)
RETURNS TABLE (site_total INT, site_counts JSONB, max_people INT)
LANGUAGE sql STABLE AS $$
  SELECT SUM(kind_count)::int, jsonb_object_agg(kind, kind_count), MAX(max_people)::int
  FROM (
    SELECT kind, COUNT(*)::int AS kind_count, MAX(max_people) AS max_people
    FROM campsites c
    WHERE c.campground_id = cg_id AND c.deleted_at IS NULL
    GROUP BY kind
  ) per_kind
$$;
