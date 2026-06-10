CREATE TABLE api_cache (
  namespace   TEXT NOT NULL,
  cache_key   TEXT NOT NULL,
  payload     JSONB NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (namespace, cache_key)
);

CREATE INDEX api_cache_expires_at_idx ON api_cache (expires_at);
