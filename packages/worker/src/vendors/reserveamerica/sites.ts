import { BookingProvider, CampsiteKind, DataProvider, DataProviderRef } from '@roadtrip/domain';
import { serializeOpaquePayload } from '../../etl/envelope.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { contentOf } from '../../etl/kotlin-json.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import { parseCatalog, type CatalogSite } from './catalog-parser.js';

export const PARENT_CONTRACT_KEY = '_parent_contract_code';
export const PARENT_PARK_KEY = '_parent_park_id';
export const PARENT_CAMPGROUND_REF_PREFIX = 'ra-';

const PARENT_CONTRACTS: ReadonlySet<string> = new Set(['ABPP', 'NY']);
const NO_SITES_SUFFIX = 'no ReserveAmerica campsite rows parsed';

export interface ReserveAmericaSitesDto {
  sites: CatalogSite[];
}

export function parentCampgroundVendor(contractCode: string): DataProvider | null {
  return PARENT_CONTRACTS.has(contractCode.toUpperCase()) ? DataProvider.RESERVEAMERICA : null;
}

function distinctBySiteId(sites: readonly CatalogSite[]): CatalogSite[] {
  const seen = new Set<string>();
  return sites.filter((site) => {
    if (seen.has(site.siteId)) return false;
    seen.add(site.siteId);
    return true;
  });
}

export class ReserveAmericaSitesEtl implements SourceEtl<ReserveAmericaSitesDto, CampsiteUpsertCandidate> {
  constructor(
    readonly etlSlug: string,
    private readonly contractCode: string,
  ) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<ReserveAmericaSitesDto>> {
    const sites = inputs.soleEnvelopes().flatMap((envelope) => parseCatalog(contentOf(envelope.payload)));
    yield sites.length === 0
      ? { ok: false, errors: [`${this.etlSlug}: ${NO_SITES_SUFFIX}`] }
      : { ok: true, dto: { sites } };
  }

  *transform(dto: ReserveAmericaSitesDto): Iterable<TransformResult<CampsiteUpsertCandidate>> {
    const hasParent = parentCampgroundVendor(this.contractCode) !== null;
    for (const site of distinctBySiteId(dto.sites)) {
      yield {
        ok: true,
        record: {
          dataProviderRef: DataProviderRef.reserveAmerica(site.siteId),
          bookingProvider: BookingProvider.RESERVEAMERICA,
          bookingProviderRef: site.siteId,
          parentDataProviderRef: hasParent
            ? DataProviderRef.reserveAmerica(`${PARENT_CAMPGROUND_REF_PREFIX}${site.parkId}`)
            : null,
          name: site.name,
          kind: CampsiteKind.OTHER,
          sourcePayload: serializeOpaquePayload({
            site_id: site.siteId,
            name: site.name,
            [PARENT_CONTRACT_KEY]: this.contractCode,
            [PARENT_PARK_KEY]: site.parkId,
          }),
        },
      };
    }
  }
}
