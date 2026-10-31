import {
  BookingProvider,
  DataProviderRef,
  carrierFromVendorName,
  type Address,
  type CampgroundContact,
  type CampgroundLink,
  type CampgroundManagement,
  type CampgroundMetadata,
  type CampgroundRating,
  type CarrierSignal,
  type CatalogPhoto,
} from '@roadtrip/domain';
import { serializeOpaquePayload, type Envelope } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { booleanOrNull, contentOrNull, jsonArrayOf, jsonObjectOf } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText, kotlinRint, kotlinStringToIntOrNull, kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';
import { kotlinEqualsIgnoreCase, kotlinTrim, nonBlank } from '../../etl/kotlin-text.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import type { AgencyConfig } from '../../registry/poi-registry.js';
import { decodeRidbPage, type RecGovDto, type RidbFacility, type RidbFacilityAddress } from './dto.js';

const RIDB_INPUT = 'recgov-campgrounds-raw';
const ENRICHMENT_INPUT = 'recgov-campground-enrichment';
const DEFAULT_COUNTRY = 'US';
const RECDATA_KEY = 'RECDATA';
const FACILITY_ID_KEY = 'FacilityID';
const RECAREA_KEY = 'RECAREA';
const RECAREA_NAME_KEY = 'RecAreaName';
const ACTIVITY_KEY = 'ACTIVITY';
const ACTIVITY_NAME_KEY = 'ActivityName';
const MEDIA_KEY = 'MEDIA';
const MEDIA_TYPE_KEY = 'MediaType';
const IMAGE_MEDIA_TYPE = 'Image';
const IS_PRIMARY_KEY = 'IsPrimary';
const IS_PREVIEW_KEY = 'IsPreview';
const LINK_KEY = 'LINK';
const LINK_TYPE_KEY = 'LinkType';
const OFFICIAL_LINK_TYPE = 'Official Web Site';
const URL_KEY = 'URL';
const RESERVABLE_KEY = 'Reservable';
const DESCRIPTION_KEY = 'FacilityDescription';
const AGGREGATE_KEY = 'aggregate';
const ENRICHMENT_FACILITY_ID_KEY = 'facility_id';
const ENRICHMENT_LOCATION_ID_KEY = 'location_id';
const CELL_COVERAGE_KEY = 'aggregate_cell_coverage_ratings';
const CARRIER_KEY = 'carrier';
const RATING_COUNT_KEY = 'number_of_ratings';
const RATING_AVERAGE_KEY = 'average_rating';
const RATING_ROUNDING_SCALE = 100;
const TRUE_TEXT = 'true';
const RECREATION_GOV_CAMPGROUND_URL = 'https://www.recreation.gov/camping/campgrounds/';
const MISSING_NAME_OR_GEOMETRY = 'missing name or valid geometry';
const NO_ROWS = 'no rows in payload';
const AMPERSAND = '&';
const AMPERSAND_REPLACEMENT = 'and';
const ASCII_WHITESPACE_SPLIT = /[ \t\n\v\f\r]+/;
const WORD_SEPARATOR = ' ';
const ALPHA_3_UNITED_STATES = 'USA';
const ALPHA_2_UNITED_STATES = 'US';
const ALPHA_3_CANADA = 'CAN';
const ALPHA_2_CANADA = 'CA';
const ALPHA_2_LENGTH = 2;
const RAW_PATH_SEPARATOR = '.';
const RAW_PATH_SEGMENT = /^([^[\]]+)(?:\[(\d+)\])?$/;
const RAW_PATH_FIELD_GROUP = 1;
const RAW_PATH_INDEX_GROUP = 2;

function textField(raw: JsonObject | null, key: string): string | null {
  return contentOrNull(fieldOf(raw, key));
}

function flagField(source: JsonObject, key: string): boolean {
  const value = fieldOf(source, key);
  return booleanOrNull(value) === true || kotlinEqualsIgnoreCase(contentOrNull(value), TRUE_TEXT);
}

function intField(source: JsonObject, key: string): number | null {
  const content = textField(source, key);
  return content === null ? null : kotlinStringToIntOrNull(content);
}

function doubleField(source: JsonObject, key: string): number | null {
  const content = textField(source, key);
  return content === null ? null : kotlinDoubleFromText(content);
}

function round2(value: number): number {
  return kotlinRint(value * RATING_ROUNDING_SCALE) / RATING_ROUNDING_SCALE;
}

