GRANT SELECT ON availability TO roadtrip_web;

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'roadtrip_availability_writer') THEN
    CREATE ROLE roadtrip_availability_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
END
$$;

ALTER ROLE roadtrip_availability_writer SET statement_timeout = '10s';

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO roadtrip_availability_writer', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO roadtrip_availability_writer;
GRANT SELECT ON campgrounds, campsites, availability TO roadtrip_availability_writer;
GRANT INSERT, UPDATE ON availability TO roadtrip_availability_writer;
GRANT USAGE ON SEQUENCE availability_id_seq TO roadtrip_availability_writer;
