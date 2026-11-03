import { BookingProvider, DataProviderRef } from '@roadtrip/domain';
import { campsiteKindCampflare } from '../../etl/campsite-kinds.js';
import { serializeOpaquePayload, type OpaquePayloadJson } from '../../etl/envelope.js';
import { stripTags } from '../../etl/html-text.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull } from '../../etl/kotlin-json.js';
import { kotlinTrim, nonBlank } from '../../etl/kotlin-text.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import { campflarePhotos } from './fields.js';
import {
  CAMPSITES_ETL_SLUG,
  booleanField,
  doubleField,
  extractRecgovCampsiteRef,
  intField,
  jsonArrayField,
  jsonObjectResults,
  normalizedLatitude,
  normalizedLongitude,
  objectField,
  stringField,
} from './support.js';

const FIELD = {
  id: 'id',
  campgroundId: 'campground_id',
  name: 'name',
  kind: 'kind',
  reservationUrl: 'reservation_url',
  loopName: 'loop_name',
  latitude: 'latitude',
  longitude: 'longitude',
  equipment: 'equipment',
  kindListed: 'kind_listed',
  schedule: 'schedule',
  price: 'price',
  firepit: 'firepit',
  picnicTable: 'picnic_table',
  adaAccessible: 'ada_accessible',
  waterHookups: 'water_hookups',
  electricHookups: 'electric_hookups',
  sewerHookups: 'sewer_hookups',
  maxPeople: 'max_people',
  maxCars: 'max_cars',
  pullThrough: 'pull_through',
  drivewayLength: 'driveway_length',
  maxRvLength: 'max_rv_length',
  maxTrailerLength: 'max_trailer_length',
  photos: 'photos',
  description: 'description',
} as const;

const EQUIPMENT_NAME_KEY = 'name';
const MISSING_ID = 'missing id';
const MISSING_CAMPGROUND_ID = 'missing campground_id';
const MISSING_NAME = 'missing name';

function optionalPayload(value: JsonObject | null): OpaquePayloadJson | null {
  return value === null ? null : serializeOpaquePayload(value);
}

function equipmentName(entry: unknown): string | null {
  if (isJsonObject(entry)) return stringField(entry, EQUIPMENT_NAME_KEY);
  if (Array.isArray(entry)) return null;
  return contentOrNull(entry);
}

function campflareEquipment(equipment: readonly unknown[] | null): string[] {
  const names: string[] = [];
  for (const entry of equipment ?? []) {
    const name = equipmentName(entry);
    if (name === null) continue;
    const trimmed = kotlinTrim(name);
    if (trimmed !== '') names.push(trimmed);
  }
  return names;
}

function campsiteRecord(raw: JsonObject): TransformResult<CampsiteUpsertCandidate> {
  const id = stringField(raw, FIELD.id);
  const campgroundId = stringField(raw, FIELD.campgroundId);
  const name = stringField(raw, FIELD.name);
  const errors: string[] = [];
  if (id === null) errors.push(MISSING_ID);
  if (campgroundId === null) errors.push(MISSING_CAMPGROUND_ID);
  if (name === null) errors.push(MISSING_NAME);
  if (id === null || campgroundId === null || name === null) {
    return { ok: false, ...(id === null ? {} : { sourceId: id }), errors };
  }

  const rawKind = stringField(raw, FIELD.kind);
  const reservationUrl = stringField(raw, FIELD.reservationUrl);
  const recgovRef = extractRecgovCampsiteRef(reservationUrl);
  const description = stringField(raw, FIELD.description);
  return {
    ok: true,
    record: {
      dataProviderRef: DataProviderRef.campflare(id),
      bookingProvider: BookingProvider.CAMPFLARE,
      bookingProviderRef: id,
      bookingAliases: recgovRef === null ? [] : [{ provider: BookingProvider.RECGOV, ref: recgovRef }],
      parentDataProviderRef: DataProviderRef.campflare(campgroundId),
      name,
      kind: campsiteKindCampflare(rawKind),
      loopName: stringField(raw, FIELD.loopName),
      latitude: normalizedLatitude(doubleField(raw, FIELD.latitude)),
      longitude: normalizedLongitude(doubleField(raw, FIELD.longitude)),
      reservationUrl,
      equipment: campflareEquipment(jsonArrayField(raw, FIELD.equipment)),
      kindListed: stringField(raw, FIELD.kindListed) ?? rawKind,
      schedule: optionalPayload(objectField(raw, FIELD.schedule)),
      price: optionalPayload(objectField(raw, FIELD.price)),
      firepit: booleanField(raw, FIELD.firepit),
      picnicTable: booleanField(raw, FIELD.picnicTable),
      adaAccessible: booleanField(raw, FIELD.adaAccessible),
      waterHookups: booleanField(raw, FIELD.waterHookups),
      electricHookups: booleanField(raw, FIELD.electricHookups),
      sewerHookups: booleanField(raw, FIELD.sewerHookups),
      maxPeople: intField(raw, FIELD.maxPeople),
      maxCars: intField(raw, FIELD.maxCars),
      pullThrough: booleanField(raw, FIELD.pullThrough),
      drivewayLength: intField(raw, FIELD.drivewayLength),
      maxRvLength: intField(raw, FIELD.maxRvLength),
      maxTrailerLength: doubleField(raw, FIELD.maxTrailerLength),
      photos: campflarePhotos(jsonArrayField(raw, FIELD.photos)),
      description: description === null ? null : nonBlank(stripTags(description)),
      sourcePayload: serializeOpaquePayload(raw),
    },
  };
}

export class CampflareCampsitesEtl implements SourceEtl<JsonObject, CampsiteUpsertCandidate> {
  readonly etlSlug = CAMPSITES_ETL_SLUG;

  parse(inputs: InputBundle): Iterable<ParseResult<JsonObject>> {
    return jsonObjectResults(inputs.soleEnvelopes(), this.etlSlug);
  }

  *transform(dto: JsonObject): Iterable<TransformResult<CampsiteUpsertCandidate>> {
    yield campsiteRecord(dto);
  }
}
