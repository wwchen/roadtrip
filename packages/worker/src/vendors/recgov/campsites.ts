import { BookingProvider, DataProviderRef, type CampsiteAttribute } from '@roadtrip/domain';
import { campsiteKindRecGov } from '../../etl/campsite-kinds.js';
import { serializeOpaquePayload, type Envelope } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull, intOrNull } from '../../etl/kotlin-json.js';
import { kotlinStringToIntOrNull } from '../../etl/kotlin-number.js';
import { kotlinTrim } from '../../etl/kotlin-text.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';

const FIRE_PIT_ATTRIBUTE = 'fire pit';
const PICNIC_TABLE_ATTRIBUTE = 'picnic table';
const ADA_ACCESSIBLE_ATTRIBUTES: ReadonlySet<string> = new Set(['accessible', 'ada accessible']);
const MAX_CARS_ATTRIBUTES: ReadonlySet<string> = new Set(['max num of vehicles', 'max vehicles']);
const DRIVEWAY_LENGTH_ATTRIBUTE = 'driveway length';
const MAX_VEHICLE_LENGTH_ATTRIBUTE = 'max vehicle length';
const YES_VALUES: ReadonlySet<string> = new Set(['yes', 'y', 'true']);
const NO_VALUES: ReadonlySet<string> = new Set(['no', 'n', 'false']);
const LEADING_INT = /^\d+/;
const RESERVE_TYPE_LABEL = 'Reserve type';
const TYPE_OF_USE_LABEL = 'Type of use';
const CAMPGROUND_MARKER = '/campground/';
const URL_ID_TERMINATORS = ['/', '?'];
const PARENT_FACILITY_KEY = '_parent_facility_id';

const FIELD = {
  campsites: 'campsites',
  attributes: 'attributes',
  attributeName: 'attribute_name',
  attributeValue: 'attribute_value',
  equipment: 'equipment_types',
  site: 'site',
  loop: 'loop',
  campsiteType: 'campsite_type',
  campsiteReserveType: 'campsite_reserve_type',
  typeOfUse: 'type_of_use',
  maxPeople: 'max_num_people',
  minPeople: 'min_num_people',
} as const;

const NO_ENVELOPES_SUFFIX = 'no envelopes captured (run fetch_recgov_campsites.py first)';
const MISSING_FACILITY_ID = 'missing facility id in request URL';
const EXPECTED_OBJECT_PAYLOAD = 'expected JSON object payload';
const MISSING_CAMPSITES = 'missing campsites object';
const EXPECTED_CAMPSITE_OBJECT = 'expected campsite JSON object';

interface PromotedAttributes {
  firepit: boolean | null;
  picnicTable: boolean | null;
  adaAccessible: boolean | null;
  maxCars: number | null;
  drivewayLength: number | null;
  maxRvLength: number | null;
  attributes: CampsiteAttribute[];
}

function primitiveStringField(source: JsonObject, key: string): string | null {
  const value = fieldOf(source, key);
  if (value === undefined || Array.isArray(value) || isJsonObject(value)) return null;
  const content = contentOrNull(value);
  if (content === null) return null;
  const trimmed = kotlinTrim(content);
  return trimmed === '' ? null : trimmed;
}

function equipmentNames(raw: JsonObject): string[] {
  const entries = fieldOf(raw, FIELD.equipment);
  if (!Array.isArray(entries)) return [];
  const names: string[] = [];
  for (const entry of entries) {
    if (Array.isArray(entry) || isJsonObject(entry)) continue;
    const content = contentOrNull(entry);
    if (content === null) continue;
    const trimmed = kotlinTrim(content);
    if (trimmed !== '') names.push(trimmed);
  }
  return names;
}

function booleanValue(value: string | null): boolean | null {
  const lowered = value === null ? null : value.toLowerCase();
  if (lowered === null) return null;
  if (YES_VALUES.has(lowered)) return true;
  return NO_VALUES.has(lowered) ? false : null;
}

function leadingInt(value: string | null): number | null {
  const digits = value === null ? undefined : LEADING_INT.exec(value)?.[0];
  return digits === undefined ? null : kotlinStringToIntOrNull(digits);
}

function promoteAttributes(raw: JsonObject): PromotedAttributes {
  const promoted: PromotedAttributes = {
    firepit: null,
    picnicTable: null,
    adaAccessible: null,
    maxCars: null,
    drivewayLength: null,
    maxRvLength: null,
    attributes: [],
  };
  const entries = fieldOf(raw, FIELD.attributes);
  for (const entry of Array.isArray(entries) ? entries : []) {
    if (!isJsonObject(entry)) continue;
    const name = primitiveStringField(entry, FIELD.attributeName);
    if (name === null) continue;
    const value = primitiveStringField(entry, FIELD.attributeValue);
    if (!promoteOne(promoted, name.toLowerCase(), value)) {
      promoted.attributes.push(value === null ? { name } : { name, value });
    }
  }
  const reserveType = primitiveStringField(raw, FIELD.campsiteReserveType);
  if (reserveType !== null) promoted.attributes.push({ name: RESERVE_TYPE_LABEL, value: reserveType });
  const typeOfUse = primitiveStringField(raw, FIELD.typeOfUse);
  if (typeOfUse !== null) promoted.attributes.push({ name: TYPE_OF_USE_LABEL, value: typeOfUse });
  return promoted;
}

