DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'roadtrip_web') THEN
    CREATE ROLE roadtrip_web LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
END
$$;

ALTER ROLE roadtrip_web SET default_transaction_read_only = on;
ALTER ROLE roadtrip_web SET statement_timeout = '10s';

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO roadtrip_web', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO roadtrip_web;
GRANT SELECT ON pois, campgrounds, poi_campgrounds, campsites, tesla_superchargers, poi_tesla_superchargers, planet_fitness_locations, poi_planet_fitness_locations TO roadtrip_web;
