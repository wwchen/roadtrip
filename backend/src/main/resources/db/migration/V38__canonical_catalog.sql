-- Canonical catalog reset.
--
-- This migration intentionally discards old catalog, availability, watch, and
-- poller state. Old POI/reservable identities are not translated into the new
-- canonical campground/campsite catalog.

TRUNCATE TABLE availability, availability_watch_target, availability_watch, availability_poller
  RESTART IDENTITY CASCADE;

DROP TABLE IF EXISTS reservable_pois CASCADE;
DROP TABLE IF EXISTS reservables CASCADE;
DROP TABLE IF EXISTS pois CASCADE;

CREATE TABLE vendor_refs (
  id             BIGSERIAL PRIMARY KEY,
  vendor         TEXT        NOT NULL,
  entity_type    TEXT        NOT NULL,
  external_id    TEXT        NOT NULL,
  external_name  TEXT,
  source_url     TEXT,
  payload        JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at     TIMESTAMPTZ,
  CONSTRAINT vendor_refs_vendor_check CHECK (length(btrim(vendor)) > 0),
  CONSTRAINT vendor_refs_entity_type_check CHECK (
    entity_type IN (
      'campground',
      'campsite',
      'tesla_supercharger',
      'planet_fitness_location'
    )
  ),
  CONSTRAINT vendor_refs_external_id_check CHECK (length(btrim(external_id)) > 0),
  CONSTRAINT vendor_refs_payload_check CHECK (jsonb_typeof(payload) = 'object')
);

CREATE UNIQUE INDEX vendor_refs_vendor_entity_external_uidx
  ON vendor_refs (vendor, entity_type, external_id)
  WHERE deleted_at IS NULL;

CREATE TABLE campgrounds (
  id                         BIGSERIAL PRIMARY KEY,
  name                       TEXT        NOT NULL,
  status                     TEXT,
  status_description         TEXT,
  kind                       TEXT,
  short_description          TEXT,
  medium_description         TEXT,
  long_description           TEXT,
  location                   JSONB       NOT NULL DEFAULT '{}'::jsonb,
  default_campsite_schedule  JSONB       NOT NULL DEFAULT '{}'::jsonb,
  amenities                  JSONB       NOT NULL DEFAULT '{}'::jsonb,
  max_rv_length              DOUBLE PRECISION,
  max_trailer_length         DOUBLE PRECISION,
  has_pull_through_sites     BOOLEAN,
  big_rig_friendly           BOOLEAN,
  reservation_url            TEXT,
  links                      JSONB       NOT NULL DEFAULT '[]'::jsonb,
  photos                     JSONB       NOT NULL DEFAULT '[]'::jsonb,
  alerts                     JSONB       NOT NULL DEFAULT '[]'::jsonb,
  price                      JSONB       NOT NULL DEFAULT '{}'::jsonb,
  cell_service               JSONB       NOT NULL DEFAULT '{}'::jsonb,
  management                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
  contact                    JSONB       NOT NULL DEFAULT '{}'::jsonb,
  connections                JSONB       NOT NULL DEFAULT '{}'::jsonb,
  metadata                   JSONB       NOT NULL DEFAULT '{}'::jsonb,
  source_payload             JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at                 TIMESTAMPTZ,
  CONSTRAINT campgrounds_name_check CHECK (length(btrim(name)) > 0),
  CONSTRAINT campgrounds_max_rv_length_check CHECK (max_rv_length IS NULL OR max_rv_length >= 0),
  CONSTRAINT campgrounds_max_trailer_length_check CHECK (max_trailer_length IS NULL OR max_trailer_length >= 0),
  CONSTRAINT campgrounds_location_check CHECK (jsonb_typeof(location) = 'object'),
  CONSTRAINT campgrounds_default_schedule_check CHECK (jsonb_typeof(default_campsite_schedule) = 'object'),
  CONSTRAINT campgrounds_amenities_check CHECK (jsonb_typeof(amenities) = 'object'),
  CONSTRAINT campgrounds_links_check CHECK (jsonb_typeof(links) = 'array'),
  CONSTRAINT campgrounds_photos_check CHECK (jsonb_typeof(photos) = 'array'),
  CONSTRAINT campgrounds_alerts_check CHECK (jsonb_typeof(alerts) = 'array'),
  CONSTRAINT campgrounds_price_check CHECK (jsonb_typeof(price) = 'object'),
  CONSTRAINT campgrounds_cell_service_check CHECK (jsonb_typeof(cell_service) = 'object'),
  CONSTRAINT campgrounds_management_check CHECK (jsonb_typeof(management) = 'object'),
  CONSTRAINT campgrounds_contact_check CHECK (jsonb_typeof(contact) = 'object'),
  CONSTRAINT campgrounds_connections_check CHECK (jsonb_typeof(connections) = 'object'),
  CONSTRAINT campgrounds_metadata_check CHECK (jsonb_typeof(metadata) = 'object'),
  CONSTRAINT campgrounds_source_payload_check CHECK (jsonb_typeof(source_payload) = 'object')
);

