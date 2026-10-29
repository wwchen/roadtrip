import type { Envelope } from '../../etl/envelope.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { booleanOrNull, contentOrNull, doubleOrNull, intOrNull } from '../../etl/kotlin-json.js';
import { kotlinTrim } from '../../etl/kotlin-text.js';
import type { ParseResult } from '../../etl/source-etl.js';
import { campflareCampgroundUrl } from './urls.js';

export const CAMPGROUNDS_ETL_SLUG = 'campflare-campgrounds';
export const CAMPSITES_ETL_SLUG = 'campflare-campsites';
export const CAMPFLARE_SOURCE_LINK_TITLE = 'Campflare source';

export const SUPPORT_FIELD = {
  connections: 'connections',
  ridbFacilityId: 'ridb_facility_id',
  reservationUrl: 'reservation_url',
} as const;

const LATITUDE_MIN = -90;
const LATITUDE_MAX = 90;
const LONGITUDE_MIN = -180;
const LONGITUDE_MAX = 180;
const E6_COORDINATE_SCALE = 1_000_000;
const NULL_LITERAL = 'null';
const NO_ENVELOPES = 'no envelopes captured';
const EXPECTED_ARRAY = 'expected JSON array payload';
const EXPECTED_OBJECT_ROW = 'expected JSON object row';

const RECGOV_CAMPGROUND_URL = /\/campgrounds\/(\d+)/;
const RECGOV_CAMPSITE_URL = /\/campsites\/(\d+)/;

export function stringField(raw: JsonObject | null | undefined, name: string): string | null {
  const content = contentOrNull(fieldOf(raw, name));
  if (content === null) return null;
  const trimmed = kotlinTrim(content);
  return trimmed === '' || trimmed === NULL_LITERAL ? null : trimmed;
}

export function doubleField(raw: JsonObject | null | undefined, name: string): number | null {
  return doubleOrNull(fieldOf(raw, name));
}

export function intField(raw: JsonObject | null | undefined, name: string): number | null {
  return intOrNull(fieldOf(raw, name));
}

export function booleanField(raw: JsonObject | null | undefined, name: string): boolean | null {
  return booleanOrNull(fieldOf(raw, name));
}

export function objectField(raw: JsonObject | null | undefined, name: string): JsonObject | null {
  const value = fieldOf(raw, name);
  return isJsonObject(value) ? value : null;
}

export function jsonArrayField(raw: JsonObject | null | undefined, name: string): unknown[] | null {
  const value = fieldOf(raw, name);
  return Array.isArray(value) ? value : null;
}

export function firstString(raw: JsonObject, keys: readonly string[]): string | null {
  for (const key of keys) {
    const found = stringField(raw, key);
    if (found !== null) return found;
  }
  return null;
}

export function recgovCampgroundIdFromUrl(url: string | null): string | null {
  return url === null ? null : (RECGOV_CAMPGROUND_URL.exec(url)?.[1] ?? null);
}

export function recgovCampsiteIdFromUrl(url: string | null): string | null {
  return url === null ? null : (RECGOV_CAMPSITE_URL.exec(url)?.[1] ?? null);
}

export function extractRecgovCampgroundRef(raw: JsonObject): string | null {
  const connections = objectField(raw, SUPPORT_FIELD.connections);
  const facilityId = connections === null ? null : stringField(connections, SUPPORT_FIELD.ridbFacilityId);
  return facilityId ?? recgovCampgroundIdFromUrl(stringField(raw, SUPPORT_FIELD.reservationUrl));
}

export function extractRecgovCampsiteRef(reservationUrl: string | null): string | null {
  return recgovCampsiteIdFromUrl(reservationUrl);
}

export function campflareCampgroundSourceUrl(campflareId: string): string {
  return campflareCampgroundUrl(campflareId);
}

function normalizedCoordinate(value: number | null, min: number, max: number): number | null {
  if (value === null) return null;
  if (value >= min && value <= max) return value;
  const scaled = value / E6_COORDINATE_SCALE;
  return scaled >= min && scaled <= max ? scaled : null;
}

export function normalizedLatitude(value: number | null): number | null {
  return normalizedCoordinate(value, LATITUDE_MIN, LATITUDE_MAX);
}

export function normalizedLongitude(value: number | null): number | null {
  return normalizedCoordinate(value, LONGITUDE_MIN, LONGITUDE_MAX);
}

export function* jsonObjectResults(envelopes: readonly Envelope[], etlSlug: string): Generator<ParseResult<JsonObject>> {
  if (envelopes.length === 0) {
    yield { ok: false, errors: [`${etlSlug}: ${NO_ENVELOPES}`] };
    return;
  }
  for (const envelope of envelopes) {
    const sourceId = envelope.part ?? envelope.request.url;
    const rows = envelope.payload;
    if (!Array.isArray(rows)) {
      yield { ok: false, sourceId, errors: [`${etlSlug}: ${EXPECTED_ARRAY}`] };
      continue;
    }
    for (const [index, row] of rows.entries()) {
      if (isJsonObject(row)) yield { ok: true, dto: row };
      else yield { ok: false, sourceId: `${sourceId}[${index}]`, errors: [`${etlSlug}: ${EXPECTED_OBJECT_ROW}`] };
    }
  }
}
