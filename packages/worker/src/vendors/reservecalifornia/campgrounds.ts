import {
  AmenityKey,
  BookingProvider,
  DataProviderRef,
  type CampgroundAmenity,
  type CampgroundMetadata,
} from '@roadtrip/domain';
import { documentEntries, documentValues } from '../../etl/document-order.js';
import { serializeOpaquePayload, type Envelope, type OpaquePayloadJson } from '../../etl/envelope.js';
import { stripTags } from '../../etl/html-text.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { booleanOrNull, contentOrNull, jsonObjectOf, longOrNull } from '../../etl/kotlin-json.js';
import { compareLongs, kotlinDoubleFromText, kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';
import { kotlinTrim, nonBlank } from '../../etl/kotlin-text.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import type { ReserveCaliforniaCatalog } from './catalog.js';
import type { ReserveCaliforniaFacility } from './facility.js';
import type { ReserveCaliforniaGridCatalog } from './grid-catalog.js';
import type { ReserveCaliforniaPlace } from './place.js';
import type { ReserveCaliforniaUnit } from './unit.js';

export const REGION = 'CA';
export const COUNTRY = 'US';
export const PARENT_PLACE_ID_KEY = '_parent_place_id';

const PLACE_PART_PREFIX = 'place-';
const FACILITY_PART_PREFIX = 'facility-';
const GRID_PART_PREFIX = 'grid-';
const PARK_URL_PREFIX = 'https://reservecalifornia.com/park/';
const FACILITY_ID_SEPARATOR = ',';
const PLACE_REF_SEPARATOR = ':';
const NO_FACILITIES_SUFFIX = 'no ReserveCalifornia place payloads with facilities parsed';
const NO_ENVELOPES_SUFFIX = 'no envelopes captured (run fetch_reservecalifornia.py first)';
const NO_FACILITY_IDS = 'place has no facility ids';
const LINE_BREAK = /<br[ \t\n\x0B\f\r]*\/?>/i;

const AMENITY_KEY_BY_HIGHLIGHT: ReadonlyMap<string, AmenityKey> = new Map([
  ['restrooms', AmenityKey.TOILETS],
  ['toilet, accessible', AmenityKey.TOILETS],
  ['comfort station', AmenityKey.TOILETS],
  ['showers', AmenityKey.SHOWERS],
  ['rinse showers', AmenityKey.SHOWERS],
  ['dump station', AmenityKey.DUMP_STATION],
  ['store-convenience', AmenityKey.CAMP_STORE],
  ['store - convenience', AmenityKey.CAMP_STORE],
  ['fire rings', AmenityKey.FIRES_ALLOWED],
]);

export const ACTIVITY_HINTS: readonly string[] = [
  'biking',
  'bird',
  'boating',
  'fishing',
  'hiking',
  'riding',
  'ski',
  'swimming',
  'trail',
  'water sport',
];

export function reserveCaliforniaParkUrl(placeId: bigint): string {
  return `${PARK_URL_PREFIX}${placeId}`;
}

function trimmedNonEmpty(value: string | null): string | null {
  if (value === null) return null;
  const trimmed = kotlinTrim(value);
  return trimmed === '' ? null : trimmed;
}

export function stringValue(source: JsonObject, key: string): string | null {
  return trimmedNonEmpty(contentOrNull(fieldOf(source, key)));
}

export function parseHighlights(raw: string | null): string[] {
  if (raw === null) return [];
  const labels = raw
    .split(LINE_BREAK)
    .map(stripTags)
    .filter((label) => label !== '');
  return [...new Set(labels)];
}

export function isActivityHighlight(label: string): boolean {
  const normalized = label.toLowerCase();
  return ACTIVITY_HINTS.some((hint) => normalized.includes(hint));
}

export function highlightAmenities(labels: readonly string[]): CampgroundAmenity[] {
  const seen = new Set<string>();
  const amenities: CampgroundAmenity[] = [];
  for (const raw of labels) {
    const label = trimmedNonEmpty(raw);
    if (label === null) continue;
    const key = AMENITY_KEY_BY_HIGHLIGHT.get(label.toLowerCase());
    const amenity: CampgroundAmenity =
      key === undefined
        ? { key: AmenityKey.OTHER, present: true, detail: label }
        : { key, present: true };
    const identity = JSON.stringify([amenity.key, amenity.detail ?? null]);
    if (seen.has(identity)) continue;
    seen.add(identity);
    amenities.push(amenity);
  }
  return amenities;
}

function placeMetadata(place: ReserveCaliforniaPlace): CampgroundMetadata | null {
  return place.activities.length === 0 ? null : { activities: place.activities };
}

export function campsiteSourcePayload(
  unit: ReserveCaliforniaUnit,
  grid: ReserveCaliforniaGridCatalog,
  placeId: bigint,
  facility: ReserveCaliforniaFacility | undefined,
): OpaquePayloadJson {
  return serializeOpaquePayload({
    ...unit.raw,
    unit_id: unit.unitId,
    facility_id: grid.facilityId,
    [PARENT_PLACE_ID_KEY]: placeId,
    ...(grid.facilityName === null ? {} : { facility_name: grid.facilityName }),
    ...(facility === undefined ? {} : { facility: facility.raw }),
  });
}

function firstUnitTypeName(facility: JsonObject): string | null {
  const unitTypes = fieldOf(facility, 'UnitTypes');
  if (unitTypes === undefined) return null;
  for (const entry of documentValues(jsonObjectOf(unitTypes))) {
    if (!isJsonObject(entry)) continue;
    const name = contentOrNull(fieldOf(entry, 'Name'));
    if (name !== null) return name;
  }
  return null;
}

function latitudeOrLongitude(selected: JsonObject, key: string): number | null {
  const text = contentOrNull(fieldOf(selected, key));
  return text === null ? null : kotlinDoubleFromText(text);
}

export function parsePlace(payload: JsonObject): ReserveCaliforniaPlace | null {
  const selectedElement = fieldOf(payload, 'SelectedPlace');
  if (selectedElement === undefined) return null;
  const selected = jsonObjectOf(selectedElement);
  const placeId = longOrNull(fieldOf(selected, 'PlaceId'));
  if (placeId === null) return null;
  const name = nonBlank(contentOrNull(fieldOf(selected, 'Name')));
  if (name === null) return null;
  const latitude = latitudeOrLongitude(selected, 'Latitude');
  if (latitude === null) return null;
  const longitude = latitudeOrLongitude(selected, 'Longitude');
  if (longitude === null) return null;
  const facilitiesElement = fieldOf(selected, 'Facilities');
  const facilities = facilitiesElement === undefined ? {} : jsonObjectOf(facilitiesElement);
  const facilityIds: bigint[] = [];
  const unitTypes = new Map<bigint, string>();
  for (const [key, rawFacility] of documentEntries(facilities)) {
    if (!isJsonObject(rawFacility)) continue;
    const facilityId = longOrNull(fieldOf(rawFacility, 'FacilityId')) ?? kotlinStringToLongOrNull(key);
    if (facilityId === null) continue;
    facilityIds.push(facilityId);
    const unitTypeName = firstUnitTypeName(rawFacility);
    if (unitTypeName !== null) unitTypes.set(facilityId, unitTypeName);
  }
  const highlights = parseHighlights(contentOrNull(fieldOf(selected, 'Allhighlights')));
  return {
    placeId,
    name,
    latitude,
    longitude,
    facilityIds: [...new Set(facilityIds)].sort(compareLongs),
    unitTypeByFacilityId: unitTypes,
    imageUrl: stringValue(selected, 'ImageUrl'),
    description: stringValue(selected, 'Description'),
    amenities: highlights.filter((label) => !isActivityHighlight(label)),
    activities: highlights.filter(isActivityHighlight),
    raw: selected,
  };
}

export function parseFacility(payload: JsonObject): ReserveCaliforniaFacility | null {
  const facilityId = longOrNull(fieldOf(payload, 'FacilityId'));
  if (facilityId === null) return null;
  return {
    facilityId,
    placeId: longOrNull(fieldOf(payload, 'PlaceId')),
    name: contentOrNull(fieldOf(payload, 'Name')),
    facilityTypeNew: longOrNull(fieldOf(payload, 'FacilityTypeNew')),
    facilityBehaviourType: longOrNull(fieldOf(payload, 'FacilityBehaviourType')),
    allowWebBooking: booleanOrNull(fieldOf(payload, 'AllowWebBooking')),
    raw: payload,
  };
}

function parseUnit(raw: unknown): ReserveCaliforniaUnit | null {
  if (!isJsonObject(raw)) return null;
  const unitId = longOrNull(fieldOf(raw, 'UnitId'));
  if (unitId === null) return null;
  return { unitId, name: contentOrNull(fieldOf(raw, 'Name')), raw };
}

export function parseGrid(payload: JsonObject): ReserveCaliforniaGridCatalog | null {
  const facilityElement = fieldOf(payload, 'Facility');
  if (facilityElement === undefined) return null;
  const facility = jsonObjectOf(facilityElement);
  const facilityId = longOrNull(fieldOf(facility, 'FacilityId'));
  if (facilityId === null) return null;
  const unitsElement = fieldOf(facility, 'Units');
  const units = unitsElement === undefined ? {} : jsonObjectOf(unitsElement);
  return {
    facilityId,
    placeId: longOrNull(fieldOf(facility, 'PlaceId')),
    facilityName: contentOrNull(fieldOf(facility, 'Name')),
    units: documentValues(units)
      .map(parseUnit)
      .filter((unit): unit is ReserveCaliforniaUnit => unit !== null),
  };
}

export function parseCatalog(envelopes: readonly Envelope[], label: string): ReserveCaliforniaCatalog {
  if (envelopes.length === 0) throw new Error(`${label}: ${NO_ENVELOPES_SUFFIX}`);
  const places = new Map<bigint, ReserveCaliforniaPlace>();
  const facilities = new Map<bigint, ReserveCaliforniaFacility>();
  const grids = new Map<bigint, ReserveCaliforniaGridCatalog>();
  for (const envelope of envelopes) {
    const payload = envelope.payload;
    if (!isJsonObject(payload)) continue;
    const part = envelope.part;
    if (part?.startsWith(PLACE_PART_PREFIX) === true) {
      const place = parsePlace(payload);
      if (place !== null) places.set(place.placeId, place);
    } else if (part?.startsWith(FACILITY_PART_PREFIX) === true) {
      const facility = parseFacility(payload);
      if (facility !== null) facilities.set(facility.facilityId, facility);
    } else if (part?.startsWith(GRID_PART_PREFIX) === true || fieldOf(payload, 'Facility') !== undefined) {
      const grid = parseGrid(payload);
      if (grid !== null) grids.set(grid.facilityId, grid);
    }
  }
  return { places, facilities, grids };
}

export class ReserveCaliforniaCampgroundsEtl implements SourceEtl<ReserveCaliforniaCatalog, CampgroundUpsertCandidate> {
  constructor(readonly etlSlug: string) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<ReserveCaliforniaCatalog>> {
    const catalog = parseCatalog(inputs.soleEnvelopes(), this.etlSlug);
    const hasFacilities = [...catalog.places.values()].some((place) => place.facilityIds.length > 0);
    yield hasFacilities
      ? { ok: true, dto: catalog }
      : { ok: false, errors: [`${this.etlSlug}: ${NO_FACILITIES_SUFFIX}`] };
  }

  *transform(
    dto: ReserveCaliforniaCatalog,
    ctx: TransformCtx,
  ): Iterable<TransformResult<CampgroundUpsertCandidate>> {
    const bucket = ctx.subcategoryFor(this.etlSlug);
    const agency = ctx.requiredConstantAgency(this.etlSlug);
    for (const place of dto.places.values()) {
      if (place.facilityIds.length === 0) {
        yield { ok: false, sourceId: place.placeId.toString(), errors: [NO_FACILITY_IDS] };
        continue;
      }
      const parkUrl = reserveCaliforniaParkUrl(place.placeId);
      yield {
        ok: true,
        record: {
          dataProviderRef: DataProviderRef.reserveCalifornia(place.placeId.toString()),
          bookingProvider: BookingProvider.RESERVECALIFORNIA,
          bookingProviderRef: `${place.placeId}${PLACE_REF_SEPARATOR}${place.facilityIds.join(FACILITY_ID_SEPARATOR)}`,
          name: place.name,
          latitude: place.latitude,
          longitude: place.longitude,
          kind: bucket ?? null,
          mediumDescription: place.description,
          location: { latitude: place.latitude, longitude: place.longitude, region: REGION, country: COUNTRY },
          amenities: highlightAmenities(place.amenities),
          reservationUrl: parkUrl,
          links: [{ url: parkUrl }],
          photos: place.imageUrl === null ? [] : [{ url: place.imageUrl }],
          management: { agency },
          metadata: placeMetadata(place),
          sourceUrl: parkUrl,
          sourcePayload: serializeOpaquePayload(place.raw),
        },
      };
    }
  }
}
