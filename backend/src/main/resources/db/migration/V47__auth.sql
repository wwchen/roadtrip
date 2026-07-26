-- RFC 0009 PR 1: the auth layer's persistence. Identity only — nothing here is
-- read or enforced yet. Routes, sessions-in-flight, and ownership arrive in
-- later PRs; this migration exists so those can land without a schema change.
--
-- Four tables:
--   app_user       one row per human
--   user_identity  (provider, subject) -> user. Many rows per user = account
--                  linking, and the vendor-swap path (re-link by upstream
--                  subject, falling back to verified email).
--   user_session   first-party sessions. The vendor's token never gets past
--                  the callback; this is what the cookie actually references.
--   user_role      coarse roles. Nothing consumes it until the authz pass, but
--                  it ships here to avoid a second migration for one column.
--
-- Email is stored as TEXT with a UNIQUE index on lower(email) rather than
-- CITEXT: the citext extension is not installed in this database (V1 installs
-- only postgis), and an expression index keeps normalization explicit at the
-- boundary instead of hiding it in a column type.

CREATE TABLE app_user (
  id             BIGSERIAL   PRIMARY KEY,
  -- Normalized to lowercase on write; uniqueness enforced by the index below.
  email          TEXT        NOT NULL,
  -- Whether the identity provider asserted the address. Gates account linking:
  -- an unverified address must never attach to an existing user.
  email_verified BOOLEAN     NOT NULL DEFAULT FALSE,
  display_name   TEXT,
  status         TEXT        NOT NULL DEFAULT 'active'
                               CHECK (status IN ('active', 'disabled')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_user_email_lower_uq ON app_user (lower(email));

CREATE TABLE user_identity (
  id                BIGSERIAL   PRIMARY KEY,
  user_id           BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  -- The provider we actually spoke OIDC to (config slug, e.g. 'auth0').
  provider          TEXT        NOT NULL,
  -- That provider's `sub` claim.
  subject           TEXT        NOT NULL,
  -- The IdP behind the aggregator ('google' | 'apple' | 'password') and its own
  -- subject, when the provider exposes it. Recorded from day one because a
  -- future vendor migration matches on this stable key; it cannot be
  -- backfilled for users who have stopped signing in.
  upstream_provider TEXT,
  upstream_subject  TEXT,
  -- When the provider last asserted this identity's email. NULL = never.
  email_verified_at TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, subject)
);

CREATE INDEX user_identity_user_idx ON user_identity (user_id);

CREATE INDEX user_identity_upstream_idx
  ON user_identity (upstream_provider, upstream_subject)
  WHERE upstream_subject IS NOT NULL;

CREATE TABLE user_session (
  id         BIGSERIAL   PRIMARY KEY,
  user_id    BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  -- SHA-256 of the opaque cookie value. The cookie itself is never stored, so a
  -- database leak does not hand over live sessions.
  token_hash BYTEA       NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  -- Set on logout / revocation. A session is usable only while NULL and unexpired.
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX user_session_user_idx ON user_session (user_id);

-- Sweep support for expired-session cleanup.
CREATE INDEX user_session_expires_idx ON user_session (expires_at);

CREATE TABLE user_role (
  user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role    TEXT   NOT NULL CHECK (role IN ('admin')),
  PRIMARY KEY (user_id, role)
);
