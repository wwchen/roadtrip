import path from 'node:path';
import { addressJson, type Address } from '../../etl/address.js';
import { serializeOpaquePayload, parseEnvelope, type OpaquePayloadJson } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import type { JsonObject } from '../../etl/json-decode.js';
import { nonBlank } from '../../etl/kotlin-text.js';
import { newestJsonFileIn } from '../../etl/raw-capture-store.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import { DEFAULT_TESLA_SITE_STATUS, type TeslaSuperchargerUpsertCandidate } from '../../model/tesla-candidate.js';
import {
  decodeTeslaDetail,
  decodeTeslaIndex,
  type TeslaDetailCapture,
  type TeslaIndexDto,
  type TeslaIndexRow,
  type TeslaLocationDetail,
} from './dto.js';

const ETL_SLUG = 'tesla-superchargers';
const LOCATIONS_DIR = 'tesla-locations';
const NO_ROWS = 'no rows in payload';
const LOGGED_COLLISION_SAMPLES = 3;
const HIDDEN_FROM_FIND_US = '0';
const PLACEHOLDER_TITLE = 'locations';
const FIND_US_URL = 'https://www.tesla.com/findus?location=';
const AMENITIES_KEY = 'amenities';
const AVAILABILITY_PROFILE_KEY = 'availabilityProfile';
const ADDRESS_SEPARATOR = ' ';
const SLUG_REPLACEMENT = '-';
const UNSAFE_SLUG_RUN = /[^a-z0-9_:-]+/g;
const SLUG_EDGE = /^-+|-+$/g;
const LOAD_FAILURE_LOG = 'tesla-locations parse failed for slug=';

function sanitizeSlug(slug: string): string {
  return slug.toLowerCase().replace(UNSAFE_SLUG_RUN, SLUG_REPLACEMENT).replace(SLUG_EDGE, '');
}

function loadDetail(locationsDir: string, slug: string): TeslaDetailCapture | null {
  const newest = newestJsonFileIn(path.join(locationsDir, slug));
  if (newest === null) return null;
  try {
    return decodeTeslaDetail(parseEnvelope(newest).payload);
  } catch (error) {
    console.error(`${LOAD_FAILURE_LOG}${slug}: ${error instanceof Error ? error.message : String(error)}`);
    return null;
  }
}

function addressOf(detail: TeslaLocationDetail | undefined): Address | null {
  const address = detail?.address;
  if (address === undefined || address === null) return null;
  const street = nonBlank(
    [address.streetNumber, address.street]
      .filter((part): part is string => part !== null && nonBlank(part) !== null)
      .join(ADDRESS_SEPARATOR),
  );
  const city = nonBlank(address.city);
  const state = nonBlank(address.state);
  const postcode = nonBlank(address.postalCode);
  const country = nonBlank(address.countryCode);
  if (street === null && city === null && state === null && postcode === null && country === null) return null;
  return { street, city, state, postcode, country };
}

function arrayField(raw: JsonObject | null | undefined, key: string): OpaquePayloadJson | null {
  const value = raw === null || raw === undefined || !Object.hasOwn(raw, key) ? undefined : raw[key];
  return Array.isArray(value) ? serializeOpaquePayload(value) : null;
}

function objectField(raw: JsonObject | null | undefined, key: string): OpaquePayloadJson | null {
  const value = raw === null || raw === undefined || !Object.hasOwn(raw, key) ? undefined : raw[key];
  const isObject = typeof value === 'object' && value !== null && !Array.isArray(value);
  return isObject ? serializeOpaquePayload(value) : null;
}

