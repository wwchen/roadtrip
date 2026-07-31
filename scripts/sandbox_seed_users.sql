-- Sandbox user seed — run AFTER snapshot restore.
--
-- Inserts two fixed-id users that never appear in prod data (high ids chosen
-- to be well above any restored sequence values).  Idempotent via ON CONFLICT.
--
-- Will (90001)  — admin role; matches ROADTRIP_SANDBOX_ASSUME_USER admin path.
-- Matt (90002)  — no role; regular user for testing non-admin flows.
--
-- The unique index on app_user is app_user_email_lower_uq ON lower(email),
-- so sandbox emails are lowercase and distinct from any prod address.

INSERT INTO app_user (id, email, email_verified, display_name, status)
VALUES
    (90001, 'will@sandbox.local', TRUE,  'Will', 'active'),
    (90002, 'matt@sandbox.local', FALSE, 'Matt', 'active')
ON CONFLICT (id) DO NOTHING;

-- Advance the sequence past our fixed ids so any future auto-inserts
-- (e.g. from the backend creating a user via OIDC callback on restored data)
-- never collide with the seed rows.
SELECT setval(
    pg_get_serial_sequence('app_user', 'id'),
    GREATEST(90002, (SELECT MAX(id) FROM app_user))
);

INSERT INTO user_role (user_id, role)
VALUES (90001, 'admin')
ON CONFLICT DO NOTHING;
