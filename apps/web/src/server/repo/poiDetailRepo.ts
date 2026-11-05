import {
  alertsShape,
  amenitiesShape,
  cellServiceShape,
  contactShape,
  linksShape,
  locationShape,
  managementShape,
  metadataShape,
  photosShape,
  priceShape,
  scheduleShape,
  type Db,
} from '@roadtrip/db';
import type {
  CampgroundAlert,
  CampgroundAmenity,
  CampgroundContact,
  CampgroundLink,
  CampgroundLocation,
  CampgroundManagement,
  CampgroundMetadata,
  CampgroundPrice,
  CampgroundSchedule,
  CarrierSignal,
  CatalogPhoto,
} from '@roadtrip/domain';
import { sql } from 'drizzle-orm';
import { createColumnDecoder } from './columnDecoder';
import { campgroundProviderFilter } from './poiServingRepo';

export interface PoiIndexRow {
  id: bigint;
  category: string;
  geometry: unknown;
}

export interface CampgroundDetailRow {
  source: string;
  sourceId: string;
  name: string;
  parentName: string | null;
  kind: string | null;
  status: string | null;
  statusDescription: string | null;
  shortDescription: string | null;
  mediumDescription: string | null;
  longDescription: string | null;
  reservationUrl: string | null;
  maxRvLength: number | null;
  maxTrailerLength: number | null;
  hasPullThroughSites: boolean | null;
  bigRigFriendly: boolean | null;
  location: CampgroundLocation | null;
  amenities: CampgroundAmenity[];
  links: CampgroundLink[];
  photos: CatalogPhoto[];
  alerts: CampgroundAlert[];
  cellService: CarrierSignal[];
  metadata: CampgroundMetadata | null;
  management: CampgroundManagement | null;
  contact: CampgroundContact | null;
  schedule: CampgroundSchedule | null;
  price: CampgroundPrice | null;
  connections: unknown;
  upstream: unknown;
}

export interface TeslaDetailRow {
  slug: string;
  name: string;
  region: string | null;
  country: string | null;
  infoUrl: string | null;
  address: unknown;
  status: string | null;
  timeZone: string | null;
  amenities: unknown;
  stallCount: number | null;
  maxPowerKw: number | null;
  pricebooks: unknown;
  availabilityProfile: unknown;
  openToNonTeslas: boolean | null;
  trailerFriendly: boolean | null;
  twentyFourSeven: boolean | null;
  indexPayload: unknown;
  detailPayload: unknown;
  raw: unknown;
}

export interface PlanetFitnessDetailRow {
  locationId: string;
  name: string;
  region: string | null;
  country: string | null;
  phone: string | null;
  infoUrl: string | null;
  openingHours: string | null;
  address: unknown;
  payload: unknown;
  raw: unknown;
}

export async function findPoiIndex(db: Db, id: bigint): Promise<PoiIndexRow | null> {
  const rows = await db.execute<{ id: string; category: string; geometry: string }>(sql`
    SELECT id, poi_type AS category, ST_AsGeoJSON(geom) AS geometry
    FROM pois
    WHERE id = ${id} AND deleted_at IS NULL
  `);
  const row = rows[0];
  return row === undefined ? null : { id: BigInt(row.id), category: row.category, geometry: JSON.parse(row.geometry) };
}

