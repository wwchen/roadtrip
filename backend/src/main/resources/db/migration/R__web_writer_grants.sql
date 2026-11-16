DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'roadtrip_web_writer') THEN
    CREATE ROLE roadtrip_web_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
END
$$;
ALTER ROLE roadtrip_web_writer SET statement_timeout = '10s';
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO roadtrip_web_writer', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO roadtrip_web_writer;
GRANT SELECT, INSERT, UPDATE ON app_user, user_identity TO roadtrip_web_writer;
GRANT USAGE ON SEQUENCE app_user_id_seq, user_identity_id_seq TO roadtrip_web_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_booking_credentials TO roadtrip_web_writer;
GRANT SELECT ON user_settings TO roadtrip_web_writer;
