import {
  type BookingProvider,
  type CampsiteKind,
  campsiteKinds,
} from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import {
  bigint,
  bigserial,
  boolean,
  char,
  check,
  doublePrecision,
  foreignKey,
  index,
  integer,
  jsonb,
  pgTable,
  primaryKey,
  text,
  timestamp,
  uniqueIndex,
} from 'drizzle-orm/pg-core';
import { geometry } from '../types/geometry.js';
import {
  jsonbArray,
  jsonbObject,
  nonBlank,
  nullOrAtLeast,
  nullOrBetween,
  nullOrJsonbArray,
  nullOrJsonbObject,
  textIn,
} from './constraints.js';

const MIN_CADENCE_SEC = 5;
const MIN_LENGTH = 0;
const MIN_COUNT = 0;
const MIN_LATITUDE = -90;
const MAX_LATITUDE = 90;
const MIN_LONGITUDE = -180;
const MAX_LONGITUDE = 180;
const COUNTRY_CODE_LENGTH = 2;

export const CAMPGROUND_POI_TYPE = 'campground';

export const POI_TYPES = [
  CAMPGROUND_POI_TYPE,
  'tesla_supercharger',
  'planet_fitness_location',
] as const;

export type PoiType = (typeof POI_TYPES)[number];

export const pois = pgTable(
  'pois',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    poiType: text('poi_type').$type<PoiType>().notNull(),
    geom: geometry('geom').notNull(),
    cadenceOverrideSec: integer('cadence_override_sec'),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    check('pois_poi_type_check', textIn(table.poiType, POI_TYPES)),
    check(
      'pois_cadence_override_sec_check',
      nullOrAtLeast(table.cadenceOverrideSec, MIN_CADENCE_SEC),
    ),
    index('pois_active_type_idx').on(table.poiType).where(sql`deleted_at IS NULL`),
    index('pois_geom_idx').using('gist', table.geom),
  ],
);

export const campgrounds = pgTable(
  'campgrounds',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    dataProvider: text('data_provider').notNull(),
    dataProviderRef: text('data_provider_ref').notNull(),
    bookingProvider: text('booking_provider').$type<BookingProvider>(),
    bookingProviderRef: text('booking_provider_ref'),
    bookingAliases: jsonb('booking_aliases').default([]).notNull(),
    name: text('name').notNull(),
    parentName: text('parent_name'),
    kind: text('kind'),
    status: text('status'),
    statusDescription: text('status_description'),
    shortDescription: text('short_description'),
    mediumDescription: text('medium_description'),
    longDescription: text('long_description'),
    reservationUrl: text('reservation_url'),
    bigRigFriendly: boolean('big_rig_friendly'),
    hasPullThroughSites: boolean('has_pull_through_sites'),
    maxRvLength: doublePrecision('max_rv_length'),
    maxTrailerLength: doublePrecision('max_trailer_length'),
    location: jsonb('location').default({}).notNull(),
    contact: jsonb('contact').default({}).notNull(),
    connections: jsonb('connections').default({}).notNull(),
    management: jsonb('management').default({}).notNull(),
    price: jsonb('price').default({}).notNull(),
    metadata: jsonb('metadata').default({}).notNull(),
    amenities: jsonb('amenities').default([]).notNull(),
    alerts: jsonb('alerts').default([]).notNull(),
    cellService: jsonb('cell_service').default([]).notNull(),
    links: jsonb('links').default([]).notNull(),
    photos: jsonb('photos').default([]).notNull(),
    defaultCampsiteSchedule: jsonb('default_campsite_schedule').default({}).notNull(),
    geometryProvenance: jsonb('geometry_provenance'),
    sourcePayload: jsonb('source_payload').default({}).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    check('campgrounds_alerts_check', jsonbArray(table.alerts)),
    check('campgrounds_amenities_check', jsonbArray(table.amenities)),
    check('campgrounds_booking_aliases_check', jsonbArray(table.bookingAliases)),
    check('campgrounds_cell_service_check', jsonbArray(table.cellService)),
    check('campgrounds_connections_check', jsonbObject(table.connections)),
    check('campgrounds_contact_check', jsonbObject(table.contact)),
    check('campgrounds_data_source_check', nonBlank(table.dataProvider)),
    check('campgrounds_default_schedule_check', jsonbObject(table.defaultCampsiteSchedule)),
    check('campgrounds_geometry_provenance_check', nullOrJsonbObject(table.geometryProvenance)),
    check('campgrounds_links_check', jsonbArray(table.links)),
    check('campgrounds_location_check', jsonbObject(table.location)),
    check('campgrounds_management_check', jsonbObject(table.management)),
    check('campgrounds_max_rv_length_check', nullOrAtLeast(table.maxRvLength, MIN_LENGTH)),
    check('campgrounds_max_trailer_length_check', nullOrAtLeast(table.maxTrailerLength, MIN_LENGTH)),
    check('campgrounds_metadata_check', jsonbObject(table.metadata)),
    check('campgrounds_name_check', nonBlank(table.name)),
    check('campgrounds_photos_check', jsonbArray(table.photos)),
    check('campgrounds_price_check', jsonbObject(table.price)),
    check('campgrounds_source_payload_check', jsonbObject(table.sourcePayload)),
    index('campgrounds_active_kind_idx').on(table.kind).where(sql`deleted_at IS NULL`),
    index('campgrounds_booking_aliases_gin').using('gin', table.bookingAliases),
    index('campgrounds_booking_provider_ref_idx').on(table.bookingProvider, table.bookingProviderRef),
    index('campgrounds_geometry_provenance_match_kind_idx')
      .on(sql`(geometry_provenance ->> 'match_kind')`)
      .where(sql`geometry_provenance IS NOT NULL`),
    uniqueIndex('campgrounds_provider_uidx')
      .on(table.dataProvider, table.dataProviderRef)
      .where(sql`deleted_at IS NULL`),
  ],
);

