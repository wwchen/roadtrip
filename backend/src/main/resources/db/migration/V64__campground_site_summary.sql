-- One row per campground with live campsites: what the campground filter reads
-- instead of grouping campsites per request. CampsiteRepo refreshes the rows an
-- upsert batch touches; this backfill covers everything already imported.
CREATE TABLE campground_site_summary (
  campground_id  BIGINT      PRIMARY KEY REFERENCES campgrounds(id) ON DELETE CASCADE,
  site_total     INT         NOT NULL,
  site_counts    JSONB       NOT NULL DEFAULT '{}'::jsonb,
  max_people     INT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT campground_site_summary_total_check CHECK (site_total >= 0),
  CONSTRAINT campground_site_summary_counts_check CHECK (jsonb_typeof(site_counts) = 'object')
);

INSERT INTO campground_site_summary (campground_id, site_total, site_counts, max_people)
SELECT campground_id,
       SUM(kind_count)::int,
       jsonb_object_agg(kind, kind_count),
       MAX(max_people)
FROM (
  SELECT campground_id, kind, COUNT(*)::int AS kind_count, MAX(max_people) AS max_people
  FROM campsites
  WHERE deleted_at IS NULL
  GROUP BY campground_id, kind
) per_kind
GROUP BY campground_id;
