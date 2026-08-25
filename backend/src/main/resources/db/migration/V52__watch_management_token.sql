-- Magic-link tokens that let an alert email manage its own watch (pause,
-- resume, delete) without requiring the recipient to be signed in. Each row
-- is scoped to exactly one watch; only the SHA-256 hash of the token is
-- stored, mirroring user_session.
CREATE TABLE watch_management_token (
  id BIGSERIAL PRIMARY KEY,
  watch_id BIGINT NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  token_hash BYTEA NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX watch_management_token_hash_idx ON watch_management_token (token_hash);
CREATE INDEX watch_management_token_watch_idx ON watch_management_token (watch_id);
