-- Per-user booking credentials, keyed by provider: one account per user per vendor.
--
-- This is V53's two rec.gov columns on user_settings widened to a table. The
-- storage contract is unchanged and still applies to every provider: the
-- username is an identifier kept in the clear for display and re-login, the
-- secret is sealed with SecretCipher (AES-256-GCM), and there is deliberately
-- no last-4 hint column — a human-chosen password's tail is credential
-- material, not a display aid (see V53 for the full argument).
--
-- The V53 columns stay for now so a rollback still finds them; a later cleanup
-- drops them once no jar reads them.
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
