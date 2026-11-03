import { BookingProvider } from './campground.js';
import { memberList } from './members.js';

export const DataProvider = {
  CAMPFLARE: 'campflare',
  RECGOV: 'recgov',
  ASPIRA: 'aspira',
  STRAPI: 'bcparks-strapi',
  RESERVEAMERICA: 'reserveamerica',
  RESERVECALIFORNIA: 'reservecalifornia',
  TESLA_SUPERCHARGER: 'tesla_supercharger',
  PLANET_FITNESS_LOCATION: 'planet_fitness_location',
} as const;

export type DataProvider = (typeof DataProvider)[keyof typeof DataProvider];

export const dataProviders: readonly DataProvider[] = memberList(DataProvider);

const SEGMENT_SEPARATOR = ':';
const LIST_SEPARATOR = ',';
const NULL_SENTINEL = 'null';
const UNKNOWN_TENANT_SENTINEL = 'unknown';
const NO_TENANT = '';
const CAMPGROUND_SEGMENT_COUNT = 2;
const CAMPSITE_ONLY_SEGMENT_COUNT = 1;
const MIN_ASPIRA_BOOKING_SEGMENTS = 3;
const ASPIRA_BOOKING_RESOURCE_SEGMENT = 3;
const LONG_PATTERN = /^[+-]?[0-9]+$/;
const LONG_MIN = -(2n ** 63n);
const LONG_MAX = 2n ** 63n - 1n;

export type LongId = bigint;

function toLongOrNull(text: string): LongId | null {
  if (!LONG_PATTERN.test(text)) return null;
  const value = BigInt(text);
  return value < LONG_MIN || value > LONG_MAX ? null : value;
}

function asLong(value: number | bigint, label: string): LongId {
  if (typeof value === 'bigint') return value;
  if (!Number.isSafeInteger(value)) throw new RangeError(`${label} must be a safe integer, got ${value}`);
  return BigInt(value);
}

export class RecGovDataRef {
  readonly kind = 'recgov';
  readonly provider = DataProvider.RECGOV;
  constructor(readonly id: string) {}
  serialize(): string {
    return this.id;
  }
}

export class CampflareDataRef {
  readonly kind = 'campflare';
  readonly provider = DataProvider.CAMPFLARE;
  constructor(readonly id: string) {}
  serialize(): string {
    return this.id;
  }
}

export class AspiraDataRef {
  readonly kind = 'aspira';
  readonly provider = DataProvider.ASPIRA;
  constructor(
    readonly transactionLocationId: LongId,
    readonly mapId: LongId,
  ) {}
  serialize(): string {
    return `${this.transactionLocationId}${SEGMENT_SEPARATOR}${this.mapId}`;
  }
}

export class AspiraCampsiteDataRef {
  readonly kind = 'aspira_campsite';
  readonly provider = DataProvider.ASPIRA;
  constructor(
    readonly tenant: string,
    readonly resourceLocationId: LongId,
  ) {}
  serialize(): string {
    return `${this.tenant}${SEGMENT_SEPARATOR}${this.resourceLocationId}`;
  }
}

export class BcParksDataRef {
  readonly kind = 'bcparks';
  readonly provider = DataProvider.STRAPI;
  constructor(
    readonly transactionLocationId: LongId,
    readonly mapId: LongId,
  ) {}
  serialize(): string {
    return `${this.transactionLocationId}${SEGMENT_SEPARATOR}${this.mapId}`;
  }
}

export class BcParksCampsiteDataRef {
  readonly kind = 'bcparks_campsite';
  readonly provider = DataProvider.STRAPI;
  constructor(
    readonly tenant: string,
    readonly resourceLocationId: LongId,
  ) {}
  serialize(): string {
    return `${this.tenant}${SEGMENT_SEPARATOR}${this.resourceLocationId}`;
  }
}

export class ReserveAmericaDataRef {
  readonly kind = 'reserveamerica';
  readonly provider = DataProvider.RESERVEAMERICA;
  constructor(readonly id: string) {}
  serialize(): string {
    return this.id;
  }
}

export class ReserveCaliforniaDataRef {
  readonly kind = 'reservecalifornia';
  readonly provider = DataProvider.RESERVECALIFORNIA;
  constructor(readonly id: string) {}
  serialize(): string {
    return this.id;
  }
}

export class TeslaSuperchargerDataRef {
  readonly kind = 'tesla_supercharger';
  readonly provider = DataProvider.TESLA_SUPERCHARGER;
  constructor(readonly locationId: string) {}
  serialize(): string {
    return this.locationId;
  }
}

export class PlanetFitnessLocationDataRef {
  readonly kind = 'planet_fitness_location';
  readonly provider = DataProvider.PLANET_FITNESS_LOCATION;
  constructor(readonly locationId: string) {}
  serialize(): string {
    return this.locationId;
  }
}

export type DataProviderRef =
  | RecGovDataRef
  | CampflareDataRef
  | AspiraDataRef
  | AspiraCampsiteDataRef
  | BcParksDataRef
  | BcParksCampsiteDataRef
  | ReserveAmericaDataRef
  | ReserveCaliforniaDataRef
  | TeslaSuperchargerDataRef
  | PlanetFitnessLocationDataRef;

