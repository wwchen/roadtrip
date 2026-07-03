-- PR4: durable per-vendor token bucket state for the fetch governor.
--
-- This table is Bucket4j's internal state store (its PostgreSQL
-- SELECT-FOR-UPDATE proxy manager reads/writes it via raw JDBC:
--   INSERT INTO <table>(<id>, <state>) VALUES(?, null) ON CONFLICT(<id>) DO NOTHING
--   SELECT <state> FROM <table> WHERE <id> = ? FOR UPDATE
-- It is NOT a roadtrip domain table and is deliberately excluded from the jOOQ
-- includes allowlist -- nothing in the codebase queries it via jOOQ; Bucket4j
-- owns it directly. We create it via Flyway (rather than leaving it to a library
-- runtime path) so the schema stays deterministic and Flyway is the single source
-- of schema truth.
--
-- Keyed by vendor/provider name (VARCHAR), so buckets are addressed by a stable
-- string key with no hashCode collision risk (see VendorRateLimiter, which uses
-- PrimaryKeyMapper.STRING). State is Bucket4j's opaque serialized bucket snapshot.
CREATE TABLE vendor_rate_limit_bucket (
    id    VARCHAR(255) PRIMARY KEY,
    state BYTEA
);
