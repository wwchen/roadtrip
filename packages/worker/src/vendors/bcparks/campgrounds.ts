import { BookingProvider, DataProviderRef, type CampgroundLink } from '@roadtrip/domain';
import { serializeOpaquePayload, type OpaquePayloadJson } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { jsonArrayOf } from '../../etl/kotlin-json.js';
import { STDERR_LOG } from '../../etl/run-log.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import type { GeometryPolicy } from '../../registry/poi-registry.js';
import { bookingCtaRefForLeaf } from '../aspira/booking-cta-refs.js';
import {
  aspiraBookingProviderRef,
  aspiraRunSettings,
  DICTIONARIES_ROLE,
  INVENTORY_ROLE,
  leafSourcePayload,
  MAPS_ROLE,
  matchAspiraLeaves,
  objectPayloadOrNull,
  type AspiraRunSettings,
} from '../aspira/campground-support.js';
import { byNormalizedName } from '../aspira/geometry-index.js';
import type { GeometryPoint } from '../aspira/geometry-point.js';
import type { AspiraLeaf } from '../aspira/leaf.js';
import { toProvenance, type AspiraLeafMatch } from '../aspira/leaf-match.js';
import { walkAspiraLeaves } from '../aspira/leaves-walk.js';
import type { BcParksCampgroundsDto } from './dto.js';
import { BcParksStrapiSource, type BcParksStrapiRow } from './strapi-source.js';

const REGION = 'BC';
const COUNTRY = 'CA';
const NO_LEAVES = 'no Aspira leaves from /api/maps';
const NO_STRAPI_ROWS = 'no BC Parks Strapi rows parsed';
const NO_INVENTORY = 'no inventory envelopes';
const STRAPI_ORCS_KEY = 'strapi_orcs';
const STRAPI_URL_KEY = 'strapi_url';

function requiredRoleSlug(slugs: readonly string[], role: string): string {
  const found = slugs.find((slug) => slug.includes(role));
  if (found === undefined) throw new Error(`no input slug contains '${role}'`);
  return found;
}

function soleGeometryInput(geometry: GeometryPolicy): string {
  const [only] = geometry.sources;
  if (geometry.sources.length !== 1 || only === undefined) {
    throw new Error(`expected exactly one geometry source, got ${geometry.sources.length}`);
  }
  return only.input;
}

function sourcePayloadOf(leaf: AspiraLeaf, strapiRow: BcParksStrapiRow): OpaquePayloadJson {
  return serializeOpaquePayload({
    ...leafSourcePayload(leaf),
    ...(strapiRow.orcs === null ? {} : { [STRAPI_ORCS_KEY]: strapiRow.orcs }),
    ...(strapiRow.url === null ? {} : { [STRAPI_URL_KEY]: strapiRow.url }),
  });
}

function linksOf(bookingUrl: string, strapiRow: BcParksStrapiRow): CampgroundLink[] {
  const strapiUrl = strapiRow.url === null || strapiRow.url === bookingUrl ? null : strapiRow.url;
  return (strapiUrl === null ? [bookingUrl] : [bookingUrl, strapiUrl]).map((url) => ({ url }));
}

function campgroundCandidate(
  match: AspiraLeafMatch<GeometryPoint>,
  strapiRow: BcParksStrapiRow,
  settings: AspiraRunSettings,
  tenant: string,
  bookableMapIds: ReadonlyMap<bigint, ReadonlySet<bigint>>,
): CampgroundUpsertCandidate {
  const leaf = match.leaf;
  const bookingCtaRef = bookingCtaRefForLeaf(leaf, bookableMapIds);
  return {
    dataProviderRef: DataProviderRef.bcParks(leaf.transactionLocationId, leaf.mapId),
    bookingProvider: BookingProvider.ASPIRA,
    bookingProviderRef: bookingCtaRef === null ? null : aspiraBookingProviderRef(tenant, leaf, bookingCtaRef),
    name: leaf.name,
    parentName: leaf.parentName,
    latitude: strapiRow.lat,
    longitude: strapiRow.lon,
    kind: settings.subcategory ?? null,
    mediumDescription: strapiRow.description,
    location: { latitude: strapiRow.lat, longitude: strapiRow.lon, region: REGION, country: COUNTRY },
    reservationUrl: settings.bookingUrl,
    links: linksOf(settings.bookingUrl, strapiRow),
    photos: strapiRow.photoUrl === null ? [] : [{ url: strapiRow.photoUrl }],
    management: { agency: settings.agency },
    contact: strapiRow.phone === null ? null : { phone: strapiRow.phone },
    sourceUrl: settings.bookingUrl,
    sourcePayload: sourcePayloadOf(leaf, strapiRow),
    geometryProvenance: toProvenance(match),
  };
}

