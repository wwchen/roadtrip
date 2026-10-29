import {
  arrayOrEmpty,
  asObject,
  hasField,
  nullableBoolean,
  nullableDouble,
  nullableInt,
  nullableObject,
  nullableString,
  nullableStringList,
  objectOrEmpty,
  type JsonObject,
} from '../../etl/json-decode.js';
import { isKotlinBlank } from '../../etl/kotlin-text.js';

export interface TeslaSuperchargerFunction {
  showOnFindUs: string | null;
  siteStatus: string | null;
}

export interface TeslaIndexRow {
  latitude: number | null;
  longitude: number | null;
  title: string | null;
  locationType: string[] | null;
  locationUrlSlug: string | null;
  superchargerFunction: TeslaSuperchargerFunction | null;
}

export interface TeslaIndexDto {
  rows: TeslaIndexRow[];
  rawBySlug: ReadonlyMap<string, JsonObject>;
}

export interface TeslaAddress {
  street: string | null;
  streetNumber: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  countryCode: string | null;
}

export interface TeslaLocationDetail {
  name: string | null;
  locationGuid: string | null;
  address: TeslaAddress | null;
  timeZone: string | null;
  openToPublic: boolean | null;
  publicStallCount: number | null;
  maxPowerKw: number | null;
  accessType: string | null;
  openToNonTeslas: boolean | null;
  isTrailerFriendly: boolean | null;
  twentyFourSeven: boolean | null;
  effectivePricebooks: unknown[];
}

export interface TeslaDetailCapture {
  detail: TeslaLocationDetail;
  raw: JsonObject | null;
}

const DATA_KEY = 'data';
const SLUG_KEY = 'location_url_slug';
const JSON_NULL_CONTENT = 'null';

function decodeSuperchargerFunction(source: JsonObject): TeslaSuperchargerFunction | null {
  const fn = nullableObject(source, 'supercharger_function');
  if (fn === null) return null;
  return { showOnFindUs: nullableString(fn, 'show_on_find_us'), siteStatus: nullableString(fn, 'site_status') };
}

function decodeIndexRow(value: unknown): TeslaIndexRow {
  const source = asObject(value, 'row');
  return {
    latitude: nullableDouble(source, 'latitude'),
    longitude: nullableDouble(source, 'longitude'),
    title: nullableString(source, 'title'),
    locationType: nullableStringList(source, 'location_type'),
    locationUrlSlug: nullableString(source, SLUG_KEY),
    superchargerFunction: decodeSuperchargerFunction(source),
  };
}

function slugContent(entry: JsonObject): string | null {
  if (!Object.hasOwn(entry, SLUG_KEY)) return null;
  const value = entry[SLUG_KEY];
  if (value === null) return JSON_NULL_CONTENT;
  return typeof value === 'string' ? value : null;
}

function rawBySlugOf(entries: unknown[]): Map<string, JsonObject> {
  const bySlug = new Map<string, JsonObject>();
  for (const entry of entries) {
    const object = asObject(entry, 'row');
    const slug = slugContent(object);
    if (slug !== null && !isKotlinBlank(slug)) bySlug.set(slug, object);
  }
  return bySlug;
}

export function decodeTeslaIndex(payload: unknown): TeslaIndexDto {
  const inner = objectOrEmpty(asObject(payload, 'payload'), DATA_KEY);
  const entries = arrayOrEmpty(inner, DATA_KEY);
  return { rows: entries.map(decodeIndexRow), rawBySlug: rawBySlugOf(entries) };
}

function decodeAddress(source: JsonObject): TeslaAddress | null {
  const address = nullableObject(source, 'address');
  if (address === null) return null;
  return {
    street: nullableString(address, 'street'),
    streetNumber: nullableString(address, 'streetNumber'),
    city: nullableString(address, 'city'),
    state: nullableString(address, 'state'),
    postalCode: nullableString(address, 'postalCode'),
    countryCode: nullableString(address, 'countryCode'),
  };
}

function decodeTwentyFourSeven(source: JsonObject): boolean | null {
  const hours = nullableObject(source, 'accessHours');
  return hours === null ? null : nullableBoolean(hours, 'twentyFourSeven');
}

function decodeDetail(source: JsonObject): TeslaLocationDetail {
  return {
    name: nullableString(source, 'name'),
    locationGuid: nullableString(source, 'locationGUID'),
    address: decodeAddress(source),
    timeZone: nullableString(source, 'timeZone'),
    openToPublic: nullableBoolean(source, 'openToPublic'),
    publicStallCount: nullableInt(source, 'publicStallCount'),
    maxPowerKw: nullableInt(source, 'maxPowerKw'),
    accessType: nullableString(source, 'accessType'),
    openToNonTeslas: nullableBoolean(source, 'openToNonTeslas'),
    isTrailerFriendly: nullableBoolean(source, 'isTrailerFriendly'),
    twentyFourSeven: decodeTwentyFourSeven(source),
    effectivePricebooks: arrayOrEmpty(source, 'effectivePricebooks'),
  };
}

export function decodeTeslaDetail(payload: unknown): TeslaDetailCapture {
  const wire = asObject(payload, 'payload');
  const outer = objectOrEmpty(wire, DATA_KEY);
  const inner = objectOrEmpty(outer, DATA_KEY);
  return { detail: decodeDetail(inner), raw: hasField(wire, DATA_KEY) && hasField(outer, DATA_KEY) ? inner : null };
}
