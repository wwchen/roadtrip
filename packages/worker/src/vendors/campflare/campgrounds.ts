import { BookingProvider, DataProviderRef } from '@roadtrip/domain';
import { serializeOpaquePayload, type OpaquePayloadJson } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import type { JsonObject } from '../../etl/json-decode.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import * as bags from './bags.js';
import { campflareContact, campflareLinks, campflareLocation, campflareManagement, campflarePhotos } from './fields.js';
import {
  CAMPGROUNDS_ETL_SLUG,
  booleanField,
  campflareCampgroundSourceUrl,
  doubleField,
  extractRecgovCampgroundRef,
  jsonArrayField,
  jsonObjectResults,
  normalizedLatitude,
  normalizedLongitude,
  objectField,
  stringField,
} from './support.js';

const FIELD = {
  id: 'id',
  name: 'name',
  location: 'location',
  latitude: 'latitude',
  longitude: 'longitude',
  status: 'status',
  kind: 'kind',
  schedule: 'default_campsite_schedule',
  amenities: 'amenities',
  maxRvLength: 'max_rv_length',
  maxTrailerLength: 'max_trailer_length',
  hasPullThroughSites: 'has_pull_through_sites',
  bigRigFriendly: 'big_rig_friendly',
  reservationUrl: 'reservation_url',
  links: 'links',
  photos: 'photos',
  alerts: 'alerts',
  price: 'price',
  cellService: 'cell_service',
  management: 'management',
  contact: 'contact',
  connections: 'connections',
  metadata: 'metadata',
} as const;

const MISSING_ID = 'missing id';
const MISSING_NAME = 'missing name';
const MISSING_LOCATION = 'missing location';
const MISSING_LATITUDE = 'missing or invalid latitude';
const MISSING_LONGITUDE = 'missing or invalid longitude';

function optionalPayload(value: JsonObject | null): OpaquePayloadJson | null {
  return value === null ? null : serializeOpaquePayload(value);
}

function campgroundRecord(raw: JsonObject): TransformResult<CampgroundUpsertCandidate> {
  const id = stringField(raw, FIELD.id);
  const name = stringField(raw, FIELD.name);
  const location = objectField(raw, FIELD.location);
  const latitude = normalizedLatitude(doubleField(location, FIELD.latitude));
  const longitude = normalizedLongitude(doubleField(location, FIELD.longitude));
  const errors: string[] = [];
  if (id === null) errors.push(MISSING_ID);
  if (name === null) errors.push(MISSING_NAME);
  if (location === null) errors.push(MISSING_LOCATION);
  if (latitude === null) errors.push(MISSING_LATITUDE);
  if (longitude === null) errors.push(MISSING_LONGITUDE);
  if (id === null || name === null || location === null || latitude === null || longitude === null) {
    return { ok: false, ...(id === null ? {} : { sourceId: id }), errors };
  }

  const sourceUrl = campflareCampgroundSourceUrl(id);
  const recgovRef = extractRecgovCampgroundRef(raw);
  const cellService = objectField(raw, FIELD.cellService);
  const amenities = objectField(raw, FIELD.amenities);
  return {
    ok: true,
    record: {
      dataProviderRef: DataProviderRef.campflare(id),
      bookingProvider: BookingProvider.CAMPFLARE,
      bookingProviderRef: id,
      bookingAliases: recgovRef === null ? [] : [{ provider: BookingProvider.RECGOV, ref: recgovRef }],
      name,
      latitude,
      longitude,
      status: stringField(raw, FIELD.status),
      kind: stringField(raw, FIELD.kind),
      location: campflareLocation(location, latitude, longitude),
      defaultCampsiteSchedule: bags.schedule(objectField(raw, FIELD.schedule)),
      amenities: amenities === null ? [] : bags.amenities(amenities),
      maxRvLength: doubleField(raw, FIELD.maxRvLength),
      maxTrailerLength: doubleField(raw, FIELD.maxTrailerLength),
      hasPullThroughSites: booleanField(raw, FIELD.hasPullThroughSites),
      bigRigFriendly: booleanField(raw, FIELD.bigRigFriendly),
      reservationUrl: stringField(raw, FIELD.reservationUrl),
      links: campflareLinks(jsonArrayField(raw, FIELD.links), sourceUrl),
      photos: campflarePhotos(jsonArrayField(raw, FIELD.photos)),
      alerts: bags.alerts(jsonArrayField(raw, FIELD.alerts)),
      price: bags.price(objectField(raw, FIELD.price)),
      cellService: cellService === null ? [] : bags.carrierSignals(cellService),
      management: campflareManagement(objectField(raw, FIELD.management)),
      contact: campflareContact(objectField(raw, FIELD.contact)),
      connections: optionalPayload(objectField(raw, FIELD.connections)),
      metadata: bags.metadata(objectField(raw, FIELD.metadata)),
      sourceUrl,
      sourcePayload: serializeOpaquePayload(raw),
    },
  };
}

export class CampflareCampgroundsEtl implements SourceEtl<JsonObject, CampgroundUpsertCandidate> {
  readonly etlSlug = CAMPGROUNDS_ETL_SLUG;

  parse(inputs: InputBundle): Iterable<ParseResult<JsonObject>> {
    return jsonObjectResults(inputs.soleEnvelopes(), this.etlSlug);
  }

  *transform(dto: JsonObject): Iterable<TransformResult<CampgroundUpsertCandidate>> {
    yield campgroundRecord(dto);
  }
}
