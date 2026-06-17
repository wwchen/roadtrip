-- Hard-remove the deprecated rec.gov campsite alert app.
--
-- Generic availability watches/jobs/snapshots replace alerts, matches,
-- companion work, and the old in-process campsite scheduler.

DROP TABLE IF EXISTS schedules;
DROP TABLE IF EXISTS matches;
DROP TABLE IF EXISTS alerts;
DROP TABLE IF EXISTS settings;