function transformRow(
  row: TeslaIndexRow,
  rawIndex: JsonObject | undefined,
  locationsDir: string,
): TeslaSuperchargerUpsertCandidate | null {
  const fn = row.superchargerFunction;
  if (fn === null || fn.showOnFindUs === HIDDEN_FROM_FIND_US) return null;
  const slug = nonBlank(row.locationUrlSlug);
  if (slug === null || row.latitude === null || row.longitude === null) return null;

  const capture = loadDetail(locationsDir, slug);
  const detail = capture?.detail;
  const raw = capture?.raw;

  const title = nonBlank(row.title);
  const usableTitle = title === PLACEHOLDER_TITLE ? null : title;
  const name = nonBlank(detail?.name) ?? usableTitle ?? `Supercharger ${slug}`;

  return {
    locationSlug: sanitizeSlug(slug),
    commonSiteName: name,
    latitude: row.latitude,
    longitude: row.longitude,
    locationGuid: nonBlank(detail?.locationGuid),
    siteStatus: nonBlank(fn.siteStatus) ?? DEFAULT_TESLA_SITE_STATUS,
    accessType: nonBlank(detail?.accessType),
    openToPublic: detail?.openToPublic ?? true,
    openToNonTeslas: detail?.openToNonTeslas ?? null,
    trailerFriendly: detail?.isTrailerFriendly ?? null,
    twentyFourSeven: detail?.twentyFourSeven ?? null,
    stallCount: detail?.publicStallCount ?? null,
    maxPowerKw: detail?.maxPowerKw ?? null,
    address: addressJson(addressOf(detail)),
    region: nonBlank(detail?.address?.state),
    country: nonBlank(detail?.address?.countryCode),
    timeZone: nonBlank(detail?.timeZone),
    amenities: arrayField(raw, AMENITIES_KEY),
    infoUrl: `${FIND_US_URL}${slug}`,
    pricebooks: serializeOpaquePayload(detail?.effectivePricebooks ?? []),
    availabilityProfile: objectField(raw, AVAILABILITY_PROFILE_KEY),
    indexPayload: rawIndex === undefined ? null : serializeOpaquePayload(rawIndex),
    detailPayload: raw === null || raw === undefined ? null : serializeOpaquePayload(raw),
  };
}

function collisionMessage(collisions: [string, TeslaSuperchargerUpsertCandidate[]][]): string {
  const sample = collisions
    .slice(0, LOGGED_COLLISION_SAMPLES)
    .map(([slug, group]) => `${slug} ← [${group.map((record) => record.commonSiteName).join(', ')}]`)
    .join('; ');
  return (
    `tesla-index: ${collisions.length} sanitized location_slug collision(s) — ` +
    `distinct upstream slugs collapsing to the same key. Sample: ${sample}`
  );
}

function assertNoSlugCollisions(records: TeslaSuperchargerUpsertCandidate[]): void {
  const bySlug = new Map<string, TeslaSuperchargerUpsertCandidate[]>();
  for (const record of records) {
    const group = bySlug.get(record.locationSlug);
    if (group === undefined) bySlug.set(record.locationSlug, [record]);
    else group.push(record);
  }
  const collisions = [...bySlug].filter(([, group]) => group.length > 1);
  if (collisions.length > 0) throw new Error(collisionMessage(collisions));
}

export class TeslaIndexEtl implements SourceEtl<TeslaIndexDto, TeslaSuperchargerUpsertCandidate> {
  readonly etlSlug = ETL_SLUG;

  *parse(inputs: InputBundle): Iterable<ParseResult<TeslaIndexDto>> {
    const dto = decodeTeslaIndex(inputs.soleEnvelope().payload);
    yield dto.rows.length === 0 ? { ok: false, errors: [NO_ROWS] } : { ok: true, dto };
  }

  *transform(dto: TeslaIndexDto, ctx: TransformCtx): Iterable<TransformResult<TeslaSuperchargerUpsertCandidate>> {
    const locationsDir = path.join(ctx.rawDir, LOCATIONS_DIR);
    const records: TeslaSuperchargerUpsertCandidate[] = [];
    for (const row of dto.rows) {
      const rawIndex = row.locationUrlSlug === null ? undefined : dto.rawBySlug.get(row.locationUrlSlug);
      const record = transformRow(row, rawIndex, locationsDir);
      if (record !== null) records.push(record);
    }
    assertNoSlugCollisions(records);
    for (const record of records) yield { ok: true, record };
  }
}
