import {
  arrayOrEmpty,
  asObject,
  fieldOf,
  isJsonObject,
  nullableString,
  type JsonObject,
} from '../../etl/json-decode.js';
import { contentOrNull } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText, kotlinLongFromText } from '../../etl/kotlin-number.js';

export interface RidbFacilityAddress {
  facilityStreetAddress1: string | null;
  city: string | null;
  addressStateCode: string | null;
  postalCode: string | null;
  addressCountryCode: string | null;
}

export interface RidbFacility {
  facilityId: string;
  facilityName: string | null;
  facilityLatitude: number | null;
  facilityLongitude: number | null;
  facilityPhone: string | null;
  facilityReservationUrl: string | null;
  facilityAddress: RidbFacilityAddress[] | null;
}

export interface RecGovDto {
  rows: RidbFacility[];
  rawById: ReadonlyMap<string, JsonObject>;
  enrichmentById: ReadonlyMap<string, JsonObject>;
}

const RECDATA_KEY = 'RECDATA';
const FACILITY_ID_KEY = 'FacilityID';
const FACILITY_ADDRESS_KEY = 'FACILITYADDRESS';

function mismatch(key: string, expected: string): never {
  throw new Error(`${key}: expected ${expected}`);
}

function presentValue(source: JsonObject, key: string): unknown {
  const value = fieldOf(source, key);
  return value === null ? undefined : value;
}

function primitiveContent(key: string, value: unknown, expected: string): string {
  if (isJsonObject(value) || Array.isArray(value)) mismatch(key, expected);
  const content = contentOrNull(value);
  if (content === null) mismatch(key, expected);
  return content;
}

function longIdOf(key: string, value: unknown): string {
  const parsed = kotlinLongFromText(primitiveContent(key, value, 'a long'));
  if (parsed === null) mismatch(key, 'a long');
  return parsed.toString();
}

function nullableDoubleOf(source: JsonObject, key: string): number | null {
  const value = presentValue(source, key);
  if (value === undefined) return null;
  const parsed = kotlinDoubleFromText(primitiveContent(key, value, 'a double'));
  if (parsed === null || !Number.isFinite(parsed)) mismatch(key, 'a finite double');
  return parsed;
}

function decodeAddress(value: unknown, label: string): RidbFacilityAddress {
  const source = asObject(value, label);
  return {
    facilityStreetAddress1: nullableString(source, 'FacilityStreetAddress1'),
    city: nullableString(source, 'City'),
    addressStateCode: nullableString(source, 'AddressStateCode'),
    postalCode: nullableString(source, 'PostalCode'),
    addressCountryCode: nullableString(source, 'AddressCountryCode'),
  };
}

function decodeAddresses(source: JsonObject): RidbFacilityAddress[] | null {
  const value = presentValue(source, FACILITY_ADDRESS_KEY);
  if (value === undefined) return null;
  if (!Array.isArray(value)) mismatch(FACILITY_ADDRESS_KEY, 'an array');
  return value.map((entry, index) => decodeAddress(entry, `${FACILITY_ADDRESS_KEY}[${index}]`));
}

function decodeFacility(value: unknown, index: number): RidbFacility {
  const source = asObject(value, `${RECDATA_KEY}[${index}]`);
  return {
    facilityId: longIdOf(FACILITY_ID_KEY, fieldOf(source, FACILITY_ID_KEY)),
    facilityName: nullableString(source, 'FacilityName'),
    facilityLatitude: nullableDoubleOf(source, 'FacilityLatitude'),
    facilityLongitude: nullableDoubleOf(source, 'FacilityLongitude'),
    facilityPhone: nullableString(source, 'FacilityPhone'),
    facilityReservationUrl: nullableString(source, 'FacilityReservationURL'),
    facilityAddress: decodeAddresses(source),
  };
}

export function decodeRidbPage(payload: unknown): RidbFacility[] {
  const page = asObject(payload, 'payload');
  return arrayOrEmpty(page, RECDATA_KEY).map(decodeFacility);
}