export function rawFacilityId(value: unknown): string | null {
  if (isJsonObject(value) || Array.isArray(value)) return null;
  const content = contentOrNull(value);
  const id = content === null ? null : kotlinStringToLongOrNull(content);
  return id === null ? null : id.toString();
}

function enrichmentFacilityId(payload: JsonObject): string | null {
  const direct = contentOrNull(fieldOf(payload, ENRICHMENT_FACILITY_ID_KEY));
  const directId = direct === null ? null : kotlinStringToLongOrNull(direct);
  if (directId !== null) return directId.toString();
  const aggregate = fieldOf(payload, AGGREGATE_KEY);
  if (!isJsonObject(aggregate)) return null;
  const located = contentOrNull(fieldOf(aggregate, ENRICHMENT_LOCATION_ID_KEY));
  const locatedId = located === null ? null : kotlinStringToLongOrNull(located);
  return locatedId === null ? null : locatedId.toString();
}

function parseEnrichment(envelopes: readonly Envelope[]): Map<string, JsonObject> {
  const byId = new Map<string, JsonObject>();
  for (const envelope of envelopes) {
    const payload = envelope.payload;
    if (!isJsonObject(payload)) continue;
    const id = enrichmentFacilityId(payload);
    if (id !== null) byId.set(id, payload);
  }
  return byId;
}

function parseRaw(envelopes: readonly Envelope[]): { rows: RidbFacility[]; rawById: Map<string, JsonObject> } {
  const rows: RidbFacility[] = [];
  const rawById = new Map<string, JsonObject>();
  for (const envelope of envelopes) {
    rows.push(...decodeRidbPage(envelope.payload));
    const entries = fieldOf(jsonObjectOf(envelope.payload), RECDATA_KEY);
    if (entries === undefined) continue;
    for (const entry of jsonArrayOf(entries)) {
      const object = jsonObjectOf(entry);
      const id = rawFacilityId(fieldOf(object, FACILITY_ID_KEY));
      if (id !== null) rawById.set(id, object);
    }
  }
  return { rows, rawById };
}

export function stringAtRawPath(raw: JsonObject, path: string): string | null {
  let current: unknown = raw;
  for (const segment of path.split(RAW_PATH_SEPARATOR)) {
    const match = RAW_PATH_SEGMENT.exec(segment);
    if (match === null) return null;
    const field = match[RAW_PATH_FIELD_GROUP] as string;
    const indexText = match[RAW_PATH_INDEX_GROUP];
    const index = indexText === undefined ? null : kotlinStringToIntOrNull(indexText);
    if (!isJsonObject(current)) return null;
    const next = fieldOf(current, field);
    if (next === undefined) return null;
    current = next;
    if (index !== null) {
      if (!Array.isArray(current)) return null;
      const element: unknown = current[index];
      if (element === undefined) return null;
      current = element;
    }
  }
  if (Array.isArray(current) || isJsonObject(current)) return null;
  return nonBlank(contentOrNull(current));
}

function agencyFrom(raw: JsonObject | null, config: AgencyConfig | undefined): string | null {
  if (config === undefined) return null;
  if (config.kind === 'constant') return config.value;
  return raw === null ? null : stringAtRawPath(raw, config.field);
}

function isReservable(raw: JsonObject | null): boolean {
  const value = fieldOf(raw, RESERVABLE_KEY);
  return booleanOrNull(value) === true || kotlinEqualsIgnoreCase(contentOrNull(value), TRUE_TEXT);
}

function normalizeCountry(code: string | null | undefined): string | null {
  if (code == null) return null;
  const upper = kotlinTrim(code).toUpperCase();
  if (upper === '') return null;
  if (upper === ALPHA_3_UNITED_STATES) return ALPHA_2_UNITED_STATES;
  if (upper === ALPHA_3_CANADA) return ALPHA_2_CANADA;
  return upper.length === ALPHA_2_LENGTH ? upper : null;
}

function addressOf(address: RidbFacilityAddress | undefined): Address | null {
  if (address === undefined) return null;
  const parsed: Address = {
    street: nonBlank(address.facilityStreetAddress1) ?? undefined,
    city: nonBlank(address.city) ?? undefined,
    state: nonBlank(address.addressStateCode) ?? undefined,
    postcode: nonBlank(address.postalCode) ?? undefined,
    country: normalizeCountry(address.addressCountryCode) ?? undefined,
  };
  return Object.values(parsed).every((part) => part === undefined) ? null : parsed;
}

