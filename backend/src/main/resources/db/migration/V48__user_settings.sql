-- Per-user settings. Named generically (not user_notification_settings) so
-- future non-notification preferences share the table without a new migration.
-- One row per user, created lazily on first write. The Slack token is stored
-- as AES-GCM ciphertext (see SecretCipher); only the last-4 hint is ever
-- returned to a client.
CREATE TABLE user_settings (
  user_id            BIGINT      PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  notification_email TEXT,                 -- NULL = fall back to app_user.email
  slack_channel      TEXT,                 -- NULL = channel unset
  slack_token_cipher BYTEA,                -- AES-GCM ciphertext; NULL = no token
  slack_token_hint   TEXT,                 -- last 4 chars; safe to return
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