export const campsites = pgTable(
  'campsites',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    campgroundId: bigint('campground_id', { mode: 'bigint' }).notNull(),
    dataProvider: text('data_provider').notNull(),
    dataProviderRef: text('data_provider_ref').notNull(),
    bookingProvider: text('booking_provider').$type<BookingProvider>(),
    bookingProviderRef: text('booking_provider_ref'),
    bookingAliases: jsonb('booking_aliases').default([]).notNull(),
    name: text('name').notNull(),
    kind: text('kind').$type<CampsiteKind>().notNull(),
    kindListed: text('kind_listed'),
    loopName: text('loop_name'),
    description: text('description'),
    reservationUrl: text('reservation_url'),
    latitude: doublePrecision('latitude'),
    longitude: doublePrecision('longitude'),
    adaAccessible: boolean('ada_accessible'),
    electricHookups: boolean('electric_hookups'),
    waterHookups: boolean('water_hookups'),
    sewerHookups: boolean('sewer_hookups'),
    firepit: boolean('firepit'),
    picnicTable: boolean('picnic_table'),
    pullThrough: boolean('pull_through'),
    drivewayLength: integer('driveway_length'),
    maxCars: integer('max_cars'),
    minPeople: integer('min_people'),
    maxPeople: integer('max_people'),
    maxRvLength: integer('max_rv_length'),
    maxTrailerLength: doublePrecision('max_trailer_length'),
    attributes: jsonb('attributes').default([]).notNull(),
    equipment: jsonb('equipment').default([]),
    photos: jsonb('photos').default([]).notNull(),
    price: jsonb('price').default({}).notNull(),
    schedule: jsonb('schedule').default({}).notNull(),
    sourcePayload: jsonb('source_payload').default({}).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    foreignKey({
      name: 'campsites_campground_id_fkey',
      columns: [table.campgroundId],
      foreignColumns: [campgrounds.id],
    }).onDelete('cascade'),
    check('campsites_attributes_check', jsonbArray(table.attributes)),
    check('campsites_booking_aliases_check', jsonbArray(table.bookingAliases)),
    check('campsites_data_source_check', nonBlank(table.dataProvider)),
    check('campsites_driveway_length_check', nullOrAtLeast(table.drivewayLength, MIN_LENGTH)),
    check('campsites_equipment_check', nullOrJsonbArray(table.equipment)),
    check('campsites_kind_check', nonBlank(table.kind)),
    check('campsites_kind_wire_check', textIn(table.kind, campsiteKinds)),
    check('campsites_latitude_check', nullOrBetween(table.latitude, MIN_LATITUDE, MAX_LATITUDE)),
    check('campsites_longitude_check', nullOrBetween(table.longitude, MIN_LONGITUDE, MAX_LONGITUDE)),
    check('campsites_max_cars_check', nullOrAtLeast(table.maxCars, MIN_COUNT)),
    check('campsites_max_people_check', nullOrAtLeast(table.maxPeople, MIN_COUNT)),
    check('campsites_max_rv_length_check', nullOrAtLeast(table.maxRvLength, MIN_LENGTH)),
    check('campsites_max_trailer_length_check', nullOrAtLeast(table.maxTrailerLength, MIN_LENGTH)),
    check('campsites_min_people_check', nullOrAtLeast(table.minPeople, MIN_COUNT)),
    check('campsites_name_check', nonBlank(table.name)),
    check('campsites_photos_check', jsonbArray(table.photos)),
    check('campsites_price_check', jsonbObject(table.price)),
    check('campsites_schedule_check', jsonbObject(table.schedule)),
    check('campsites_source_payload_check', jsonbObject(table.sourcePayload)),
    index('campsites_booking_aliases_gin').using('gin', table.bookingAliases),
    index('campsites_booking_provider_ref_idx').on(table.bookingProvider, table.bookingProviderRef),
    index('campsites_campground_active_idx')
      .on(table.campgroundId, table.name)
      .where(sql`deleted_at IS NULL`),
    index('campsites_summary_idx')
      .on(table.campgroundId, table.kind, table.maxPeople)
      .where(sql`deleted_at IS NULL`),
    uniqueIndex('campsites_provider_uidx')
      .on(table.dataProvider, table.dataProviderRef)
      .where(sql`deleted_at IS NULL`),
  ],
);

