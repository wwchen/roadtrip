import {
  AmenityKey,
  amenityKeys,
  carriers,
  type Carrier,
  type CampgroundAlert,
  type CampgroundAmenity,
  type CampgroundMetadata,
  type CampgroundPrice,
  type CampgroundSchedule,
  type CarrierSignal,
} from '@roadtrip/domain';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { booleanOrNull, doubleOrNull, doubleToInt, isNonStringPrimitive } from '../../etl/kotlin-json.js';
import { doubleField, stringField } from './support.js';

const TOILET_KIND_KEY = 'toilet_kind';
const CARRIER_AVERAGE_KEY = 'avg';
const CARRIER_COUNT_KEY = 'count';
const KEY_WORD_SEPARATOR = '_';
const KEY_WORD_REPLACEMENT = ' ';
const PRICE_MINIMUM_KEY = 'minimum';
const PRICE_MAXIMUM_KEY = 'maximum';
const PRICE_CURRENCY_KEY = 'currency';
const PRICE_CURRENCY_CODE_KEY = 'currency_code';
const CHECK_IN_TIME_KEY = 'check_in_time';
const CHECK_OUT_TIME_KEY = 'check_out_time';
const ALERT_TITLE_KEY = 'title';
const ALERT_BODY_KEY = 'content';
const ALERT_END_DATE_KEY = 'end_date';
const ALERT_SOURCE_URL_KEY = 'source_url';
const LAST_UPDATED_KEY = 'last_updated';
const SINGLE_CODE_UNIT = 1;

const WIRE_AMENITY_KEYS: ReadonlySet<string> = new Set(amenityKeys.filter((key) => key !== AmenityKey.OTHER));
const WIRE_CARRIERS: ReadonlySet<string> = new Set(carriers);

function isEmptyRecord(record: object): boolean {
  return Object.values(record).every((value) => value === undefined);
}

function uppercaseFirst(text: string): string {
  const first = text.charAt(0);
  const upper = first.toUpperCase();
  return (upper.length === SINGLE_CODE_UNIT ? upper : first) + text.slice(SINGLE_CODE_UNIT);
}

function humanizeKey(key: string): string {
  return uppercaseFirst(key.replaceAll(KEY_WORD_SEPARATOR, KEY_WORD_REPLACEMENT));
}

function numberField(value: unknown): number | null {
  return isNonStringPrimitive(value) ? doubleOrNull(value) : null;
}

function withToiletKind(entries: CampgroundAmenity[], toiletKind: string | null): CampgroundAmenity[] {
  if (toiletKind === null) return entries;
  if (!entries.some((entry) => entry.key === AmenityKey.TOILETS)) {
    return [{ key: AmenityKey.TOILETS, present: true, detail: toiletKind }, ...entries];
  }
  return entries.map((entry) => (entry.key === AmenityKey.TOILETS ? { ...entry, detail: toiletKind } : entry));
}

export function amenities(raw: JsonObject): CampgroundAmenity[] {
  const entries: CampgroundAmenity[] = [];
  for (const [key, value] of Object.entries(raw)) {
    if (key === TOILET_KIND_KEY || value === null) continue;
    const known = WIRE_AMENITY_KEYS.has(key) ? (key as AmenityKey) : null;
    const present = isNonStringPrimitive(value) ? (booleanOrNull(value) ?? true) : true;
    entries.push({
      key: known ?? AmenityKey.OTHER,
      present,
      detail: known === null ? humanizeKey(key) : typeof value === 'string' ? value : undefined,
    });
  }
  return withToiletKind(entries, stringField(raw, TOILET_KIND_KEY));
}

export function carrierSignals(raw: JsonObject): CarrierSignal[] {
  const signals: CarrierSignal[] = [];
  for (const [key, value] of Object.entries(raw)) {
    if (!WIRE_CARRIERS.has(key)) continue;
    const nested = isJsonObject(value) ? value : null;
    const average = numberField(value) ?? numberField(fieldOf(nested, CARRIER_AVERAGE_KEY));
    if (average === null) continue;
    const count = numberField(fieldOf(nested, CARRIER_COUNT_KEY));
    signals.push({
      carrier: key as Carrier,
      average,
      count: count === null ? undefined : doubleToInt(count),
    });
  }
  return signals;
}

export function price(raw: JsonObject | null): CampgroundPrice | null {
  if (raw === null) return null;
  const parsed: CampgroundPrice = {
    minimum: doubleField(raw, PRICE_MINIMUM_KEY) ?? undefined,
    maximum: doubleField(raw, PRICE_MAXIMUM_KEY) ?? undefined,
    currency: stringField(raw, PRICE_CURRENCY_CODE_KEY) ?? stringField(raw, PRICE_CURRENCY_KEY) ?? undefined,
  };
  return isEmptyRecord(parsed) ? null : parsed;
}

export function schedule(raw: JsonObject | null): CampgroundSchedule | null {
  if (raw === null) return null;
  const parsed: CampgroundSchedule = {
    check_in: stringField(raw, CHECK_IN_TIME_KEY) ?? undefined,
    check_out: stringField(raw, CHECK_OUT_TIME_KEY) ?? undefined,
  };
  return isEmptyRecord(parsed) ? null : parsed;
}

export function alerts(raw: readonly unknown[] | null): CampgroundAlert[] {
  const found: CampgroundAlert[] = [];
  for (const entry of raw ?? []) {
    if (!isJsonObject(entry)) continue;
    const body = stringField(entry, ALERT_BODY_KEY);
    if (body === null) continue;
    found.push({
      title: stringField(entry, ALERT_TITLE_KEY) ?? undefined,
      body,
      ends_on: stringField(entry, ALERT_END_DATE_KEY) ?? undefined,
      source_url: stringField(entry, ALERT_SOURCE_URL_KEY) ?? undefined,
    });
  }
  return found;
}

export function metadata(raw: JsonObject | null): CampgroundMetadata | null {
  if (raw === null) return null;
  const parsed: CampgroundMetadata = { last_updated: stringField(raw, LAST_UPDATED_KEY) ?? undefined };
  return isEmptyRecord(parsed) ? null : parsed;
}