export async function findCampgroundDetail(db: Db, poiId: bigint, providers: readonly string[]): Promise<CampgroundDetailRow | null> {
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT cg.data_provider, cg.data_provider_ref, cg.name, cg.parent_name, cg.kind, cg.status, cg.status_description,
           cg.short_description, cg.medium_description, cg.long_description, cg.reservation_url, cg.max_rv_length,
           cg.max_trailer_length, cg.has_pull_through_sites, cg.big_rig_friendly, cg.location, cg.amenities, cg.links,
           cg.photos, cg.alerts, cg.cell_service, cg.metadata, cg.management, cg.contact, cg.default_campsite_schedule,
           cg.price, cg.connections, COALESCE(cg.source_payload, '{}'::jsonb) AS upstream
    FROM poi_campgrounds pc
    JOIN pois p ON p.id = pc.poi_id
    JOIN campgrounds cg ON cg.id = pc.campground_id
    WHERE pc.poi_id = ${poiId}
      AND cg.deleted_at IS NULL
      AND p.deleted_at IS NULL
      ${campgroundProviderFilter(providers)}
    ORDER BY cg.id
  `);
  const row = rows[0];
  if (row === undefined) return null;
  const decoder = createColumnDecoder();
  return {
    source: row.data_provider as string,
    sourceId: row.data_provider_ref as string,
    name: row.name as string,
    parentName: row.parent_name as string | null,
    kind: row.kind as string | null,
    status: row.status as string | null,
    statusDescription: row.status_description as string | null,
    shortDescription: row.short_description as string | null,
    mediumDescription: row.medium_description as string | null,
    longDescription: row.long_description as string | null,
    reservationUrl: row.reservation_url as string | null,
    maxRvLength: row.max_rv_length as number | null,
    maxTrailerLength: row.max_trailer_length as number | null,
    hasPullThroughSites: row.has_pull_through_sites as boolean | null,
    bigRigFriendly: row.big_rig_friendly as boolean | null,
    location: decoder.decode('location', locationShape, row.location),
    amenities: decoder.decode('amenities', amenitiesShape, row.amenities) ?? [],
    links: decoder.decode('links', linksShape, row.links) ?? [],
    photos: decoder.decode('photos', photosShape, row.photos) ?? [],
    alerts: decoder.decode('alerts', alertsShape, row.alerts) ?? [],
    cellService: decoder.decode('cell_service', cellServiceShape, row.cell_service) ?? [],
    metadata: decoder.decode('metadata', metadataShape, row.metadata),
    management: decoder.decode('management', managementShape, row.management),
    contact: decoder.decode('contact', contactShape, row.contact),
    schedule: decoder.decode('default_campsite_schedule', scheduleShape, row.default_campsite_schedule),
    price: decoder.decode('price', priceShape, row.price),
    connections: row.connections,
    upstream: row.upstream,
  };
}

export async function findTeslaDetail(db: Db, poiId: bigint): Promise<TeslaDetailRow | null> {
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT ts.location_slug, ts.common_site_name, ts.region, ts.country, ts.info_url, ts.address, ts.site_status,
           ts.time_zone, ts.amenities, ts.stall_count, ts.max_power_kw, ts.pricebooks, ts.availability_profile,
           ts.open_to_non_teslas, ts.trailer_friendly, ts.twenty_four_seven, ts.index_payload, ts.detail_payload,
           to_jsonb(ts) AS raw
    FROM tesla_superchargers ts
    JOIN poi_tesla_superchargers pts ON pts.tesla_supercharger_id = ts.id
    JOIN pois p ON p.id = pts.poi_id
    WHERE pts.poi_id = ${poiId} AND ts.deleted_at IS NULL AND p.deleted_at IS NULL
  `);
  const row = rows[0];
  if (row === undefined) return null;
  return {
    slug: row.location_slug as string,
    name: row.common_site_name as string,
    region: row.region as string | null,
    country: row.country as string | null,
    infoUrl: row.info_url as string | null,
    address: row.address,
    status: row.site_status as string | null,
    timeZone: row.time_zone as string | null,
    amenities: row.amenities,
    stallCount: row.stall_count as number | null,
    maxPowerKw: row.max_power_kw as number | null,
    pricebooks: row.pricebooks,
    availabilityProfile: row.availability_profile,
    openToNonTeslas: row.open_to_non_teslas as boolean | null,
    trailerFriendly: row.trailer_friendly as boolean | null,
    twentyFourSeven: row.twenty_four_seven as boolean | null,
    indexPayload: row.index_payload,
    detailPayload: row.detail_payload,
    raw: row.raw,
  };
}

export async function findPlanetFitnessDetail(db: Db, poiId: bigint): Promise<PlanetFitnessDetailRow | null> {
  const rows = await db.execute<Record<string, unknown>>(sql`
    SELECT pfl.location_id, pfl.name, pfl.region, pfl.country, pfl.phone, pfl.info_url, pfl.opening_hours, pfl.address,
           pfl.payload, to_jsonb(pfl) AS raw
    FROM planet_fitness_locations pfl
    JOIN poi_planet_fitness_locations ppf ON ppf.planet_fitness_location_id = pfl.id
    JOIN pois p ON p.id = ppf.poi_id
    WHERE ppf.poi_id = ${poiId} AND pfl.deleted_at IS NULL AND p.deleted_at IS NULL
  `);
  const row = rows[0];
  if (row === undefined) return null;
  return {
    locationId: row.location_id as string,
    name: row.name as string,
    region: row.region as string | null,
    country: row.country as string | null,
    phone: row.phone as string | null,
    infoUrl: row.info_url as string | null,
    openingHours: row.opening_hours as string | null,
    address: row.address,
    payload: row.payload,
    raw: row.raw,
  };
}
