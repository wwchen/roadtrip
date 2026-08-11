-- The UI theme preference, per user. 'system' means follow the browser's
-- prefers-color-scheme; it is the default so every existing row keeps today's
-- behaviour on a light-mode device.
ALTER TABLE app_user
  ADD COLUMN theme TEXT NOT NULL DEFAULT 'system';

ALTER TABLE app_user
  ADD CONSTRAINT app_user_theme_check CHECK (theme IN ('light', 'dark', 'system'));
