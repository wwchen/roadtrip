import { addressJson, type Address } from '../../etl/address.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import {
  arrayOrEmpty,
  asObject,
  nullableDouble,
  nullableObject,
  nullableStringMap,
  requiredDouble,
  requiredLong,
  requiredString,
  type JsonObject,
} from '../../etl/json-decode.js';
import { nonBlank } from '../../etl/kotlin-text.js';
import { serializeOpaquePayload, type OpaquePayloadJson } from '../../etl/envelope.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { PlanetFitnessLocationUpsertCandidate } from '../../model/planet-fitness-candidate.js';

const ETL_SLUG = 'planet-fitness';
const DEFAULT_NAME = 'Planet Fitness';
const COUNTRY = 'US';
const SOURCE_ID_SEPARATOR = '-';
const ADDRESS_SEPARATOR = ' ';
const NO_ELEMENTS = 'no elements in payload';
const MISSING_COORDINATES = 'missing latitude/longitude';

const TAG = {
  name: 'name',
  housenumber: 'addr:housenumber',
  street: 'addr:street',
  city: 'addr:city',
  state: 'addr:state',
  postcode: 'addr:postcode',
  phone: 'phone',
  website: 'website',
  openingHours: 'opening_hours',
} as const;

export interface OverpassCenter {
  lat: number;
  lon: number;
}

export interface OverpassElement {
  type: string;
  id: number;
  lat: number | null;
  lon: number | null;
  center: OverpassCenter | null;
  tags: Record<string, string> | null;
}

export interface PlanetFitnessRawDto {
  elements: OverpassElement[];
}

function decodeCenter(source: JsonObject): OverpassCenter | null {
  const center = nullableObject(source, 'center');
  return center === null ? null : { lat: requiredDouble(center, 'lat'), lon: requiredDouble(center, 'lon') };
}

function decodeElement(value: unknown): OverpassElement {
  const source = asObject(value, 'element');
  return {
    type: requiredString(source, 'type'),
    id: requiredLong(source, 'id'),
    lat: nullableDouble(source, 'lat'),
    lon: nullableDouble(source, 'lon'),
    center: decodeCenter(source),
    tags: nullableStringMap(source, 'tags'),
  };
}

function sourceIdOf(element: OverpassElement): string {
  return `${element.type}${SOURCE_ID_SEPARATOR}${element.id}`;
}

type Tags = Readonly<Record<string, string>>;

function tagOf(tags: Tags, key: string): string | undefined {
  return Object.hasOwn(tags, key) ? tags[key] : undefined;
}

function buildAddress(tags: Tags): Address | null {
  const street = nonBlank(
    [tagOf(tags, TAG.housenumber), tagOf(tags, TAG.street)]
      .filter((part): part is string => part !== undefined && nonBlank(part) !== null)
      .join(ADDRESS_SEPARATOR),
  );
  const city = nonBlank(tagOf(tags, TAG.city));
  const state = nonBlank(tagOf(tags, TAG.state));
  const postcode = nonBlank(tagOf(tags, TAG.postcode));
  if (street === null && city === null && state === null && postcode === null) return null;
  return { street, city, state, postcode, country: COUNTRY };
}

function elementExtras(element: OverpassElement): OpaquePayloadJson {
  const extras: Record<string, unknown> = { type: element.type, id: element.id };
  if (element.lat !== null) extras.lat = element.lat;
  if (element.lon !== null) extras.lon = element.lon;
  if (element.center !== null) extras.center = element.center;
  if (element.tags !== null) extras.tags = element.tags;
  return serializeOpaquePayload(extras);
}

function transformElement(element: OverpassElement): PlanetFitnessLocationUpsertCandidate | null {
  const latitude = element.lat ?? element.center?.lat ?? null;
  const longitude = element.lon ?? element.center?.lon ?? null;
  if (latitude === null || longitude === null) return null;
  const tags: Tags = element.tags ?? {};
  return {
    locationId: sourceIdOf(element),
    name: tagOf(tags, TAG.name) ?? DEFAULT_NAME,
    latitude,
    longitude,
    region: nonBlank(tagOf(tags, TAG.state)),
    country: COUNTRY,
    phone: nonBlank(tagOf(tags, TAG.phone)),
    address: addressJson(buildAddress(tags)),
    infoUrl: nonBlank(tagOf(tags, TAG.website)),
    openingHours: nonBlank(tagOf(tags, TAG.openingHours)),
    payload: elementExtras(element),
  };
}

export class PlanetFitnessEtl implements SourceEtl<PlanetFitnessRawDto, PlanetFitnessLocationUpsertCandidate> {
  readonly etlSlug = ETL_SLUG;

  *parse(inputs: InputBundle): Iterable<ParseResult<PlanetFitnessRawDto>> {
    const payload = asObject(inputs.soleEnvelope().payload, 'payload');
    const dto: PlanetFitnessRawDto = { elements: arrayOrEmpty(payload, 'elements').map(decodeElement) };
    yield dto.elements.length === 0 ? { ok: false, errors: [NO_ELEMENTS] } : { ok: true, dto };
  }

  *transform(dto: PlanetFitnessRawDto): Iterable<TransformResult<PlanetFitnessLocationUpsertCandidate>> {
    for (const element of dto.elements) {
      const record = transformElement(element);
      yield record === null
        ? { ok: false, sourceId: sourceIdOf(element), errors: [MISSING_COORDINATES] }
        : { ok: true, record };
    }
  }
}
