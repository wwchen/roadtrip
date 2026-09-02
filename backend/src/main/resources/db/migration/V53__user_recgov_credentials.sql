-- Per-user recreation.gov credentials, following the V48 Slack-token pattern on
-- the same table. One row per user already exists there; these two columns are
-- NULL until the user saves credentials in Settings.
--
-- The username is stored in the clear on purpose: it is an email-shaped
-- identifier needed for display and for re-login, not a secret in the way the
-- password is. The password is sealed with SecretCipher (AES-256-GCM).
--
-- Deliberately no last-4 hint column, which is where the V48 pattern stops
-- applying: a Slack bot token is machine-generated, so its tail says *which*
-- token is stored without helping anyone guess it. A rec.gov password is
-- human-chosen, so its tail is real credential material — it would narrow a
-- guess enormously, sitting in the clear next to the ciphertext and travelling
-- to anyone holding the user's session. The UI shows a fixed-length mask
-- instead, which does not leak the password's length either.
--
-- No session-status column: "configured" is derived from these columns, and
-- live session health is always asked of the companion rather than persisted.
ALTER TABLE user_settings
  ADD COLUMN recgov_username        TEXT,
  ADD COLUMN recgov_password_cipher BYTEA;
