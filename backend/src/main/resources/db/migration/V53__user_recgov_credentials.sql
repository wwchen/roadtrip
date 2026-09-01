-- Per-user recreation.gov credentials, following the V48 Slack-token pattern on
-- the same table. One row per user already exists there; these three columns
-- are NULL until the user saves credentials in Settings.
--
-- The username is stored in the clear on purpose: it is an email-shaped
-- identifier needed for display and for re-login, not a secret in the way the
-- password is. The password is sealed with SecretCipher (AES-256-GCM) and the
-- hint is its last 4 characters, which is all a masked SecretField needs.
--
-- No session-status column: "configured" is derived from these columns, and
-- live session health is always asked of the companion rather than persisted.
ALTER TABLE user_settings
  ADD COLUMN recgov_username        TEXT,
  ADD COLUMN recgov_password_cipher BYTEA,
  ADD COLUMN recgov_password_hint   TEXT;