export const DataProviderRef = {
  recGov: (id: string) => new RecGovDataRef(id),
  campflare: (id: string) => new CampflareDataRef(id),
  aspira: (transactionLocationId: number | bigint, mapId: number | bigint) =>
    new AspiraDataRef(asLong(transactionLocationId, 'transactionLocationId'), asLong(mapId, 'mapId')),
  aspiraCampsite: (tenant: string, resourceLocationId: number | bigint) =>
    new AspiraCampsiteDataRef(tenant, asLong(resourceLocationId, 'resourceLocationId')),
  bcParks: (transactionLocationId: number | bigint, mapId: number | bigint) =>
    new BcParksDataRef(asLong(transactionLocationId, 'transactionLocationId'), asLong(mapId, 'mapId')),
  bcParksCampsite: (tenant: string, resourceLocationId: number | bigint) =>
    new BcParksCampsiteDataRef(tenant, asLong(resourceLocationId, 'resourceLocationId')),
  reserveAmerica: (id: string) => new ReserveAmericaDataRef(id),
  reserveCalifornia: (id: string) => new ReserveCaliforniaDataRef(id),
  teslaSupercharger: (locationId: string) => new TeslaSuperchargerDataRef(locationId),
  planetFitnessLocation: (locationId: string) => new PlanetFitnessLocationDataRef(locationId),
} as const;

function parseTwoSegmentRef<Campground, Campsite>(
  ref: string,
  campground: (transactionLocationId: LongId, mapId: LongId) => Campground,
  campsite: (tenant: string, resourceLocationId: LongId) => Campsite,
): Campground | Campsite | null {
  const parts = ref.split(SEGMENT_SEPARATOR);
  if (parts.length === CAMPGROUND_SEGMENT_COUNT) {
    const first = toLongOrNull(parts[0]!);
    if (first !== null) {
      const mapId = toLongOrNull(parts[1]!);
      return mapId === null ? null : campground(first, mapId);
    }
    const resourceLocationId = toLongOrNull(parts[1]!);
    return resourceLocationId === null ? null : campsite(parts[0]!, resourceLocationId);
  }
  if (parts.length === CAMPSITE_ONLY_SEGMENT_COUNT) {
    const resourceLocationId = toLongOrNull(parts[0]!);
    return resourceLocationId === null ? null : campsite(NO_TENANT, resourceLocationId);
  }
  return null;
}

export function parseDataProviderRef(provider: DataProvider, ref: string): DataProviderRef | null {
  switch (provider) {
    case DataProvider.RECGOV:
      return DataProviderRef.recGov(ref);
    case DataProvider.CAMPFLARE:
      return DataProviderRef.campflare(ref);
    case DataProvider.ASPIRA:
      return parseTwoSegmentRef<AspiraDataRef, AspiraCampsiteDataRef>(
        ref,
        (transactionLocationId, mapId) => new AspiraDataRef(transactionLocationId, mapId),
        (tenant, resourceLocationId) => new AspiraCampsiteDataRef(tenant, resourceLocationId),
      );
    case DataProvider.STRAPI:
      return parseTwoSegmentRef<BcParksDataRef, BcParksCampsiteDataRef>(
        ref,
        (transactionLocationId, mapId) => new BcParksDataRef(transactionLocationId, mapId),
        (tenant, resourceLocationId) => new BcParksCampsiteDataRef(tenant, resourceLocationId),
      );
    case DataProvider.RESERVEAMERICA:
      return DataProviderRef.reserveAmerica(ref);
    case DataProvider.RESERVECALIFORNIA:
      return DataProviderRef.reserveCalifornia(ref);
    case DataProvider.TESLA_SUPERCHARGER:
      return DataProviderRef.teslaSupercharger(ref);
    case DataProvider.PLANET_FITNESS_LOCATION:
      return DataProviderRef.planetFitnessLocation(ref);
  }
}

export class AspiraBookingRef {
  readonly kind = 'aspira';
  readonly provider = BookingProvider.ASPIRA;
  constructor(
    readonly tenant: string | null,
    readonly transactionLocationId: LongId,
    readonly mapId: LongId,
    readonly resourceLocationId: LongId | null,
  ) {}
  get parentRefKey(): string {
    return String(this.mapId);
  }
  serialize(): string {
    return [
      this.tenant ?? UNKNOWN_TENANT_SENTINEL,
      String(this.transactionLocationId),
      String(this.mapId),
      this.resourceLocationId === null ? NULL_SENTINEL : String(this.resourceLocationId),
    ].join(SEGMENT_SEPARATOR);
  }
}

export class RecGovBookingRef {
  readonly kind = 'recgov';
  readonly provider = BookingProvider.RECGOV;
  constructor(readonly facilityId: string) {}
  get parentRefKey(): string {
    return this.facilityId;
  }
  serialize(): string {
    return this.facilityId;
  }
}

export class CampflareBookingRef {
  readonly kind = 'campflare';
  readonly provider = BookingProvider.CAMPFLARE;
  constructor(readonly campgroundId: string) {}
  get parentRefKey(): string {
    return this.campgroundId;
  }
  serialize(): string {
    return this.campgroundId;
  }
}

