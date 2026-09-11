-- Per-user booking credentials, keyed by provider: V53's two rec.gov columns on
-- user_settings, widened to a table. Same storage contract (see V53); the V53
-- columns stay until a later cleanup drops them, so a rollback still finds them.
CREATE TABLE IF NOT EXISTS user_booking_credentials (
  user_id       BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  provider      TEXT        NOT NULL,
  username      TEXT        NOT NULL,
  secret_cipher BYTEA       NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, provider)
);

-- Copies the stored rec.gov accounts across. ON CONFLICT DO NOTHING makes a
-- rerun a no-op rather than overwriting an account changed since the copy.
INSERT INTO user_booking_credentials (user_id, provider, username, secret_cipher)
SELECT user_id, 'recgov', recgov_username, recgov_password_cipher
FROM user_settings
WHERE recgov_username IS NOT NULL
  AND recgov_password_cipher IS NOT NULL
ON CONFLICT DO NOTHING;
