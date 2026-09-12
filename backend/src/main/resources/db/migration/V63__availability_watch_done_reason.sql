-- Why a watch reached `done`: availability was found and the watch was told to
-- stop, or its window elapsed. Both transitions used to persist nothing, so the
-- frontend guessed from end_date and mislabelled a watch triggered on its last
-- day. No backfill: a row that went done before this migration honestly does not
-- know, and NULL says so.
ALTER TABLE availability_watch
    ADD COLUMN done_reason text;

ALTER TABLE availability_watch
    ADD CONSTRAINT availability_watch_done_reason_check
        CHECK (done_reason IS NULL OR done_reason IN ('triggered', 'elapsed'));
