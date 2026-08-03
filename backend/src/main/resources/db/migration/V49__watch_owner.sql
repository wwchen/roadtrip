-- Watches now belong to a user. Existing rows are ownerless and cannot be
-- assigned to anyone, so drop them. All child tables
-- (availability_watch_target, availability_watch_poller, availability_run/job)
-- reference availability_watch(id) ON DELETE CASCADE, so this clears the whole
-- subtree; the V30 last-target prune trigger is moot once the parent is gone.
DELETE FROM availability_watch;

ALTER TABLE availability_watch
  ADD COLUMN owner_user_id BIGINT NOT NULL REFERENCES app_user(id);

CREATE INDEX availability_watch_owner_idx ON availability_watch (owner_user_id);