function normalizeActivity(name: string): string {
  return name
    .replaceAll(AMPERSAND, AMPERSAND_REPLACEMENT)
    .toLowerCase()
    .split(ASCII_WHITESPACE_SPLIT)
    .filter((word) => nonBlank(word) !== null)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(WORD_SEPARATOR);
}

function activities(raw: JsonObject | null): string[] {
  const rows = fieldOf(raw, ACTIVITY_KEY);
  if (rows === undefined) return [];
  const found: string[] = [];
  for (const entry of jsonArrayOf(rows)) {
    const name = nonBlank(textField(jsonObjectOf(entry), ACTIVITY_NAME_KEY));
    if (name === null) continue;
    const normalized = normalizeActivity(name);
    if (!found.includes(normalized)) found.push(normalized);
  }
  return found;
}

function aggregateOf(enrichment: JsonObject | null): JsonObject | null {
  const aggregate = fieldOf(enrichment, AGGREGATE_KEY);
  return isJsonObject(aggregate) ? aggregate : enrichment;
}

function ratingOf(enrichment: JsonObject | null): CampgroundRating | null {
  const aggregate = aggregateOf(enrichment);
  if (aggregate === null) return null;
  const count = intField(aggregate, RATING_COUNT_KEY);
  const average = doubleField(aggregate, RATING_AVERAGE_KEY);
  if (count === null || count <= 0 || average === null) return null;
  return { average: round2(average), count };
}

function metadataOf(raw: JsonObject | null, enrichment: JsonObject | null): CampgroundMetadata | null {
  const found = activities(raw);
  const rating = ratingOf(enrichment);
  if (found.length === 0 && rating === null) return null;
  return { activities: found, ...(rating === null ? {} : { rating }) };
}

function cellCoverage(enrichment: JsonObject | null): CarrierSignal[] {
  const aggregate = aggregateOf(enrichment);
  if (aggregate === null) return [];
  const rows = fieldOf(aggregate, CELL_COVERAGE_KEY);
  if (rows === undefined) return [];
  const signals: CarrierSignal[] = [];
  for (const entry of jsonArrayOf(rows)) {
    if (!isJsonObject(entry)) continue;
    const name = textField(entry, CARRIER_KEY);
    const carrier = name === null ? null : carrierFromVendorName(name);
    if (carrier === null) continue;
    const count = intField(entry, RATING_COUNT_KEY);
    if (count === null) continue;
    const average = doubleField(entry, RATING_AVERAGE_KEY);
    if (average === null || count <= 0) continue;
    signals.push({ carrier, average: round2(average), count });
  }
  return signals;
}

function parentRecAreaName(raw: JsonObject | null): string | null {
  const areas = fieldOf(raw, RECAREA_KEY);
  if (!Array.isArray(areas)) return null;
  const first: unknown = areas[0];
  if (!isJsonObject(first)) return null;
  const name = textField(first, RECAREA_NAME_KEY);
  return name === null ? null : nonBlank(kotlinTrim(name));
}

function descriptionOf(raw: JsonObject | null): string | null {
  const content = textField(raw, DESCRIPTION_KEY);
  return content === null ? null : nonBlank(kotlinTrim(content));
}

function urlOf(source: JsonObject): string | null {
  return nonBlank(textField(source, URL_KEY));
}

function photoUrl(raw: JsonObject | null): string | null {
  const entries = fieldOf(raw, MEDIA_KEY);
  if (entries === undefined) return null;
  const media = jsonArrayOf(entries)
    .filter(isJsonObject)
    .filter((item) => {
      const mediaType = textField(item, MEDIA_TYPE_KEY);
      return mediaType === null || kotlinEqualsIgnoreCase(mediaType, IMAGE_MEDIA_TYPE);
    });
  const primary = media.find((item) => flagField(item, IS_PRIMARY_KEY));
  const fromPrimary = primary === undefined ? null : urlOf(primary);
  if (fromPrimary !== null) return fromPrimary;
  const preview = media.find((item) => flagField(item, IS_PREVIEW_KEY));
  const fromPreview = preview === undefined ? null : urlOf(preview);
  if (fromPreview !== null) return fromPreview;
  for (const item of media) {
    const url = urlOf(item);
    if (url !== null) return url;
  }
  return null;
}

