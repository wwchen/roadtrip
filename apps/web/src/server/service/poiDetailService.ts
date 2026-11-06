import { CAMPGROUND_POI_TYPE, type Db } from '@roadtrip/db';
import { bookingIdentities, shippedTenantRegistry } from '@roadtrip/domain';
import { config } from '../config';
import type { PoiCategoryDetailSchema, PoiDetailFeatureSchema, PoiDetailPropertiesSchema } from '../contract';
import { toContractId } from '../contractIds';
import { serverDb } from '../db';
import {
  findCampgroundDetail,
  findPlanetFitnessDetail,
  findPoiIndex,
  findTeslaDetail,
  type CampgroundDetailRow,
  type PlanetFitnessDetailRow,
  type TeslaDetailRow,
} from '../repo/poiDetailRepo';
import { bookingSystem, campgroundBookingRef, computeCtas } from './booking/campgroundCta';
import { poiDateContext } from './booking/dateContext';
import {
  alertDto,
  amenityDtos,
  bookingSiteOf,
  carrierSignalDto,
  nonBlank,
  priceDto,
  ratingDto,
  scheduleDto,
} from './campgroundContract';

const TESLA_POI_TYPE = 'tesla_supercharger';
const PLANET_FITNESS_POI_TYPE = 'planet_fitness_location';
const PLANET_FITNESS_BRAND = 'Planet Fitness';
const PAYLOAD_TAGS_KEY = 'tags';
const FEATURE = 'Feature';

type Defined<T> = { [K in keyof T]: Exclude<T[K], null | undefined> };

function present<T extends Record<string, unknown>>(fields: T): Partial<Defined<T>> {
  return Object.fromEntries(Object.entries(fields).filter(([, value]) => value !== null && value !== undefined)) as Partial<Defined<T>>;
}

const emptyLists = () => ({ amenities: [], cell_coverage: [], activities: [], alerts: [], charger_amenities: [] });

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

function campgroundBooking(row: CampgroundDetailRow, now: Date) {
  const tenants = shippedTenantRegistry();
  const identities = bookingIdentities(row.bookingProvider, row.bookingProviderRef, row.bookingAliases);
  const bookingRef = campgroundBookingRef(tenants, identities);
  const cta = computeCtas(tenants, {
    bookingRef,
    reserveUrl: row.reservationUrl,
    infoUrl: row.links[0]?.url ?? null,
    dateContext: poiDateContext(row.location?.latitude, row.location?.longitude, now),
    identities,
  });
  return { cta: cta.length === 0 ? null : cta, booking_system: bookingSystem(tenants, bookingRef) };
}

function campgroundProperties(row: CampgroundDetailRow, now: Date): PoiDetailPropertiesSchema {
  const parentName =
    row.parentName !== null && row.parentName.trim().toLowerCase() !== row.name.trim().toLowerCase() ? row.parentName : null;
  const detail: PoiCategoryDetailSchema = {
    ...emptyLists(),
    sources: [row.source],
    ...present({
      reserve_url: row.reservationUrl,
      booking_site: row.reservationUrl === null ? null : bookingSiteOf(row.reservationUrl),
      phone: row.contact?.phone,
      info_url: row.links[0]?.url,
      description: row.mediumDescription ?? row.shortDescription ?? row.longDescription,
      photo_url: row.photos[0]?.url,
      status: row.status,
      status_description: row.statusDescription,
      kind: row.kind,
      parent_name: parentName,
      rating: ratingDto(row.metadata?.rating),
      price: row.price === null ? null : priceDto(row.price),
      schedule: row.schedule === null ? null : scheduleDto(row.schedule),
      max_rv_length: row.maxRvLength,
      max_trailer_length: row.maxTrailerLength,
      has_pull_through_sites: row.hasPullThroughSites,
      big_rig_friendly: row.bigRigFriendly,
      email: row.contact?.email,
      elevation: row.location?.elevation,
      last_verified: nonBlank(row.metadata?.last_updated),
      ...campgroundBooking(row, now),
    }),
    address: row.location ?? {},
    upstream: row.upstream,
    amenities: amenityDtos(row.amenities),
    cell_coverage: row.cellService.map(carrierSignalDto),
    activities: row.metadata?.activities ?? [],
    links: row.links,
    alerts: row.alerts.map(alertDto),
    connections: row.connections,
    management: row.management ?? {},
    contact: row.contact ?? {},
  };
  return {
    source: row.source,
    source_id: row.sourceId,
    category: CAMPGROUND_POI_TYPE,
    name: row.name,
    ...present({
      subcategory: row.kind,
      agency: row.management?.agency,
      region: row.location?.region,
      country: row.location?.country,
    }),
    detail,
  };
}

