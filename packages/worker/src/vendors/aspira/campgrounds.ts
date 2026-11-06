import { BookingProvider, DataProviderRef } from '@roadtrip/domain';
import { serializeOpaquePayload } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { jsonArrayOf } from '../../etl/kotlin-json.js';
import { STDERR_LOG } from '../../etl/run-log.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import type { GeometryPolicy } from '../../registry/poi-registry.js';
import { bookingCtaRefForLeaf } from './booking-cta-refs.js';
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
} from './campground-support.js';
import { geometrySourceForSpec } from './geometry-sources.js';
import type { GeometryPoint } from './geometry-point.js';
import type { AspiraJoinDto } from './join-dto.js';
import { toProvenance, type AspiraLeafMatch } from './leaf-match.js';
import { walkAspiraLeaves } from './leaves-walk.js';

const NO_MAPS_INPUT = 'no /api/maps input declared';
const NO_LEAVES = 'no leaves from /api/maps';

function declaredSourceErrors(geometry: GeometryPolicy, slugs: readonly string[], inputs: InputBundle): string[] {
  const errors: string[] = [];
  for (const spec of geometry.sources) {
    if (!slugs.includes(spec.input)) {
      errors.push(`declared geometry source '${spec.input}' is not among this run's inputs`);
    } else if (inputs.envelopes(spec.input).length === 0) {
      errors.push(`declared geometry source '${spec.input}' has no envelopes`);
    }
  }
  return errors;
}

function campgroundCandidate(
  match: AspiraLeafMatch<GeometryPoint>,
  settings: AspiraRunSettings,
  tenant: string,
  bookableMapIds: ReadonlyMap<bigint, ReadonlySet<bigint>>,
): CampgroundUpsertCandidate {
  const leaf = match.leaf;
  const point = match.value;
  const bookingCtaRef = bookingCtaRefForLeaf(leaf, bookableMapIds);
  return {
    dataProviderRef: DataProviderRef.aspira(leaf.transactionLocationId, leaf.mapId),
    bookingProvider: BookingProvider.ASPIRA,
    bookingProviderRef: bookingCtaRef === null ? null : aspiraBookingProviderRef(tenant, leaf, bookingCtaRef),
    name: leaf.name,
    parentName: leaf.parentName,
    latitude: point.latitude,
    longitude: point.longitude,
    kind: settings.subcategory ?? null,
    location: { latitude: point.latitude, longitude: point.longitude },
    reservationUrl: settings.bookingUrl,
    links: [{ url: settings.bookingUrl }],
    management: { agency: settings.agency },
    sourceUrl: settings.bookingUrl,
    sourcePayload: serializeOpaquePayload(leafSourcePayload(leaf)),
    geometryProvenance: toProvenance(match),
  };
}

export class AspiraCampgroundsEtl implements SourceEtl<AspiraJoinDto, CampgroundUpsertCandidate> {
  constructor(
    readonly etlSlug: string,
    private readonly aspiraTenant: string,
    private readonly geometry: GeometryPolicy,
  ) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<AspiraJoinDto>> {
    const slugs = inputs.dataSourceSlugs();
    const declared = new Set(this.geometry.sources.map((spec) => spec.input));
    const roleSlugs = slugs.filter((slug) => !declared.has(slug));
    const mapsSlug = roleSlugs.find((slug) => slug.includes(MAPS_ROLE));
    const inventorySlug = roleSlugs.find((slug) => slug.includes(INVENTORY_ROLE));
    const dictionarySlug = roleSlugs.find((slug) => slug.includes(DICTIONARIES_ROLE));

    const errors = declaredSourceErrors(this.geometry, slugs, inputs);
    const accounted = new Set(declared);
    for (const slug of [mapsSlug, inventorySlug, dictionarySlug]) if (slug !== undefined) accounted.add(slug);
    for (const slug of slugs) {
      if (!accounted.has(slug)) {
        errors.push(`input '${slug}' is neither a declared geometry source nor the maps, inventory or dictionaries feed`);
      }
    }
    if (mapsSlug === undefined) errors.push(NO_MAPS_INPUT);
    if (errors.length > 0 || mapsSlug === undefined) {
      yield { ok: false, errors };
      return;
    }

    const leaves = walkAspiraLeaves(jsonArrayOf(inputs.envelope(mapsSlug).payload));
    const dto: AspiraJoinDto = {
      leaves,
      geomSources: this.geometry.sources.map((spec) => [
        spec.input,
        geometrySourceForSpec(spec, inputs.envelopes(spec.input)),
      ]),
      inventoryEnvelopes: inventorySlug === undefined ? [] : inputs.envelopes(inventorySlug),
      dictionaryPayload: dictionarySlug === undefined ? null : objectPayloadOrNull(inputs.envelope(dictionarySlug)),
      fetchedAt: new Date().toISOString(),
    };
    yield dto.leaves.length === 0 ? { ok: false, errors: [NO_LEAVES] } : { ok: true, dto };
  }

  *transform(dto: AspiraJoinDto, ctx: TransformCtx): Iterable<TransformResult<CampgroundUpsertCandidate>> {
    const settings = aspiraRunSettings(this.etlSlug, ctx);
    const { matches, tally, bookableMapIds } = matchAspiraLeaves(dto, this.geometry.match, this.etlSlug, STDERR_LOG);
    const campgrounds = matches.map((match) => campgroundCandidate(match, settings, this.aspiraTenant, bookableMapIds));
    STDERR_LOG.info(
      `${this.etlSlug}: ${dto.leaves.length} leaves → ${campgrounds.length} pois ` +
        `(exact=${tally.exact} fuzzy=${tally.fuzzy} parent=${tally.parent} miss=${tally.miss} ` +
        `skippedContainer=${tally.skippedContainer} skippedNonBookable=${tally.skippedNonBookable}; ` +
        `sample misses: [${tally.missSamples.join(', ')}])`,
    );
    for (const record of campgrounds) yield { ok: true, record };
  }
}
