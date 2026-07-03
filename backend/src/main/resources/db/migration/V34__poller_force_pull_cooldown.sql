-- PR5: force pull. Tracks the last time a human forced this poller's
-- next_run_at forward, so the check-now route can enforce a per-poller
-- cooldown (a user mashing "check now" must not be able to starve the
-- vendor governor for everyone sharing this poller).
ALTER TABLE availability_poller ADD COLUMN last_force_pull_at TIMESTAMPTZ;
