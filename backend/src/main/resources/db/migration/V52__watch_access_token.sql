-- Magic-link access to one watch.
--
-- An alert email is the only place a watch's owner reliably sees it, and until
-- now every link in that email landed on a page that demanded a session. This
-- table backs a capability token minted per alert email and carried in the link:
-- holding it authorizes read/modify/stop on exactly ONE watch, and nothing else.
-- It is not a session — it cannot list watches, cannot create one, and cannot
-- reach any other user's data.
--
-- Same storage contract as `user_session` (V47): only the SHA-256 of the token
-- is persisted, so a database leak yields no usable link and the plaintext never
-- reaches a log or a query plan. Tokens are minted fresh per email rather than
-- reused, which is what lets us store the hash alone — there is no plaintext to
-- re-read for a second send.
--
-- Rows die with their watch (ON DELETE CASCADE), on revocation, or at expiry via
-- the periodic sweep.

CREATE TABLE availability_watch_access_token (
  id           BIGSERIAL   PRIMARY KEY,
  watch_id     BIGINT      NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  -- SHA-256 of the opaque token that travels in the email link.
  token_hash   BYTEA       NOT NULL UNIQUE,
  expires_at   TIMESTAMPTZ NOT NULL,
  -- Set when the link is deliberately killed. A token is usable only while NULL
  -- and unexpired.
  revoked_at   TIMESTAMPTZ,
  -- Last successful resolution, for answering "did anyone actually use this?".
  last_used_at TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Revoking every live link for a watch (the owner pressing "stop") is a
-- watch-scoped update, so index the live rows by watch.
CREATE INDEX availability_watch_access_token_watch_idx
  ON availability_watch_access_token (watch_id)
  WHERE revoked_at IS NULL;

-- Sweep support for expired-token cleanup.
CREATE INDEX availability_watch_access_token_expires_idx
  ON availability_watch_access_token (expires_at);