export class BcParksCampgroundsEtl implements SourceEtl<BcParksCampgroundsDto, CampgroundUpsertCandidate> {
  constructor(
    readonly etlSlug: string,
    private readonly aspiraTenant: string,
    private readonly geometry: GeometryPolicy,
  ) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<BcParksCampgroundsDto>> {
    const strapiSlug = soleGeometryInput(this.geometry);
    const roleSlugs = inputs.dataSourceSlugs().filter((slug) => slug !== strapiSlug);
    const mapsSlug = requiredRoleSlug(roleSlugs, MAPS_ROLE);
    const inventorySlug = requiredRoleSlug(roleSlugs, INVENTORY_ROLE);
    const dictionarySlug = roleSlugs.find((slug) => slug.includes(DICTIONARIES_ROLE));

    const leaves = walkAspiraLeaves(jsonArrayOf(inputs.envelope(mapsSlug).payload));
    const inventoryEnvelopes = inputs.envelopes(inventorySlug);
    const dictionaryPayload = dictionarySlug === undefined ? null : objectPayloadOrNull(inputs.envelope(dictionarySlug));

    const strapiSource = new BcParksStrapiSource(inputs.envelopes(strapiSlug));
    const dto: BcParksCampgroundsDto = {
      leaves,
      strapiRows: strapiSource.rows(),
      geomSources: [[strapiSlug, strapiSource]],
      inventoryEnvelopes,
      dictionaryPayload,
    };
    const errors: string[] = [];
    if (dto.leaves.length === 0) errors.push(NO_LEAVES);
    if (dto.strapiRows.length === 0) errors.push(NO_STRAPI_ROWS);
    if (dto.inventoryEnvelopes.length === 0) errors.push(NO_INVENTORY);
    yield errors.length === 0 ? { ok: true, dto } : { ok: false, errors };
  }

  *transform(dto: BcParksCampgroundsDto, ctx: TransformCtx): Iterable<TransformResult<CampgroundUpsertCandidate>> {
    const settings = aspiraRunSettings(this.etlSlug, ctx);
    const { matches, tally, bookableMapIds } = matchAspiraLeaves(dto, this.geometry.match, this.etlSlug, STDERR_LOG);
    const strapiByName = byNormalizedName(dto.strapiRows, (row) => row.name);
    const joined = matches.filter((match) => strapiByName.has(match.matchedName));
    const unjoined = matches.filter((match) => !strapiByName.has(match.matchedName));
    for (const match of unjoined) {
      STDERR_LOG.warn(`${this.etlSlug}: matched name '${match.matchedName}' has no Strapi row; dropping leaf '${match.leaf.name}'`);
    }
    const campgrounds = joined.map((match) =>
      campgroundCandidate(match, strapiByName.get(match.matchedName) as BcParksStrapiRow, settings, this.aspiraTenant, bookableMapIds),
    );
    STDERR_LOG.info(
      `${this.etlSlug}: ${dto.leaves.length} leaves → ${campgrounds.length} campgrounds ` +
        `(exact=${tally.exact} fuzzy=${tally.fuzzy} parent=${tally.parent} miss=${tally.miss} ` +
        `skippedContainer=${tally.skippedContainer} skippedNonBookable=${tally.skippedNonBookable} noStrapiRow=${unjoined.length})`,
    );
    for (const record of campgrounds) yield { ok: true, record };
  }
}
