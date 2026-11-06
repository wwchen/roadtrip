CREATE TYPE "public"."availability_status" AS ENUM('first_come', 'reserved', 'available', 'closed', 'unknown', 'past');--> statement-breakpoint
CREATE SEQUENCE "public"."availability_job_run_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1;--> statement-breakpoint
CREATE TABLE "app_user" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"email" text NOT NULL,
	"email_verified" boolean DEFAULT false NOT NULL,
	"display_name" text,
	"status" text DEFAULT 'active' NOT NULL,
	"theme" text DEFAULT 'system' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "app_user_status_check" CHECK ("status" = ANY (ARRAY['active'::text, 'disabled'::text])),
	CONSTRAINT "app_user_theme_check" CHECK ("theme" = ANY (ARRAY['light'::text, 'dark'::text, 'system'::text]))
);
--> statement-breakpoint
CREATE TABLE "user_booking_credentials" (
	"user_id" bigint NOT NULL,
	"provider" text NOT NULL,
	"username" text NOT NULL,
	"secret_cipher" "bytea" NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "user_booking_credentials_pkey" PRIMARY KEY("user_id","provider")
);
--> statement-breakpoint
CREATE TABLE "user_identity" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"user_id" bigint NOT NULL,
	"provider" text NOT NULL,
	"subject" text NOT NULL,
	"upstream_provider" text,
	"upstream_subject" text,
	"email_verified_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "user_identity_provider_subject_key" UNIQUE("provider","subject")
);
--> statement-breakpoint
CREATE TABLE "user_role" (
	"user_id" bigint NOT NULL,
	"role" text NOT NULL,
	CONSTRAINT "user_role_pkey" PRIMARY KEY("user_id","role"),
	CONSTRAINT "user_role_role_check" CHECK ("role" = 'admin'::text)
);
--> statement-breakpoint
CREATE TABLE "user_session" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"user_id" bigint NOT NULL,
	"token_hash" "bytea" NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"revoked_at" timestamp with time zone,
	CONSTRAINT "user_session_token_hash_key" UNIQUE("token_hash")
);
--> statement-breakpoint
CREATE TABLE "user_settings" (
	"user_id" bigint PRIMARY KEY NOT NULL,
	"notification_email" text,
	"slack_token_cipher" "bytea",
	"slack_token_hint" text,
	"slack_channel" text,
	"recgov_username" text,
	"recgov_password_cipher" "bytea",
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "availability" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"campsite_id" bigint NOT NULL,
	"target_date" date NOT NULL,
	"status" "availability_status" NOT NULL,
	"last_observed_at" timestamp with time zone NOT NULL,
	"run_id" bigint,
	"previous_id" bigint
);
--> statement-breakpoint
CREATE TABLE "availability_watch" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"owner_user_id" bigint NOT NULL,
	"start_date" date NOT NULL,
	"end_date" date NOT NULL,
	"cadence_sec" integer,
	"status" text DEFAULT 'active' NOT NULL,
	"done_reason" text,
	"stop_when_triggered" boolean DEFAULT true NOT NULL,
	"trigger_kinds" text[] NOT NULL,
	"trigger_config" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"campsite_filters" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"magic_link_token" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "availability_watch_cadence_sec_check" CHECK ("cadence_sec" >= 5),
	CONSTRAINT "availability_watch_date_window_check" CHECK (end_date > start_date),
	CONSTRAINT "availability_watch_done_reason_check" CHECK ("done_reason" IS NULL OR "done_reason" = ANY (ARRAY['triggered'::text, 'elapsed'::text])),
	CONSTRAINT "availability_watch_reservable_filters_check" CHECK (jsonb_typeof("campsite_filters") = 'object'),
	CONSTRAINT "availability_watch_status_check" CHECK ("status" = ANY (ARRAY['active'::text, 'paused'::text, 'done'::text])),
	CONSTRAINT "availability_watch_trigger_config_check" CHECK (jsonb_typeof("trigger_config") = 'object'),
	CONSTRAINT "availability_watch_trigger_kinds_check" CHECK (cardinality(trigger_kinds) > 0)
);
--> statement-breakpoint
CREATE TABLE "availability_watch_poller" (
	"watch_id" bigint NOT NULL,
	"poller_id" bigint NOT NULL,
	CONSTRAINT "availability_watch_poller_pkey" PRIMARY KEY("watch_id","poller_id")
);
--> statement-breakpoint
CREATE TABLE "availability_watch_target" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"watch_id" bigint NOT NULL,
	"poi_id" bigint,
	"campsite_id" bigint,
	CONSTRAINT "availability_watch_target_check" CHECK ((poi_id IS NOT NULL) <> (campsite_id IS NOT NULL))
);
--> statement-breakpoint
CREATE TABLE "campgrounds" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"data_provider" text NOT NULL,
	"data_provider_ref" text NOT NULL,
	"booking_provider" text,
	"booking_provider_ref" text,
	"booking_aliases" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"name" text NOT NULL,
	"parent_name" text,
	"kind" text,
	"status" text,
	"status_description" text,
	"short_description" text,
	"medium_description" text,
	"long_description" text,
	"reservation_url" text,
	"big_rig_friendly" boolean,
	"has_pull_through_sites" boolean,
	"max_rv_length" double precision,
	"max_trailer_length" double precision,
	"location" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"contact" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"connections" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"management" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"price" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"amenities" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"alerts" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"cell_service" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"links" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"photos" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"default_campsite_schedule" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"geometry_provenance" jsonb,
	"source_payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone,
	CONSTRAINT "campgrounds_alerts_check" CHECK (jsonb_typeof("alerts") = 'array'),
	CONSTRAINT "campgrounds_amenities_check" CHECK (jsonb_typeof("amenities") = 'array'),
	CONSTRAINT "campgrounds_booking_aliases_check" CHECK (jsonb_typeof("booking_aliases") = 'array'),
	CONSTRAINT "campgrounds_cell_service_check" CHECK (jsonb_typeof("cell_service") = 'array'),
	CONSTRAINT "campgrounds_connections_check" CHECK (jsonb_typeof("connections") = 'object'),
	CONSTRAINT "campgrounds_contact_check" CHECK (jsonb_typeof("contact") = 'object'),
	CONSTRAINT "campgrounds_data_source_check" CHECK (length(btrim("data_provider")) > 0),
	CONSTRAINT "campgrounds_default_schedule_check" CHECK (jsonb_typeof("default_campsite_schedule") = 'object'),
	CONSTRAINT "campgrounds_geometry_provenance_check" CHECK ("geometry_provenance" IS NULL OR jsonb_typeof("geometry_provenance") = 'object'),
	CONSTRAINT "campgrounds_links_check" CHECK (jsonb_typeof("links") = 'array'),
	CONSTRAINT "campgrounds_location_check" CHECK (jsonb_typeof("location") = 'object'),
	CONSTRAINT "campgrounds_management_check" CHECK (jsonb_typeof("management") = 'object'),
	CONSTRAINT "campgrounds_max_rv_length_check" CHECK ("max_rv_length" IS NULL OR "max_rv_length" >= 0),
	CONSTRAINT "campgrounds_max_trailer_length_check" CHECK ("max_trailer_length" IS NULL OR "max_trailer_length" >= 0),
	CONSTRAINT "campgrounds_metadata_check" CHECK (jsonb_typeof("metadata") = 'object'),
	CONSTRAINT "campgrounds_name_check" CHECK (length(btrim("name")) > 0),
	CONSTRAINT "campgrounds_photos_check" CHECK (jsonb_typeof("photos") = 'array'),
	CONSTRAINT "campgrounds_price_check" CHECK (jsonb_typeof("price") = 'object'),
	CONSTRAINT "campgrounds_source_payload_check" CHECK (jsonb_typeof("source_payload") = 'object')
);
--> statement-breakpoint
CREATE TABLE "campsites" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"campground_id" bigint NOT NULL,
	"data_provider" text NOT NULL,
	"data_provider_ref" text NOT NULL,
	"booking_provider" text,
	"booking_provider_ref" text,
	"booking_aliases" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"name" text NOT NULL,
	"kind" text NOT NULL,
	"kind_listed" text,
	"loop_name" text,
	"description" text,
	"reservation_url" text,
	"latitude" double precision,
	"longitude" double precision,
	"ada_accessible" boolean,
	"electric_hookups" boolean,
	"water_hookups" boolean,
	"sewer_hookups" boolean,
	"firepit" boolean,
	"picnic_table" boolean,
	"pull_through" boolean,
	"driveway_length" integer,
	"max_cars" integer,
	"min_people" integer,
	"max_people" integer,
	"max_rv_length" integer,
	"max_trailer_length" double precision,
	"attributes" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"equipment" jsonb DEFAULT '[]'::jsonb,
	"photos" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"price" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"schedule" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"source_payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone,
	CONSTRAINT "campsites_attributes_check" CHECK (jsonb_typeof("attributes") = 'array'),
	CONSTRAINT "campsites_booking_aliases_check" CHECK (jsonb_typeof("booking_aliases") = 'array'),
	CONSTRAINT "campsites_data_source_check" CHECK (length(btrim("data_provider")) > 0),
	CONSTRAINT "campsites_driveway_length_check" CHECK ("driveway_length" IS NULL OR "driveway_length" >= 0),
	CONSTRAINT "campsites_equipment_check" CHECK ("equipment" IS NULL OR jsonb_typeof("equipment") = 'array'),
	CONSTRAINT "campsites_kind_check" CHECK (length(btrim("kind")) > 0),
	CONSTRAINT "campsites_kind_wire_check" CHECK ("kind" = ANY (ARRAY['standard'::text, 'tent'::text, 'rv'::text, 'cabin'::text, 'group'::text, 'walk_in'::text, 'boat_in'::text, 'equestrian'::text, 'backcountry'::text, 'day_use'::text, 'other'::text])),
	CONSTRAINT "campsites_latitude_check" CHECK ("latitude" IS NULL OR ("latitude" >= -90 AND "latitude" <= 90)),
	CONSTRAINT "campsites_longitude_check" CHECK ("longitude" IS NULL OR ("longitude" >= -180 AND "longitude" <= 180)),
	CONSTRAINT "campsites_max_cars_check" CHECK ("max_cars" IS NULL OR "max_cars" >= 0),
	CONSTRAINT "campsites_max_people_check" CHECK ("max_people" IS NULL OR "max_people" >= 0),
	CONSTRAINT "campsites_max_rv_length_check" CHECK ("max_rv_length" IS NULL OR "max_rv_length" >= 0),
	CONSTRAINT "campsites_max_trailer_length_check" CHECK ("max_trailer_length" IS NULL OR "max_trailer_length" >= 0),
	CONSTRAINT "campsites_min_people_check" CHECK ("min_people" IS NULL OR "min_people" >= 0),
	CONSTRAINT "campsites_name_check" CHECK (length(btrim("name")) > 0),
	CONSTRAINT "campsites_photos_check" CHECK (jsonb_typeof("photos") = 'array'),
	CONSTRAINT "campsites_price_check" CHECK (jsonb_typeof("price") = 'object'),
	CONSTRAINT "campsites_schedule_check" CHECK (jsonb_typeof("schedule") = 'object'),
	CONSTRAINT "campsites_source_payload_check" CHECK (jsonb_typeof("source_payload") = 'object')
);
--> statement-breakpoint
CREATE TABLE "planet_fitness_locations" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"location_id" text NOT NULL,
	"name" text NOT NULL,
	"phone" text,
	"info_url" text,
	"opening_hours" text,
	"region" text,
	"country" char(2),
	"address" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"amenities" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone,
	CONSTRAINT "planet_fitness_locations_address_check" CHECK (jsonb_typeof("address") = 'object'),
	CONSTRAINT "planet_fitness_locations_amenities_check" CHECK (jsonb_typeof("amenities") = 'array'),
	CONSTRAINT "planet_fitness_locations_location_id_check" CHECK (length(btrim("location_id")) > 0),
	CONSTRAINT "planet_fitness_locations_name_check" CHECK (length(btrim("name")) > 0),
	CONSTRAINT "planet_fitness_locations_payload_check" CHECK (jsonb_typeof("payload") = 'object')
);
--> statement-breakpoint
CREATE TABLE "poi_campgrounds" (
	"poi_id" bigint NOT NULL,
	"campground_id" bigint NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "poi_campgrounds_pkey" PRIMARY KEY("poi_id","campground_id")
);
--> statement-breakpoint
CREATE TABLE "poi_planet_fitness_locations" (
	"poi_id" bigint NOT NULL,
	"planet_fitness_location_id" bigint NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "poi_planet_fitness_locations_pkey" PRIMARY KEY("poi_id","planet_fitness_location_id")
);
--> statement-breakpoint
CREATE TABLE "poi_tesla_superchargers" (
	"poi_id" bigint NOT NULL,
	"tesla_supercharger_id" bigint NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "poi_tesla_superchargers_pkey" PRIMARY KEY("poi_id","tesla_supercharger_id")
);
--> statement-breakpoint
CREATE TABLE "pois" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"poi_type" text NOT NULL,
	"geom" geometry(Geometry,4326) NOT NULL,
	"cadence_override_sec" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone,
	CONSTRAINT "pois_poi_type_check" CHECK ("poi_type" = ANY (ARRAY['campground'::text, 'tesla_supercharger'::text, 'planet_fitness_location'::text])),
	CONSTRAINT "pois_cadence_override_sec_check" CHECK ("cadence_override_sec" IS NULL OR "cadence_override_sec" >= 5)
);
--> statement-breakpoint
CREATE TABLE "tesla_superchargers" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"location_slug" text NOT NULL,
	"location_guid" text,
	"common_site_name" text NOT NULL,
	"site_status" text NOT NULL,
	"access_type" text,
	"open_to_public" boolean DEFAULT true NOT NULL,
	"open_to_non_teslas" boolean,
	"trailer_friendly" boolean,
	"twenty_four_seven" boolean,
	"stall_count" integer,
	"max_power_kw" integer,
	"region" text,
	"country" char(2),
	"time_zone" text,
	"info_url" text,
	"address" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"amenities" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"availability_profile" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"hardware_counts" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"pricebooks" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"index_payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"detail_payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"deleted_at" timestamp with time zone,
	CONSTRAINT "tesla_superchargers_address_check" CHECK (jsonb_typeof("address") = 'object'),
	CONSTRAINT "tesla_superchargers_amenities_check" CHECK (jsonb_typeof("amenities") = 'array'),
	CONSTRAINT "tesla_superchargers_availability_profile_check" CHECK (jsonb_typeof("availability_profile") = 'object'),
	CONSTRAINT "tesla_superchargers_common_site_name_check" CHECK (length(btrim("common_site_name")) > 0),
	CONSTRAINT "tesla_superchargers_detail_payload_check" CHECK (jsonb_typeof("detail_payload") = 'object'),
	CONSTRAINT "tesla_superchargers_hardware_counts_check" CHECK (jsonb_typeof("hardware_counts") = 'object'),
	CONSTRAINT "tesla_superchargers_index_payload_check" CHECK (jsonb_typeof("index_payload") = 'object'),
	CONSTRAINT "tesla_superchargers_location_slug_check" CHECK (length(btrim("location_slug")) > 0),
	CONSTRAINT "tesla_superchargers_max_power_kw_check" CHECK ("max_power_kw" IS NULL OR "max_power_kw" >= 0),
	CONSTRAINT "tesla_superchargers_pricebooks_check" CHECK (jsonb_typeof("pricebooks") = 'array'),
	CONSTRAINT "tesla_superchargers_site_status_check" CHECK (length(btrim("site_status")) > 0),
	CONSTRAINT "tesla_superchargers_stall_count_check" CHECK ("stall_count" IS NULL OR "stall_count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "import_runs" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"source" text NOT NULL,
	"status" text NOT NULL,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone,
	"seen_count" integer,
	"notes" text
);
--> statement-breakpoint
CREATE TABLE "ingest_runs" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"target" text NOT NULL,
	"phase" text NOT NULL,
	"phase_kind" text NOT NULL,
	"status" text NOT NULL,
	"triggered_by" text NOT NULL,
	"parent_run_id" bigint,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone,
	"exit_code" integer,
	"counts" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"notes" text,
	CONSTRAINT "ingest_runs_phase_kind_check" CHECK ("phase_kind" = ANY (ARRAY['target'::text, 'fetch'::text, 'import'::text])),
	CONSTRAINT "ingest_runs_status_check" CHECK ("status" = ANY (ARRAY['started'::text, 'completed'::text, 'failed'::text, 'aborted'::text]))
);
--> statement-breakpoint
CREATE TABLE "api_cache" (
	"namespace" text NOT NULL,
	"cache_key" text NOT NULL,
	"payload" jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	CONSTRAINT "api_cache_pkey" PRIMARY KEY("namespace","cache_key")
);
--> statement-breakpoint
CREATE TABLE "availability_fetch_call" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"run_id" bigint NOT NULL,
	"provider" text NOT NULL,
	"parent_ref" text NOT NULL,
	"window_start" date NOT NULL,
	"window_end" date NOT NULL,
	"outcome" text NOT NULL,
	"campsite_count" integer DEFAULT 0 NOT NULL,
	"duration_ms" integer,
	"error" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "availability_fetch_call_duration_ms_check" CHECK ("duration_ms" IS NULL OR "duration_ms" >= 0),
	CONSTRAINT "availability_fetch_call_outcome_check" CHECK ("outcome" = ANY (ARRAY['ok'::text, 'rate_limited'::text, 'upstream_5xx'::text, 'blocked'::text, 'other'::text])),
	CONSTRAINT "availability_fetch_call_reservable_count_check" CHECK ("campsite_count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "availability_poller" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"poi_id" bigint NOT NULL,
	"provider" text NOT NULL,
	"parent_ref" text NOT NULL,
	"active" boolean DEFAULT true NOT NULL,
	"next_run_at" timestamp with time zone DEFAULT now() NOT NULL,
	"last_run_at" timestamp with time zone,
	"last_force_pull_at" timestamp with time zone,
	"claim_token" text,
	"claimed_until" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "availability_poller_provider_parent_ref_key" UNIQUE("provider","parent_ref")
);
--> statement-breakpoint
CREATE TABLE "availability_run" (
	"id" bigint DEFAULT nextval('availability_job_run_id_seq'::regclass) NOT NULL,
	"poller_id" bigint NOT NULL,
	"status" text DEFAULT 'started' NOT NULL,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone,
	"duration_ms" integer,
	"snapshot_count" integer DEFAULT 0 NOT NULL,
	"error" text,
	CONSTRAINT "availability_job_run_pkey" PRIMARY KEY("id"),
	CONSTRAINT "availability_job_run_duration_ms_check" CHECK ("duration_ms" IS NULL OR "duration_ms" >= 0),
	CONSTRAINT "availability_job_run_snapshot_count_check" CHECK ("snapshot_count" >= 0),
	CONSTRAINT "availability_job_run_status_check" CHECK ("status" = ANY (ARRAY['started'::text, 'completed'::text, 'failed'::text]))
);
--> statement-breakpoint
CREATE TABLE "vendor_rate_limit_bucket" (
	"id" varchar(255) PRIMARY KEY NOT NULL,
	"state" "bytea"
);
--> statement-breakpoint
ALTER TABLE "user_booking_credentials" ADD CONSTRAINT "user_booking_credentials_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "public"."app_user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_identity" ADD CONSTRAINT "user_identity_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "public"."app_user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_role" ADD CONSTRAINT "user_role_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "public"."app_user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_session" ADD CONSTRAINT "user_session_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "public"."app_user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_settings" ADD CONSTRAINT "user_settings_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "public"."app_user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability" ADD CONSTRAINT "availability_campsite_id_fkey" FOREIGN KEY ("campsite_id") REFERENCES "public"."campsites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability" ADD CONSTRAINT "availability_run_id_fkey" FOREIGN KEY ("run_id") REFERENCES "public"."availability_run"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability" ADD CONSTRAINT "availability_previous_id_fkey" FOREIGN KEY ("previous_id") REFERENCES "public"."availability"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_watch" ADD CONSTRAINT "availability_watch_owner_user_id_fkey" FOREIGN KEY ("owner_user_id") REFERENCES "public"."app_user"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_watch_poller" ADD CONSTRAINT "availability_watch_poller_watch_id_fkey" FOREIGN KEY ("watch_id") REFERENCES "public"."availability_watch"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_watch_poller" ADD CONSTRAINT "availability_watch_poller_poller_id_fkey" FOREIGN KEY ("poller_id") REFERENCES "public"."availability_poller"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_watch_target" ADD CONSTRAINT "availability_watch_target_watch_id_fkey" FOREIGN KEY ("watch_id") REFERENCES "public"."availability_watch"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_watch_target" ADD CONSTRAINT "availability_watch_target_poi_id_fkey" FOREIGN KEY ("poi_id") REFERENCES "public"."pois"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_watch_target" ADD CONSTRAINT "availability_watch_target_campsite_id_fkey" FOREIGN KEY ("campsite_id") REFERENCES "public"."campsites"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "campsites" ADD CONSTRAINT "campsites_campground_id_fkey" FOREIGN KEY ("campground_id") REFERENCES "public"."campgrounds"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "poi_campgrounds" ADD CONSTRAINT "poi_campgrounds_poi_id_fkey" FOREIGN KEY ("poi_id") REFERENCES "public"."pois"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "poi_campgrounds" ADD CONSTRAINT "poi_campgrounds_campground_id_fkey" FOREIGN KEY ("campground_id") REFERENCES "public"."campgrounds"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "poi_planet_fitness_locations" ADD CONSTRAINT "poi_planet_fitness_locations_poi_id_fkey" FOREIGN KEY ("poi_id") REFERENCES "public"."pois"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "poi_planet_fitness_locations" ADD CONSTRAINT "poi_planet_fitness_locations_planet_fitness_location_id_fkey" FOREIGN KEY ("planet_fitness_location_id") REFERENCES "public"."planet_fitness_locations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "poi_tesla_superchargers" ADD CONSTRAINT "poi_tesla_superchargers_poi_id_fkey" FOREIGN KEY ("poi_id") REFERENCES "public"."pois"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "poi_tesla_superchargers" ADD CONSTRAINT "poi_tesla_superchargers_tesla_supercharger_id_fkey" FOREIGN KEY ("tesla_supercharger_id") REFERENCES "public"."tesla_superchargers"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "ingest_runs" ADD CONSTRAINT "ingest_runs_parent_run_id_fkey" FOREIGN KEY ("parent_run_id") REFERENCES "public"."ingest_runs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_fetch_call" ADD CONSTRAINT "availability_fetch_call_run_id_fkey" FOREIGN KEY ("run_id") REFERENCES "public"."availability_run"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_poller" ADD CONSTRAINT "availability_poller_poi_id_fkey" FOREIGN KEY ("poi_id") REFERENCES "public"."pois"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "availability_run" ADD CONSTRAINT "availability_run_poller_id_fkey" FOREIGN KEY ("poller_id") REFERENCES "public"."availability_poller"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "app_user_email_lower_uq" ON "app_user" USING btree (lower(email));--> statement-breakpoint
CREATE INDEX "user_identity_user_idx" ON "user_identity" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "user_identity_upstream_idx" ON "user_identity" USING btree ("upstream_provider","upstream_subject") WHERE upstream_subject IS NOT NULL;--> statement-breakpoint
CREATE INDEX "user_session_user_idx" ON "user_session" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "user_session_expires_idx" ON "user_session" USING btree ("expires_at");--> statement-breakpoint
CREATE INDEX "availability_campsite_current_idx" ON "availability" USING btree ("campsite_id","target_date","last_observed_at" DESC NULLS FIRST);--> statement-breakpoint
CREATE INDEX "availability_run_idx" ON "availability" USING btree ("run_id") WHERE run_id IS NOT NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "availability_previous_id_uq" ON "availability" USING btree ("previous_id") WHERE previous_id IS NOT NULL;--> statement-breakpoint
CREATE INDEX "availability_watch_active_idx" ON "availability_watch" USING btree ("status") WHERE status = 'active';--> statement-breakpoint
CREATE INDEX "availability_watch_owner_idx" ON "availability_watch" USING btree ("owner_user_id");--> statement-breakpoint
CREATE INDEX "availability_watch_poller_poller_idx" ON "availability_watch_poller" USING btree ("poller_id");--> statement-breakpoint
CREATE INDEX "availability_watch_target_watch_idx" ON "availability_watch_target" USING btree ("watch_id");--> statement-breakpoint
CREATE INDEX "availability_watch_target_poi_idx" ON "availability_watch_target" USING btree ("poi_id") WHERE poi_id IS NOT NULL;--> statement-breakpoint
CREATE INDEX "availability_watch_target_campsite_idx" ON "availability_watch_target" USING btree ("campsite_id") WHERE campsite_id IS NOT NULL;--> statement-breakpoint
CREATE INDEX "campgrounds_active_kind_idx" ON "campgrounds" USING btree ("kind") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE INDEX "campgrounds_booking_aliases_gin" ON "campgrounds" USING gin ("booking_aliases");--> statement-breakpoint
CREATE INDEX "campgrounds_booking_provider_ref_idx" ON "campgrounds" USING btree ("booking_provider","booking_provider_ref");--> statement-breakpoint
CREATE INDEX "campgrounds_geometry_provenance_match_kind_idx" ON "campgrounds" USING btree ((geometry_provenance ->> 'match_kind')) WHERE geometry_provenance IS NOT NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "campgrounds_provider_uidx" ON "campgrounds" USING btree ("data_provider","data_provider_ref") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE INDEX "campsites_booking_aliases_gin" ON "campsites" USING gin ("booking_aliases");--> statement-breakpoint
CREATE INDEX "campsites_booking_provider_ref_idx" ON "campsites" USING btree ("booking_provider","booking_provider_ref");--> statement-breakpoint
CREATE INDEX "campsites_campground_active_idx" ON "campsites" USING btree ("campground_id","name") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE INDEX "campsites_summary_idx" ON "campsites" USING btree ("campground_id","kind","max_people") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "campsites_provider_uidx" ON "campsites" USING btree ("data_provider","data_provider_ref") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE INDEX "planet_fitness_locations_active_region_idx" ON "planet_fitness_locations" USING btree ("country","region") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "planet_fitness_locations_location_id_uidx" ON "planet_fitness_locations" USING btree ("location_id");--> statement-breakpoint
CREATE UNIQUE INDEX "poi_campgrounds_campground_uidx" ON "poi_campgrounds" USING btree ("campground_id");--> statement-breakpoint
CREATE UNIQUE INDEX "poi_planet_fitness_locations_location_uidx" ON "poi_planet_fitness_locations" USING btree ("planet_fitness_location_id");--> statement-breakpoint
CREATE UNIQUE INDEX "poi_tesla_superchargers_supercharger_uidx" ON "poi_tesla_superchargers" USING btree ("tesla_supercharger_id");--> statement-breakpoint
CREATE INDEX "pois_active_type_idx" ON "pois" USING btree ("poi_type") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE INDEX "pois_geom_idx" ON "pois" USING gist ("geom");--> statement-breakpoint
CREATE INDEX "tesla_superchargers_active_country_region_idx" ON "tesla_superchargers" USING btree ("country","region") WHERE deleted_at IS NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "tesla_superchargers_location_slug_uidx" ON "tesla_superchargers" USING btree ("location_slug");--> statement-breakpoint
CREATE INDEX "ingest_runs_parent_idx" ON "ingest_runs" USING btree ("parent_run_id");--> statement-breakpoint
CREATE INDEX "ingest_runs_started_only_idx" ON "ingest_runs" USING btree ("started_at") WHERE status = 'started' AND phase_kind = 'target';--> statement-breakpoint
CREATE INDEX "ingest_runs_target_started_idx" ON "ingest_runs" USING btree ("target","started_at" DESC NULLS FIRST);--> statement-breakpoint
CREATE INDEX "api_cache_expires_at_idx" ON "api_cache" USING btree ("expires_at");--> statement-breakpoint
CREATE INDEX "availability_fetch_call_outcome_created_idx" ON "availability_fetch_call" USING btree ("outcome","created_at" DESC NULLS FIRST);--> statement-breakpoint
CREATE INDEX "availability_fetch_call_run_idx" ON "availability_fetch_call" USING btree ("run_id");--> statement-breakpoint
CREATE INDEX "availability_poller_due_idx" ON "availability_poller" USING btree ("next_run_at") WHERE active;--> statement-breakpoint
CREATE INDEX "availability_run_poller_started_idx" ON "availability_run" USING btree ("poller_id","started_at" DESC NULLS FIRST);