function promoteOne(promoted: PromotedAttributes, lowerName: string, value: string | null): boolean {
  if (lowerName === FIRE_PIT_ATTRIBUTE) {
    const parsed = booleanValue(value);
    if (parsed !== null) promoted.firepit = parsed;
    return parsed !== null;
  }
  if (lowerName === PICNIC_TABLE_ATTRIBUTE) {
    const parsed = booleanValue(value);
    if (parsed !== null) promoted.picnicTable = parsed;
    return parsed !== null;
  }
  if (ADA_ACCESSIBLE_ATTRIBUTES.has(lowerName)) {
    const parsed = booleanValue(value);
    if (parsed !== null) promoted.adaAccessible = parsed;
    return parsed !== null;
  }
  if (MAX_CARS_ATTRIBUTES.has(lowerName)) {
    const parsed = leadingInt(value);
    if (parsed !== null) promoted.maxCars = parsed;
    return parsed !== null;
  }
  if (lowerName === DRIVEWAY_LENGTH_ATTRIBUTE) {
    const parsed = leadingInt(value);
    if (parsed !== null) promoted.drivewayLength = parsed;
    return parsed !== null;
  }
  if (lowerName === MAX_VEHICLE_LENGTH_ATTRIBUTE) {
    const parsed = leadingInt(value);
    if (parsed !== null) promoted.maxRvLength = parsed;
    return parsed !== null;
  }
  return false;
}

function parseFacilityIdFromUrl(url: string): string | null {
  const start = url.indexOf(CAMPGROUND_MARKER);
  if (start < 0) return null;
  const tail = url.slice(start + CAMPGROUND_MARKER.length);
  const ends = URL_ID_TERMINATORS.map((terminator) => tail.indexOf(terminator)).filter((position) => position >= 0);
  const id = ends.length === 0 ? tail : tail.slice(0, Math.min(...ends));
  return id === '' ? null : id;
}

function campsiteRecord(
  campsiteId: string,
  raw: JsonObject,
  facilityId: string,
): CampsiteUpsertCandidate {
  const promoted = promoteAttributes(raw);
  const campsiteType = primitiveStringField(raw, FIELD.campsiteType);
  const siteKind = campsiteKindRecGov(campsiteType);
  return {
    dataProviderRef: DataProviderRef.recGov(campsiteId),
    bookingProvider: BookingProvider.RECGOV,
    bookingProviderRef: campsiteId,
    parentDataProviderRef: DataProviderRef.recGov(facilityId),
    name: primitiveStringField(raw, FIELD.site) ?? campsiteId,
    loopName: primitiveStringField(raw, FIELD.loop),
    kind: siteKind.kind,
    kindListed: campsiteType,
    equipment: equipmentNames(raw),
    firepit: promoted.firepit,
    picnicTable: promoted.picnicTable,
    adaAccessible: promoted.adaAccessible,
    electricHookups: siteKind.electric,
    maxPeople: intOrNull(fieldOf(raw, FIELD.maxPeople)),
    maxCars: promoted.maxCars,
    drivewayLength: promoted.drivewayLength,
    maxRvLength: promoted.maxRvLength,
    attributes: promoted.attributes,
    minPeople: intOrNull(fieldOf(raw, FIELD.minPeople)),
    sourcePayload: serializeOpaquePayload({ ...raw, [PARENT_FACILITY_KEY]: facilityId }),
  };
}

export class RecGovCampsitesEtl implements SourceEtl<Envelope[], CampsiteUpsertCandidate> {
  constructor(readonly etlSlug: string) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<Envelope[]>> {
    const envelopes = inputs.soleEnvelopes();
    if (envelopes.length === 0) throw new Error(`${this.etlSlug}: ${NO_ENVELOPES_SUFFIX}`);
    yield { ok: true, dto: envelopes };
  }

  *transform(dto: Envelope[]): Iterable<TransformResult<CampsiteUpsertCandidate>> {
    for (const envelope of dto) {
      const sourceId = envelope.part ?? envelope.request.url;
      const facilityId = parseFacilityIdFromUrl(envelope.request.url);
      if (facilityId === null) {
        yield { ok: false, sourceId, errors: [MISSING_FACILITY_ID] };
        continue;
      }
      const payload = envelope.payload;
      if (!isJsonObject(payload)) {
        yield { ok: false, sourceId, errors: [EXPECTED_OBJECT_PAYLOAD] };
        continue;
      }
      const rawCampsites = fieldOf(payload, FIELD.campsites);
      if (!isJsonObject(rawCampsites)) {
        yield { ok: false, sourceId, errors: [MISSING_CAMPSITES] };
        continue;
      }
      for (const [campsiteId, element] of Object.entries(rawCampsites)) {
        if (!isJsonObject(element)) {
          yield { ok: false, sourceId: campsiteId, errors: [EXPECTED_CAMPSITE_OBJECT] };
          continue;
        }
        yield { ok: true, record: campsiteRecord(campsiteId, element, facilityId) };
      }
    }
  }
}
