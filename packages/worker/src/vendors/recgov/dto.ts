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

const FIELD = {
  recData: 'RECDATA',
  facilityId: 'FacilityID',
  facilityName: 'FacilityName',
  facilityLatitude: 'FacilityLatitude',
  facilityLongitude: 'FacilityLongitude',
  facilityPhone: 'FacilityPhone',
  facilityReservationUrl: 'FacilityReservationURL',
  facilityAddress: 'FACILITYADDRESS',
  streetAddress: 'FacilityStreetAddress1',
  city: 'City',
  stateCode: 'AddressStateCode',
  postalCode: 'PostalCode',
  countryCode: 'AddressCountryCode',
} as const;

const EXPECTED = {
  long: 'a long',
  double: 'a double',
  finiteDouble: 'a finite double',
  array: 'an array',
} as const;

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
  const parsed = kotlinLongFromText(primitiveContent(key, value, EXPECTED.long));
  if (parsed === null) mismatch(key, EXPECTED.long);
  return parsed.toString();
}

function nullableDoubleOf(source: JsonObject, key: string): number | null {
  const value = presentValue(source, key);
  if (value === undefined) return null;
  const parsed = kotlinDoubleFromText(primitiveContent(key, value, EXPECTED.double));
  if (parsed === null || !Number.isFinite(parsed)) mismatch(key, EXPECTED.finiteDouble);
  return parsed;
}

function decodeAddress(value: unknown, label: string): RidbFacilityAddress {
  const source = asObject(value, label);
  return {
    facilityStreetAddress1: nullableString(source, FIELD.streetAddress),
    city: nullableString(source, FIELD.city),
    addressStateCode: nullableString(source, FIELD.stateCode),
    postalCode: nullableString(source, FIELD.postalCode),
    addressCountryCode: nullableString(source, FIELD.countryCode),
  };
}

function decodeAddresses(source: JsonObject): RidbFacilityAddress[] | null {
  const value = presentValue(source, FIELD.facilityAddress);
  if (value === undefined) return null;
  if (!Array.isArray(value)) mismatch(FIELD.facilityAddress, EXPECTED.array);
  return value.map((entry, index) => decodeAddress(entry, `${FIELD.facilityAddress}[${index}]`));
}

function decodeFacility(value: unknown, index: number): RidbFacility {
  const source = asObject(value, `${FIELD.recData}[${index}]`);
  return {
    facilityId: longIdOf(FIELD.facilityId, fieldOf(source, FIELD.facilityId)),
    facilityName: nullableString(source, FIELD.facilityName),
    facilityLatitude: nullableDoubleOf(source, FIELD.facilityLatitude),
    facilityLongitude: nullableDoubleOf(source, FIELD.facilityLongitude),
    facilityPhone: nullableString(source, FIELD.facilityPhone),
    facilityReservationUrl: nullableString(source, FIELD.facilityReservationUrl),
    facilityAddress: decodeAddresses(source),
  };
}

export function decodeRidbPage(payload: unknown): RidbFacility[] {
  const page = asObject(payload, 'payload');
  return arrayOrEmpty(page, FIELD.recData).map(decodeFacility);
}