export const teslaSuperchargers = pgTable(
  'tesla_superchargers',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    locationSlug: text('location_slug').notNull(),
    locationGuid: text('location_guid'),
    commonSiteName: text('common_site_name').notNull(),
    siteStatus: text('site_status').notNull(),
    accessType: text('access_type'),
    openToPublic: boolean('open_to_public').default(true).notNull(),
    openToNonTeslas: boolean('open_to_non_teslas'),
    trailerFriendly: boolean('trailer_friendly'),
    twentyFourSeven: boolean('twenty_four_seven'),
    stallCount: integer('stall_count'),
    maxPowerKw: integer('max_power_kw'),
    region: text('region'),
    country: char('country', { length: COUNTRY_CODE_LENGTH }),
    timeZone: text('time_zone'),
    infoUrl: text('info_url'),
    address: jsonb('address').default({}).notNull(),
    amenities: jsonb('amenities').default([]).notNull(),
    availabilityProfile: jsonb('availability_profile').default({}).notNull(),
    hardwareCounts: jsonb('hardware_counts').default({}).notNull(),
    pricebooks: jsonb('pricebooks').default([]).notNull(),
    indexPayload: jsonb('index_payload').default({}).notNull(),
    detailPayload: jsonb('detail_payload').default({}).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    check('tesla_superchargers_address_check', jsonbObject(table.address)),
    check('tesla_superchargers_amenities_check', jsonbArray(table.amenities)),
    check('tesla_superchargers_availability_profile_check', jsonbObject(table.availabilityProfile)),
    check('tesla_superchargers_common_site_name_check', nonBlank(table.commonSiteName)),
    check('tesla_superchargers_detail_payload_check', jsonbObject(table.detailPayload)),
    check('tesla_superchargers_hardware_counts_check', jsonbObject(table.hardwareCounts)),
    check('tesla_superchargers_index_payload_check', jsonbObject(table.indexPayload)),
    check('tesla_superchargers_location_slug_check', nonBlank(table.locationSlug)),
    check('tesla_superchargers_max_power_kw_check', nullOrAtLeast(table.maxPowerKw, MIN_COUNT)),
    check('tesla_superchargers_pricebooks_check', jsonbArray(table.pricebooks)),
    check('tesla_superchargers_site_status_check', nonBlank(table.siteStatus)),
    check('tesla_superchargers_stall_count_check', nullOrAtLeast(table.stallCount, MIN_COUNT)),
    index('tesla_superchargers_active_country_region_idx')
      .on(table.country, table.region)
      .where(sql`deleted_at IS NULL`),
    uniqueIndex('tesla_superchargers_location_slug_uidx').on(table.locationSlug),
  ],
);

