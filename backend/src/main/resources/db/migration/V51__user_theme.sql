-- 'system' follows the browser's prefers-color-scheme, and is the default so
-- existing rows keep today's behaviour.
ALTER TABLE app_user
  ADD COLUMN theme TEXT NOT NULL DEFAULT 'system';

ALTER TABLE app_user
  ADD CONSTRAINT app_user_theme_check CHECK (theme IN ('light', 'dark', 'system'));
