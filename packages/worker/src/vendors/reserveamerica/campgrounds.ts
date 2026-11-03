import { BookingProvider, DataProviderRef, type CampgroundLink, type CatalogPhoto } from '@roadtrip/domain';
import { serializeOpaquePayload, type OpaquePayloadJson } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { contentOf } from '../../etl/kotlin-json.js';
import { kotlinDoubleFromText, kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';
import { isKotlinBlank, kotlinTrim, nonBlank } from '../../etl/kotlin-text.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { firstGroup } from './catalog-parser.js';
import type { ReserveAmericaDto } from './dto.js';
import type { ParsedPark } from './parsed-park.js';

const PARK_PART_PREFIX = 'park-';
const JAVA_WHITESPACE = '[ \\t\\n\\x0B\\f\\r]';
const LATITUDE = new RegExp(`place:location:latitude"${JAVA_WHITESPACE}+content='([^']+)'`);
const LONGITUDE = new RegExp(`place:location:longitude"${JAVA_WHITESPACE}+content='([^']+)'`);
const OG_TITLE = new RegExp(`og:title"${JAVA_WHITESPACE}+content='([^']+)'`);
const OG_DESCRIPTION = new RegExp(`og:description"${JAVA_WHITESPACE}+content='([^']*)'`);
const OG_IMAGE = new RegExp(`og:image"${JAVA_WHITESPACE}+content='([^']+)'`);
const OG_URL = new RegExp(`og:url"${JAVA_WHITESPACE}+content='([^']+)'`);
const TELEPHONE = /itemprop="telephone"[^>]*>([^<]+)/;
const DEFAULT_REGION = 'AB';
const DEFAULT_COUNTRY = 'CA';
const DEFAULT_SOURCE_ID_PREFIX = 'ra';
const REGION_ARG = 'region';
const COUNTRY_ARG = 'country';
const PROVIDER_ARG = 'provider';
const TITLE_SUFFIX_ARG = 'title_suffix';
const SOURCE_ID_PREFIX_ARG = 'source_id_prefix';
const NO_PAGES_SUFFIX = 'no pages';
const NO_PARKS_SUFFIX = 'no park pages parsed';
const NON_FINITE_COORDINATE = 'is not a finite double';

interface ReserveAmericaSettings {
  contract: string;
  region: string;
  country: string;
  agency: string;
  provider: string;
  titleSuffix: string;
  sourceIdPrefix: string;
}

function settingsOf(ctx: TransformCtx, etlSlug: string, contract: string): ReserveAmericaSettings {
  const region = ctx.argFor(etlSlug, REGION_ARG) ?? DEFAULT_REGION;
  return {
    contract,
    region,
    country: ctx.argFor(etlSlug, COUNTRY_ARG) ?? DEFAULT_COUNTRY,
    agency: ctx.requiredConstantAgency(etlSlug),
    provider: ctx.argFor(etlSlug, PROVIDER_ARG) ?? BookingProvider.RESERVEAMERICA,
    titleSuffix: ctx.argFor(etlSlug, TITLE_SUFFIX_ARG) ?? `, ${region}`,
    sourceIdPrefix: ctx.argFor(etlSlug, SOURCE_ID_PREFIX_ARG) ?? DEFAULT_SOURCE_ID_PREFIX,
  };
}

function doubleGroup(pattern: RegExp, html: string): number | null {
  const text = firstGroup(pattern, html);
  return text === null ? null : kotlinDoubleFromText(text);
}

function trimmedNonBlank(text: string | null): string | null {
  return text === null ? null : nonBlank(kotlinTrim(text));
}

function parsePark(parkId: bigint, html: string): ParsedPark | null {
  const lat = doubleGroup(LATITUDE, html);
  if (lat === null) return null;
  const lon = doubleGroup(LONGITUDE, html);
  if (lon === null) return null;
  const rawTitle = firstGroup(OG_TITLE, html);
  if (rawTitle === null) return null;
  const name = kotlinTrim(rawTitle);
  if (isKotlinBlank(name)) return null;
  return {
    parkId,
    name,
    lat,
    lon,
    phone: trimmedNonBlank(firstGroup(TELEPHONE, html)),
    description: trimmedNonBlank(firstGroup(OG_DESCRIPTION, html)),
    photoUrl: nonBlank(firstGroup(OG_IMAGE, html)),
    infoUrl: nonBlank(firstGroup(OG_URL, html)),
  };
}

function displayName(rawName: string, titleSuffix: string): string {
  const trimmed = kotlinTrim(rawName);
  return !isKotlinBlank(titleSuffix) && trimmed.endsWith(titleSuffix)
    ? kotlinTrim(trimmed.slice(0, trimmed.length - titleSuffix.length))
    : trimmed;
}

function finiteCoordinate(value: number): number {
  if (!Number.isFinite(value)) throw new Error(`${String(value)} ${NON_FINITE_COORDINATE}`);
  return value;
}

function parkExtras(park: ParsedPark, name: string, contract: string): OpaquePayloadJson {
  return serializeOpaquePayload({
    contract,
    park_id: park.parkId,
    name,
    latitude: finiteCoordinate(park.lat),
    longitude: finiteCoordinate(park.lon),
    ...(park.phone === null ? {} : { phone: park.phone }),
    ...(park.description === null ? {} : { description: park.description }),
    ...(park.photoUrl === null ? {} : { photo_url: park.photoUrl }),
    ...(park.infoUrl === null ? {} : { info_url: park.infoUrl }),
  });
}

function bookingProviderOf(provider: string): BookingProvider | null {
  return kotlinTrim(provider).toLowerCase() === BookingProvider.RESERVEAMERICA ? BookingProvider.RESERVEAMERICA : null;
}

export class ReserveAmericaCampgroundsEtl implements SourceEtl<ReserveAmericaDto, CampgroundUpsertCandidate> {
  constructor(
    readonly etlSlug: string,
    private readonly contractCode: string,
  ) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<ReserveAmericaDto>> {
    const envelopes = inputs.soleEnvelopes();
    if (envelopes.length === 0) throw new Error(`${this.etlSlug}: ${NO_PAGES_SUFFIX}`);
    const parks: ParsedPark[] = [];
    for (const envelope of envelopes) {
      const partLabel = envelope.part;
      if (partLabel === undefined || !partLabel.startsWith(PARK_PART_PREFIX)) continue;
      const parkId = kotlinStringToLongOrNull(partLabel.slice(PARK_PART_PREFIX.length));
      if (parkId === null) continue;
      const park = parsePark(parkId, contentOf(envelope.payload));
      if (park !== null) parks.push(park);
    }
    yield parks.length === 0
      ? { ok: false, errors: [`${this.etlSlug}: ${NO_PARKS_SUFFIX}`] }
      : { ok: true, dto: { parks } };
  }

  *transform(dto: ReserveAmericaDto, ctx: TransformCtx): Iterable<TransformResult<CampgroundUpsertCandidate>> {
    const bucket = ctx.subcategoryFor(this.etlSlug);
    const settings = settingsOf(ctx, this.etlSlug, this.contractCode);
    for (const park of dto.parks) {
      const name = displayName(park.name, settings.titleSuffix);
      const vendorRefId = `${settings.sourceIdPrefix}-${park.parkId}`;
      const sourcePayload = parkExtras(park, name, settings.contract);
      const bookingProvider = bookingProviderOf(settings.provider);
      const links: CampgroundLink[] = park.infoUrl === null ? [] : [{ url: park.infoUrl }];
      const photos: CatalogPhoto[] = park.photoUrl === null ? [] : [{ url: park.photoUrl }];
      yield {
        ok: true,
        record: {
          dataProviderRef: DataProviderRef.reserveAmerica(vendorRefId),
          bookingProvider,
          bookingProviderRef: bookingProvider === null ? null : `${settings.contract}:${park.parkId}`,
          name,
          latitude: park.lat,
          longitude: park.lon,
          kind: bucket ?? null,
          mediumDescription: park.description,
          location: { latitude: park.lat, longitude: park.lon, region: settings.region, country: settings.country },
          reservationUrl: park.infoUrl,
          links,
          photos,
          management: { agency: settings.agency },
          sourceUrl: park.infoUrl,
          sourcePayload,
        },
      };
    }
  }
}