function chargerAmenities(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((entry) =>
    typeof entry === 'string' ? [entry] : typeof entry === 'number' || typeof entry === 'boolean' ? [String(entry)] : [],
  );
}

function teslaProperties(row: TeslaDetailRow): PoiDetailPropertiesSchema {
  return {
    source: TESLA_POI_TYPE,
    source_id: row.slug,
    category: TESLA_POI_TYPE,
    name: row.name,
    ...present({ region: row.region, country: row.country }),
    detail: {
      ...emptyLists(),
      sources: [],
      ...present({
        info_url: row.infoUrl,
        address: row.address,
        status: row.status,
        time_zone: row.timeZone,
        stall_count: row.stallCount,
        power_kilowatt: row.maxPowerKw,
        pricebooks: row.pricebooks,
        availability_profile: row.availabilityProfile,
        open_to_non_teslas: row.openToNonTeslas,
        trailer_friendly: row.trailerFriendly,
        twenty_four_seven: row.twentyFourSeven,
      }),
      raw: row.raw,
      charger_amenities: chargerAmenities(row.amenities),
      upstream: { index: row.indexPayload, detail: row.detailPayload },
    },
  };
}

function planetFitnessProperties(row: PlanetFitnessDetailRow): PoiDetailPropertiesSchema {
  const tags = isObject(row.payload) ? row.payload[PAYLOAD_TAGS_KEY] : undefined;
  return {
    source: PLANET_FITNESS_POI_TYPE,
    source_id: row.locationId,
    category: PLANET_FITNESS_POI_TYPE,
    name: row.name,
    ...present({ region: row.region, country: row.country }),
    detail: {
      ...emptyLists(),
      sources: [],
      ...present({
        phone: row.phone,
        info_url: row.infoUrl,
        opening_hours: row.openingHours,
        address: row.address,
        upstream: isObject(tags) && Object.keys(tags).length > 0 ? tags : null,
      }),
      brand: PLANET_FITNESS_BRAND,
      raw: row.raw,
    },
  };
}

async function propertiesFor(
  db: Db,
  category: string,
  id: bigint,
  providers: readonly string[],
  now: Date,
): Promise<PoiDetailPropertiesSchema | null> {
  if (category === CAMPGROUND_POI_TYPE) {
    const row = await findCampgroundDetail(db, id, providers);
    return row === null ? null : campgroundProperties(row, now);
  }
  if (category === TESLA_POI_TYPE) {
    const row = await findTeslaDetail(db, id);
    return row === null ? null : teslaProperties(row);
  }
  if (category === PLANET_FITNESS_POI_TYPE) {
    const row = await findPlanetFitnessDetail(db, id);
    return row === null ? null : planetFitnessProperties(row);
  }
  return null;
}

export async function poiDetail(
  db: Db,
  id: bigint,
  providers: readonly string[],
  now: Date = new Date(),
): Promise<PoiDetailFeatureSchema | null> {
  const poi = await findPoiIndex(db, id);
  if (poi === null) return null;
  const properties = await propertiesFor(db, poi.category, poi.id, providers, now);
  if (properties === null) return null;
  return { type: FEATURE, id: toContractId(poi.id), geometry: poi.geometry, properties };
}

export async function getPoiDetail(id: bigint): Promise<PoiDetailFeatureSchema | null> {
  return poiDetail(await serverDb(), id, config.enabledDataProviders);
}