export class ReserveAmericaBookingRef {
  readonly kind = 'reserveamerica';
  readonly provider = BookingProvider.RESERVEAMERICA;
  constructor(
    readonly contractCode: string | null,
    readonly parkId: string,
  ) {}
  get parentRefKey(): string {
    return this.parkId;
  }
  serialize(): string {
    return `${this.contractCode ?? NULL_SENTINEL}${SEGMENT_SEPARATOR}${this.parkId}`;
  }
}

export class ReserveCaliforniaBookingRef {
  readonly kind = 'reservecalifornia';
  readonly provider = BookingProvider.RESERVECALIFORNIA;
  constructor(
    readonly placeId: LongId,
    readonly facilityIds: readonly LongId[],
  ) {}
  get parentRefKey(): string {
    return this.facilityIds.join(LIST_SEPARATOR);
  }
  serialize(): string {
    return `${this.placeId}${SEGMENT_SEPARATOR}${this.facilityIds.join(LIST_SEPARATOR)}`;
  }
}

export type BookingProviderRef =
  | AspiraBookingRef
  | RecGovBookingRef
  | CampflareBookingRef
  | ReserveAmericaBookingRef
  | ReserveCaliforniaBookingRef;

export const BookingProviderRef = {
  aspira: (
    tenant: string | null,
    transactionLocationId: number | bigint,
    mapId: number | bigint,
    resourceLocationId: number | bigint | null,
  ) =>
    new AspiraBookingRef(
      tenant,
      asLong(transactionLocationId, 'transactionLocationId'),
      asLong(mapId, 'mapId'),
      resourceLocationId === null ? null : asLong(resourceLocationId, 'resourceLocationId'),
    ),
  recGov: (facilityId: string) => new RecGovBookingRef(facilityId),
  campflare: (campgroundId: string) => new CampflareBookingRef(campgroundId),
  reserveAmerica: (contractCode: string | null, parkId: string) =>
    new ReserveAmericaBookingRef(contractCode, parkId),
  reserveCalifornia: (placeId: number | bigint, facilityIds: readonly (number | bigint)[]) =>
    new ReserveCaliforniaBookingRef(
      asLong(placeId, 'placeId'),
      facilityIds.map((id) => asLong(id, 'facilityId')),
    ),
} as const;

function isSentinel(text: string): boolean {
  return text === UNKNOWN_TENANT_SENTINEL || text === NULL_SENTINEL;
}

function parseAspiraBookingRef(ref: string): AspiraBookingRef | null {
  const parts = ref.split(SEGMENT_SEPARATOR);
  if (parts.length < MIN_ASPIRA_BOOKING_SEGMENTS) return null;
  const tenant = isSentinel(parts[0]!) ? null : parts[0]!;
  const transactionLocationId = toLongOrNull(parts[1]!);
  if (transactionLocationId === null) return null;
  const mapId = toLongOrNull(parts[2]!);
  if (mapId === null) return null;
  const resourceSegment = parts[ASPIRA_BOOKING_RESOURCE_SEGMENT];
  const resourceLocationId =
    resourceSegment === undefined || resourceSegment === NULL_SENTINEL ? null : toLongOrNull(resourceSegment);
  return new AspiraBookingRef(tenant, transactionLocationId, mapId, resourceLocationId);
}

function parseReserveAmericaBookingRef(ref: string): ReserveAmericaBookingRef | null {
  const index = ref.indexOf(SEGMENT_SEPARATOR);
  if (index < 0) return null;
  const contractCode = ref.slice(0, index);
  return new ReserveAmericaBookingRef(
    contractCode === NULL_SENTINEL ? null : contractCode,
    ref.slice(index + SEGMENT_SEPARATOR.length),
  );
}

function parseReserveCaliforniaBookingRef(ref: string): ReserveCaliforniaBookingRef | null {
  const index = ref.indexOf(SEGMENT_SEPARATOR);
  if (index < 0) return null;
  const placeId = toLongOrNull(ref.slice(0, index));
  if (placeId === null) return null;
  const facilityIds = ref
    .slice(index + SEGMENT_SEPARATOR.length)
    .split(LIST_SEPARATOR)
    .map(toLongOrNull)
    .filter((id): id is LongId => id !== null);
  return facilityIds.length === 0 ? null : new ReserveCaliforniaBookingRef(placeId, facilityIds);
}

export function parseBookingProviderRef(provider: BookingProvider, ref: string): BookingProviderRef | null {
  switch (provider) {
    case BookingProvider.ASPIRA:
      return parseAspiraBookingRef(ref);
    case BookingProvider.RECGOV:
      return BookingProviderRef.recGov(ref);
    case BookingProvider.CAMPFLARE:
      return BookingProviderRef.campflare(ref);
    case BookingProvider.RESERVEAMERICA:
      return parseReserveAmericaBookingRef(ref);
    case BookingProvider.RESERVECALIFORNIA:
      return parseReserveCaliforniaBookingRef(ref);
  }
}
