import { isDeepStrictEqual } from 'node:util';
import {
  amenityKeys,
  bookingProviders,
  carriers,
  type Address,
  type BookingAlias,
  type CampgroundAlert,
  type CampgroundAmenity,
  type CampgroundLocation,
  type CampgroundMetadata,
  type CampgroundPrice,
  type CampgroundRating,
  type CampgroundSchedule,
  type CampsiteAttribute,
  type CarrierSignal,
  type CatalogPhoto,
} from '@roadtrip/domain';

export type JsonObject = Readonly<Record<string, unknown>>;

type Presence =
  | { readonly kind: 'required' }
  | { readonly kind: 'optional' }
  | { readonly kind: 'defaulted'; readonly value: unknown };

export interface Field {
  readonly shape: Shape<unknown>;
  readonly presence: Presence;
}

type Spec =
  | { readonly kind: 'string'; readonly nonBlank: boolean }
  | { readonly kind: 'number' }
  | { readonly kind: 'integer' }
  | { readonly kind: 'boolean' }
  | { readonly kind: 'enum'; readonly values: readonly string[] }
  | { readonly kind: 'object'; readonly fields: Readonly<Record<string, Field>> }
  | { readonly kind: 'array'; readonly item: Shape<unknown>; readonly lenient: boolean }
  | { readonly kind: 'opaque' };

export interface Shape<T> {
  readonly spec: Spec;
  readonly phantom?: T;
}

export class CatalogJsonError extends Error {
  constructor(path: string, problem: string) {
    super(`${path}: ${problem}`);
    this.name = 'CatalogJsonError';
  }
}

const ROOT_PATH = '$';
const DEFAULT_AMENITY_PRESENT = true;
const NO_ACTIVITIES: readonly string[] = [];

const required = (shape: Shape<unknown>): Field => ({ shape, presence: { kind: 'required' } });
const optional = (shape: Shape<unknown>): Field => ({ shape, presence: { kind: 'optional' } });
const defaulted = (shape: Shape<unknown>, value: unknown): Field => ({
  shape,
  presence: { kind: 'defaulted', value },
});

const stringShape: Shape<string> = { spec: { kind: 'string', nonBlank: false } };
const nonBlankStringShape: Shape<string> = { spec: { kind: 'string', nonBlank: true } };
const numberShape: Shape<number> = { spec: { kind: 'number' } };
const integerShape: Shape<number> = { spec: { kind: 'integer' } };
const booleanShape: Shape<boolean> = { spec: { kind: 'boolean' } };

const enumOf = <T extends string>(values: readonly T[]): Shape<T> => ({
  spec: { kind: 'enum', values },
});

const objectOf = <T extends object>(fields: { readonly [K in keyof T]-?: Field }): Shape<T> => ({
  spec: { kind: 'object', fields },
});

const arrayOf = <T>(item: Shape<T>, options: { readonly lenient?: boolean } = {}): Shape<T[]> => ({
  spec: { kind: 'array', item, lenient: options.lenient ?? false },
});

export const addressShape: Shape<Address> = objectOf<Address>({
  street: optional(stringShape),
  city: optional(stringShape),
  state: optional(stringShape),
  postcode: optional(stringShape),
  country: optional(stringShape),
  full: optional(stringShape),
});

export const locationShape: Shape<CampgroundLocation> = objectOf<CampgroundLocation>({
  latitude: optional(numberShape),
  longitude: optional(numberShape),
  region: optional(stringShape),
  country: optional(stringShape),
  elevation: optional(numberShape),
  directions: optional(stringShape),
  address: optional(addressShape),
});

export const amenityShape: Shape<CampgroundAmenity> = objectOf<CampgroundAmenity>({
  key: required(enumOf(amenityKeys)),
  present: defaulted(booleanShape, DEFAULT_AMENITY_PRESENT),
  detail: optional(stringShape),
});

export const amenitiesShape: Shape<CampgroundAmenity[]> = arrayOf(amenityShape);

export const priceShape: Shape<CampgroundPrice> = objectOf<CampgroundPrice>({
  minimum: optional(numberShape),
  maximum: optional(numberShape),
  currency: optional(stringShape),
});

export const scheduleShape: Shape<CampgroundSchedule> = objectOf<CampgroundSchedule>({
  check_in: optional(stringShape),
  check_out: optional(stringShape),
});

export const alertShape: Shape<CampgroundAlert> = objectOf<CampgroundAlert>({
  title: optional(stringShape),
  body: required(stringShape),
  ends_on: optional(stringShape),
  source_url: optional(stringShape),
});

export const alertsShape: Shape<CampgroundAlert[]> = arrayOf(alertShape);

const ratingShape: Shape<CampgroundRating> = objectOf<CampgroundRating>({
  average: required(numberShape),
  count: required(integerShape),
});

export const metadataShape: Shape<CampgroundMetadata> = objectOf<CampgroundMetadata>({
  activities: defaulted(arrayOf(stringShape), NO_ACTIVITIES),
  rating: optional(ratingShape),
  last_updated: optional(stringShape),
});

export const carrierSignalShape: Shape<CarrierSignal> = objectOf<CarrierSignal>({
  carrier: required(enumOf(carriers)),
  average: required(numberShape),
  count: optional(integerShape),
});

export const cellServiceShape: Shape<CarrierSignal[]> = arrayOf(carrierSignalShape);