CREATE INDEX campgrounds_active_kind_idx
  ON campgrounds (kind)
  WHERE deleted_at IS NULL;

CREATE TABLE campsites (
  id                     BIGSERIAL PRIMARY KEY,
  campground_id          BIGINT      NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  name                   TEXT        NOT NULL,
  kind                   TEXT        NOT NULL,
  loop_name              TEXT,
  latitude               DOUBLE PRECISION,
  longitude              DOUBLE PRECISION,
  reservation_url        TEXT,
  equipment              JSONB       DEFAULT '[]'::jsonb,
  kind_listed            TEXT,
  schedule               JSONB       NOT NULL DEFAULT '{}'::jsonb,
  price                  JSONB       NOT NULL DEFAULT '{}'::jsonb,
  firepit                BOOLEAN,
  picnic_table           BOOLEAN,
  ada_accessible         BOOLEAN,
  water_hookups          BOOLEAN,
  electric_hookups       BOOLEAN,
  sewer_hookups          BOOLEAN,
  max_people             INT,
  max_cars               INT,
  pull_through           BOOLEAN,
  driveway_length        INT,
  max_rv_length          INT,
  max_trailer_length     DOUBLE PRECISION,
  photos                 JSONB       NOT NULL DEFAULT '[]'::jsonb,
  source_payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at             TIMESTAMPTZ,
  CONSTRAINT campsites_name_check CHECK (length(btrim(name)) > 0),
  CONSTRAINT campsites_kind_check CHECK (length(btrim(kind)) > 0),
  CONSTRAINT campsites_latitude_check CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT campsites_longitude_check CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  CONSTRAINT campsites_max_people_check CHECK (max_people IS NULL OR max_people >= 0),
  CONSTRAINT campsites_max_cars_check CHECK (max_cars IS NULL OR max_cars >= 0),
  CONSTRAINT campsites_driveway_length_check CHECK (driveway_length IS NULL OR driveway_length >= 0),
  CONSTRAINT campsites_max_rv_length_check CHECK (max_rv_length IS NULL OR max_rv_length >= 0),
  CONSTRAINT campsites_max_trailer_length_check CHECK (max_trailer_length IS NULL OR max_trailer_length >= 0),
  CONSTRAINT campsites_equipment_check CHECK (equipment IS NULL OR jsonb_typeof(equipment) = 'array'),
  CONSTRAINT campsites_schedule_check CHECK (jsonb_typeof(schedule) = 'object'),
  CONSTRAINT campsites_price_check CHECK (jsonb_typeof(price) = 'object'),
  CONSTRAINT campsites_photos_check CHECK (jsonb_typeof(photos) = 'array'),
  CONSTRAINT campsites_source_payload_check CHECK (jsonb_typeof(source_payload) = 'object')
);

CREATE INDEX campsites_campground_active_idx
  ON campsites (campground_id, name)
  WHERE deleted_at IS NULL;

ALTER TABLE availability
  RENAME COLUMN reservable_id TO campsite_id;

ALTER TABLE availability_watch_target
  RENAME COLUMN reservable_id TO campsite_id;

ALTER TABLE availability_watch
  RENAME COLUMN reservable_filters TO campsite_filters;

ALTER TABLE availability_fetch_call
  RENAME COLUMN reservable_count TO campsite_count;

ALTER INDEX IF EXISTS availability_current_idx
  RENAME TO availability_campsite_current_idx;