function officialFacilityUrl(raw: JsonObject | null): string | null {
  const entries = fieldOf(raw, LINK_KEY);
  if (entries === undefined) return null;
  const links = jsonArrayOf(entries).filter(isJsonObject);
  for (const link of links) {
    const url = kotlinEqualsIgnoreCase(textField(link, LINK_TYPE_KEY), OFFICIAL_LINK_TYPE) ? urlOf(link) : null;
    if (url !== null) return url;
  }
  for (const link of links) {
    const url = urlOf(link);
    if (url !== null) return url;
  }
  return null;
}

function facilityInfoUrl(row: RidbFacility, raw: JsonObject | null, reservable: boolean): string | null {
  const upstream = nonBlank(row.facilityReservationUrl);
  if (upstream !== null) return upstream;
  return reservable ? `${RECREATION_GOV_CAMPGROUND_URL}${row.facilityId}` : officialFacilityUrl(raw);
}

function contactOf(phone: string | null): CampgroundContact | null {
  const found = nonBlank(phone);
  return found === null ? null : { phone: found };
}

function managementOf(agency: string | null): CampgroundManagement | null {
  return agency === null ? null : { agency };
}

function transformRow(
  row: RidbFacility,
  raw: JsonObject | null,
  enrichment: JsonObject | null,
  bucket: string | undefined,
  agencyConfig: AgencyConfig | undefined,
): CampgroundUpsertCandidate | null {
  const reservable = isReservable(raw);
  const agency = agencyFrom(raw, agencyConfig);
  const name = nonBlank(row.facilityName);
  const latitude = row.facilityLatitude;
  const longitude = row.facilityLongitude;
  if (name === null || latitude === null || longitude === null || (latitude === 0 && longitude === 0)) return null;

  const firstAddress = row.facilityAddress?.[0];
  const region = nonBlank(firstAddress?.addressStateCode);
  const country = normalizeCountry(firstAddress?.addressCountryCode) ?? DEFAULT_COUNTRY;
  const infoUrl = facilityInfoUrl(row, raw, reservable);
  const photo = photoUrl(raw);
  const links: CampgroundLink[] = infoUrl === null ? [] : [{ url: infoUrl }];
  const photos: CatalogPhoto[] = photo === null ? [] : [{ url: photo }];

  return {
    dataProviderRef: DataProviderRef.recGov(row.facilityId),
    bookingProvider: reservable ? BookingProvider.RECGOV : null,
    bookingProviderRef: reservable ? row.facilityId : null,
    name,
    parentName: parentRecAreaName(raw),
    latitude,
    longitude,
    kind: bucket ?? null,
    mediumDescription: descriptionOf(raw),
    location: {
      latitude,
      longitude,
      region: region ?? undefined,
      country,
      address: addressOf(firstAddress) ?? undefined,
    },
    reservationUrl: infoUrl,
    links,
    photos,
    cellService: cellCoverage(enrichment),
    management: managementOf(agency),
    contact: contactOf(row.facilityPhone),
    metadata: metadataOf(raw, enrichment),
    sourceUrl: infoUrl,
    sourcePayload: raw === null ? null : serializeOpaquePayload(raw),
  };
}

export class RecGovCampgroundsEtl implements SourceEtl<RecGovDto, CampgroundUpsertCandidate> {
  constructor(readonly etlSlug: string) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<RecGovDto>> {
    const envelopes = inputs.envelopes(RIDB_INPUT);
    if (envelopes.length === 0) throw new Error(`${this.etlSlug}: no pages`);
    const { rows, rawById } = parseRaw(envelopes);
    const enrichmentById = inputs.dataSourceSlugs().includes(ENRICHMENT_INPUT)
      ? parseEnrichment(inputs.envelopes(ENRICHMENT_INPUT))
      : new Map<string, JsonObject>();
    yield rows.length === 0
      ? { ok: false, errors: [`${this.etlSlug}: ${NO_ROWS}`] }
      : { ok: true, dto: { rows, rawById, enrichmentById } };
  }

  *transform(dto: RecGovDto, ctx: TransformCtx): Iterable<TransformResult<CampgroundUpsertCandidate>> {
    const bucket = ctx.subcategoryFor(this.etlSlug);
    const agencyConfig = ctx.agencyFor(this.etlSlug);
    for (const row of dto.rows) {
      const record = transformRow(
        row,
        dto.rawById.get(row.facilityId) ?? null,
        dto.enrichmentById.get(row.facilityId) ?? null,
        bucket,
        agencyConfig,
      );
      yield record === null
        ? { ok: false, sourceId: row.facilityId, errors: [MISSING_NAME_OR_GEOMETRY] }
        : { ok: true, record };
    }
  }
}