export const photoShape: Shape<CatalogPhoto> = objectOf<CatalogPhoto>({
  url: required(stringShape),
});

export const photosShape: Shape<CatalogPhoto[]> = arrayOf(photoShape);

export const attributeShape: Shape<CampsiteAttribute> = objectOf<CampsiteAttribute>({
  name: required(stringShape),
  value: optional(stringShape),
});

export const attributesShape: Shape<CampsiteAttribute[]> = arrayOf(attributeShape);

export const equipmentShape: Shape<string[]> = arrayOf(stringShape);

export const bookingAliasShape: Shape<BookingAlias> = objectOf<BookingAlias>({
  provider: required(enumOf(bookingProviders)),
  ref: required(nonBlankStringShape),
});

export const bookingAliasesShape: Shape<BookingAlias[]> = arrayOf(bookingAliasShape, {
  lenient: true,
});

export const sourcePayloadShape: Shape<JsonObject> = { spec: { kind: 'opaque' } };

const isPlainObject = (value: unknown): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const isAbsent = (value: unknown): value is null | undefined =>
  value === null || value === undefined;

const childPath = (path: string, name: string) => `${path}.${name}`;
const itemPath = (path: string, index: number) => `${path}[${index}]`;

function checkScalar(spec: Spec, value: unknown, path: string): unknown {
  switch (spec.kind) {
    case 'string':
      if (typeof value !== 'string') throw new CatalogJsonError(path, 'expected a string');
      if (spec.nonBlank && value.trim().length === 0)
        throw new CatalogJsonError(path, 'expected a non-blank string');
      return value;
    case 'number':
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        throw new CatalogJsonError(path, 'expected a finite number');
      }
      return value;
    case 'integer':
      if (typeof value !== 'number' || !Number.isInteger(value)) {
        throw new CatalogJsonError(path, 'expected an integer');
      }
      return value;
    case 'boolean':
      if (typeof value !== 'boolean') throw new CatalogJsonError(path, 'expected a boolean');
      return value;
    case 'enum':
      if (typeof value !== 'string' || !spec.values.includes(value)) {
        throw new CatalogJsonError(path, `expected one of ${spec.values.join(', ')}`);
      }
      return value;
    default:
      throw new CatalogJsonError(path, `${spec.kind} is not a scalar`);
  }
}

function encode(spec: Spec, value: unknown, path: string): unknown {
  switch (spec.kind) {
    case 'object': {
      if (!isPlainObject(value)) throw new CatalogJsonError(path, 'expected an object');
      const written: Record<string, unknown> = {};
      for (const [name, field] of Object.entries(spec.fields)) {
        const member = value[name];
        if (isAbsent(member)) {
          if (field.presence.kind === 'required')
            throw new CatalogJsonError(childPath(path, name), 'is required');
          continue;
        }
        const encoded = encode(field.shape.spec, member, childPath(path, name));
        if (field.presence.kind === 'defaulted' && isDeepStrictEqual(encoded, field.presence.value))
          continue;
        written[name] = encoded;
      }
      return written;
    }
    case 'array':
      if (!Array.isArray(value)) throw new CatalogJsonError(path, 'expected an array');
      return value.map((entry, index) => encode(spec.item.spec, entry, itemPath(path, index)));
    case 'opaque':
      if (!isPlainObject(value)) throw new CatalogJsonError(path, 'expected an object');
      return value;
    default:
      return checkScalar(spec, value, path);
  }
}

function decode(spec: Spec, raw: unknown, path: string): unknown {
  switch (spec.kind) {
    case 'object': {
      if (!isPlainObject(raw)) throw new CatalogJsonError(path, 'expected an object');
      const read: Record<string, unknown> = {};
      for (const [name, field] of Object.entries(spec.fields)) {
        const member = raw[name];
        if (isAbsent(member)) {
          if (field.presence.kind === 'required')
            throw new CatalogJsonError(childPath(path, name), 'is required');
          if (field.presence.kind === 'defaulted')
            read[name] = structuredClone(field.presence.value);
          continue;
        }
        read[name] = decode(field.shape.spec, member, childPath(path, name));
      }
      return read;
    }
    case 'array': {
      if (!Array.isArray(raw)) throw new CatalogJsonError(path, 'expected an array');
      const read: unknown[] = [];
      raw.forEach((entry, index) => {
        try {
          read.push(decode(spec.item.spec, entry, itemPath(path, index)));
        } catch (error) {
          if (!spec.lenient || !(error instanceof CatalogJsonError)) throw error;
        }
      });
      return read;
    }
    case 'opaque':
      if (!isPlainObject(raw)) throw new CatalogJsonError(path, 'expected an object');
      return raw;
    default:
      return checkScalar(spec, raw, path);
  }
}

function absentValue(spec: Spec): unknown {
  switch (spec.kind) {
    case 'array':
      return [];
    case 'object':
    case 'opaque':
      return {};
    default:
      return undefined;
  }
}

export function encodeCatalogJson<T>(shape: Shape<T>, value: T): unknown {
  return encode(shape.spec, value, ROOT_PATH);
}

export function decodeCatalogJson<T>(shape: Shape<T>, raw: unknown): T {
  const input = isAbsent(raw) ? absentValue(shape.spec) : raw;
  return decode(shape.spec, input, ROOT_PATH) as T;
}