ALTER INDEX IF EXISTS availability_watch_target_reservable_idx
  RENAME TO availability_watch_target_campsite_idx;

ALTER TABLE availability
  ADD CONSTRAINT availability_campsite_id_fkey
  FOREIGN KEY (campsite_id) REFERENCES campsites(id) ON DELETE CASCADE;

ALTER TABLE availability_watch_target
  ADD CONSTRAINT availability_watch_target_campsite_id_fkey
  FOREIGN KEY (campsite_id) REFERENCES campsites(id) ON DELETE CASCADE;

CREATE TABLE campground_vendor_refs (
  campground_id  BIGINT      NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  vendor_ref_id  BIGINT      NOT NULL REFERENCES vendor_refs(id) ON DELETE RESTRICT,
  is_primary     BOOLEAN     NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campground_id, vendor_ref_id)
);

CREATE UNIQUE INDEX campground_vendor_refs_vendor_ref_uidx
  ON campground_vendor_refs (vendor_ref_id);

CREATE UNIQUE INDEX campground_vendor_refs_primary_uidx
  ON campground_vendor_refs (campground_id)
  WHERE is_primary;

CREATE TABLE campsite_vendor_refs (
  campsite_id    BIGINT      NOT NULL REFERENCES campsites(id) ON DELETE CASCADE,
  vendor_ref_id  BIGINT      NOT NULL REFERENCES vendor_refs(id) ON DELETE RESTRICT,
  is_primary     BOOLEAN     NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (campsite_id, vendor_ref_id)
);

CREATE UNIQUE INDEX campsite_vendor_refs_vendor_ref_uidx
  ON campsite_vendor_refs (vendor_ref_id);

CREATE UNIQUE INDEX campsite_vendor_refs_primary_uidx
  ON campsite_vendor_refs (campsite_id)
  WHERE is_primary;

CREATE TABLE pois (
  id                    BIGSERIAL PRIMARY KEY,
  poi_type              TEXT        NOT NULL,
  geom                  geometry(Geometry, 4326) NOT NULL,
  cadence_override_sec  INT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at            TIMESTAMPTZ,
  CONSTRAINT pois_poi_type_check CHECK (
    poi_type IN ('campground', 'tesla_supercharger', 'planet_fitness_location')
  ),
  CONSTRAINT pois_cadence_override_sec_check CHECK (cadence_override_sec IS NULL OR cadence_override_sec >= 5)
);

CREATE INDEX pois_geom_idx
  ON pois USING GIST (geom);

CREATE INDEX pois_active_type_idx
  ON pois (poi_type)
  WHERE deleted_at IS NULL;

ALTER TABLE availability_watch_target
  ADD CONSTRAINT availability_watch_target_poi_id_fkey
  FOREIGN KEY (poi_id) REFERENCES pois(id) ON DELETE CASCADE;

ALTER TABLE availability_poller
  ADD CONSTRAINT availability_poller_poi_id_fkey
  FOREIGN KEY (poi_id) REFERENCES pois(id) ON DELETE CASCADE;