export const planetFitnessLocations = pgTable(
  'planet_fitness_locations',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    locationId: text('location_id').notNull(),
    name: text('name').notNull(),
    phone: text('phone'),
    infoUrl: text('info_url'),
    openingHours: text('opening_hours'),
    region: text('region'),
    country: char('country', { length: COUNTRY_CODE_LENGTH }),
    address: jsonb('address').default({}).notNull(),
    amenities: jsonb('amenities').default([]).notNull(),
    payload: jsonb('payload').default({}).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (table) => [
    check('planet_fitness_locations_address_check', jsonbObject(table.address)),
    check('planet_fitness_locations_amenities_check', jsonbArray(table.amenities)),
    check('planet_fitness_locations_location_id_check', nonBlank(table.locationId)),
    check('planet_fitness_locations_name_check', nonBlank(table.name)),
    check('planet_fitness_locations_payload_check', jsonbObject(table.payload)),
    index('planet_fitness_locations_active_region_idx')
      .on(table.country, table.region)
      .where(sql`deleted_at IS NULL`),
    uniqueIndex('planet_fitness_locations_location_id_uidx').on(table.locationId),
  ],
);

export const poiCampgrounds = pgTable(
  'poi_campgrounds',
  {
    poiId: bigint('poi_id', { mode: 'bigint' }).notNull(),
    campgroundId: bigint('campground_id', { mode: 'bigint' }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    primaryKey({ name: 'poi_campgrounds_pkey', columns: [table.poiId, table.campgroundId] }),
    foreignKey({
      name: 'poi_campgrounds_poi_id_fkey',
      columns: [table.poiId],
      foreignColumns: [pois.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'poi_campgrounds_campground_id_fkey',
      columns: [table.campgroundId],
      foreignColumns: [campgrounds.id],
    }).onDelete('cascade'),
    uniqueIndex('poi_campgrounds_campground_uidx').on(table.campgroundId),
  ],
);

export const poiTeslaSuperchargers = pgTable(
  'poi_tesla_superchargers',
  {
    poiId: bigint('poi_id', { mode: 'bigint' }).notNull(),
    teslaSuperchargerId: bigint('tesla_supercharger_id', { mode: 'bigint' }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    primaryKey({
      name: 'poi_tesla_superchargers_pkey',
      columns: [table.poiId, table.teslaSuperchargerId],
    }),
    foreignKey({
      name: 'poi_tesla_superchargers_poi_id_fkey',
      columns: [table.poiId],
      foreignColumns: [pois.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'poi_tesla_superchargers_tesla_supercharger_id_fkey',
      columns: [table.teslaSuperchargerId],
      foreignColumns: [teslaSuperchargers.id],
    }).onDelete('cascade'),
    uniqueIndex('poi_tesla_superchargers_supercharger_uidx').on(table.teslaSuperchargerId),
  ],
);

export const poiPlanetFitnessLocations = pgTable(
  'poi_planet_fitness_locations',
  {
    poiId: bigint('poi_id', { mode: 'bigint' }).notNull(),
    planetFitnessLocationId: bigint('planet_fitness_location_id', { mode: 'bigint' }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    primaryKey({
      name: 'poi_planet_fitness_locations_pkey',
      columns: [table.poiId, table.planetFitnessLocationId],
    }),
    foreignKey({
      name: 'poi_planet_fitness_locations_poi_id_fkey',
      columns: [table.poiId],
      foreignColumns: [pois.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'poi_planet_fitness_locations_planet_fitness_location_id_fkey',
      columns: [table.planetFitnessLocationId],
      foreignColumns: [planetFitnessLocations.id],
    }).onDelete('cascade'),
    uniqueIndex('poi_planet_fitness_locations_location_uidx').on(table.planetFitnessLocationId),
  ],
);