CREATE TABLE poi_campgrounds (
  poi_id         BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  campground_id  BIGINT      NOT NULL REFERENCES campgrounds(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (poi_id, campground_id)
);

CREATE UNIQUE INDEX poi_campgrounds_campground_uidx
  ON poi_campgrounds (campground_id);

CREATE TABLE tesla_superchargers (
  id                    BIGSERIAL PRIMARY KEY,
  location_slug         TEXT        NOT NULL,
  location_guid         TEXT,
  common_site_name      TEXT        NOT NULL,
  site_status           TEXT        NOT NULL,
  access_type           TEXT,
  open_to_public        BOOLEAN     NOT NULL DEFAULT true,
  open_to_non_teslas    BOOLEAN,
  trailer_friendly      BOOLEAN,
  twenty_four_seven     BOOLEAN,
  stall_count           INT,
  max_power_kw          INT,
  address               JSONB       NOT NULL DEFAULT '{}'::jsonb,
  region                TEXT,
  country               CHAR(2),
  time_zone             TEXT,
  amenities             JSONB       NOT NULL DEFAULT '[]'::jsonb,
  hardware_counts       JSONB       NOT NULL DEFAULT '{}'::jsonb,
  pricebooks            JSONB       NOT NULL DEFAULT '[]'::jsonb,
  availability_profile  JSONB       NOT NULL DEFAULT '{}'::jsonb,
  info_url              TEXT,
  index_payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
  detail_payload        JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at            TIMESTAMPTZ,
  CONSTRAINT tesla_superchargers_location_slug_check CHECK (length(btrim(location_slug)) > 0),
  CONSTRAINT tesla_superchargers_common_site_name_check CHECK (length(btrim(common_site_name)) > 0),
  CONSTRAINT tesla_superchargers_site_status_check CHECK (length(btrim(site_status)) > 0),
  CONSTRAINT tesla_superchargers_stall_count_check CHECK (stall_count IS NULL OR stall_count >= 0),
  CONSTRAINT tesla_superchargers_max_power_kw_check CHECK (max_power_kw IS NULL OR max_power_kw >= 0),
  CONSTRAINT tesla_superchargers_address_check CHECK (jsonb_typeof(address) = 'object'),
  CONSTRAINT tesla_superchargers_amenities_check CHECK (jsonb_typeof(amenities) = 'array'),
  CONSTRAINT tesla_superchargers_hardware_counts_check CHECK (jsonb_typeof(hardware_counts) = 'object'),
  CONSTRAINT tesla_superchargers_pricebooks_check CHECK (jsonb_typeof(pricebooks) = 'array'),
  CONSTRAINT tesla_superchargers_availability_profile_check CHECK (jsonb_typeof(availability_profile) = 'object'),
  CONSTRAINT tesla_superchargers_index_payload_check CHECK (jsonb_typeof(index_payload) = 'object'),
  CONSTRAINT tesla_superchargers_detail_payload_check CHECK (jsonb_typeof(detail_payload) = 'object')
);

CREATE UNIQUE INDEX tesla_superchargers_location_slug_uidx
  ON tesla_superchargers (location_slug);

CREATE INDEX tesla_superchargers_active_country_region_idx
  ON tesla_superchargers (country, region)
  WHERE deleted_at IS NULL;

CREATE TABLE poi_tesla_superchargers (
  poi_id                 BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  tesla_supercharger_id  BIGINT      NOT NULL REFERENCES tesla_superchargers(id) ON DELETE CASCADE,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (poi_id, tesla_supercharger_id)
);

CREATE UNIQUE INDEX poi_tesla_superchargers_supercharger_uidx
  ON poi_tesla_superchargers (tesla_supercharger_id);

CREATE TABLE planet_fitness_locations (
  id               BIGSERIAL PRIMARY KEY,
  location_id      TEXT        NOT NULL,
  name             TEXT        NOT NULL,
  address          JSONB       NOT NULL DEFAULT '{}'::jsonb,
  region           TEXT,
  country          CHAR(2),
  phone            TEXT,
  info_url         TEXT,
  amenities        JSONB       NOT NULL DEFAULT '[]'::jsonb,
  payload          JSONB       NOT NULL DEFAULT '{}'::jsonb,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at       TIMESTAMPTZ,
  CONSTRAINT planet_fitness_locations_location_id_check CHECK (length(btrim(location_id)) > 0),
  CONSTRAINT planet_fitness_locations_name_check CHECK (length(btrim(name)) > 0),
  CONSTRAINT planet_fitness_locations_address_check CHECK (jsonb_typeof(address) = 'object'),
  CONSTRAINT planet_fitness_locations_amenities_check CHECK (jsonb_typeof(amenities) = 'array'),
  CONSTRAINT planet_fitness_locations_payload_check CHECK (jsonb_typeof(payload) = 'object')
);

CREATE UNIQUE INDEX planet_fitness_locations_location_id_uidx
  ON planet_fitness_locations (location_id);

CREATE INDEX planet_fitness_locations_active_region_idx
  ON planet_fitness_locations (country, region)
  WHERE deleted_at IS NULL;

CREATE TABLE poi_planet_fitness_locations (
  poi_id                       BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  planet_fitness_location_id   BIGINT      NOT NULL REFERENCES planet_fitness_locations(id) ON DELETE CASCADE,
  created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (poi_id, planet_fitness_location_id)
);

CREATE UNIQUE INDEX poi_planet_fitness_locations_location_uidx
  ON poi_planet_fitness_locations (planet_fitness_location_id);
