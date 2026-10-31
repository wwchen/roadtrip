import {
  BookingProvider,
  BookingProviderRef,
  DataProvider,
  DataProviderRef,
} from '@roadtrip/domain';
import { campsiteKindAspira } from '../../etl/campsite-kinds.js';
import { documentEntries } from '../../etl/document-order.js';
import { serializeOpaquePayload, type Envelope } from '../../etl/envelope.js';
import { stripTags } from '../../etl/html-text.js';
import type { InputBundle } from '../../etl/input-bundle.js';
import { isJsonObject } from '../../etl/json-decode.js';
import { jsonArrayOf } from '../../etl/kotlin-json.js';
import { kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';
import { nonBlank } from '../../etl/kotlin-text.js';
import { STDERR_LOG } from '../../etl/run-log.js';
import type { ParseResult, SourceEtl, TransformResult } from '../../etl/source-etl.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import {
  EMPTY_DICTIONARIES,
  parseAspiraDictionaries,
  type AspiraDictionaries,
} from './campsite-dictionaries.js';
import { allowedEquipmentNames, campsiteAttributes } from './campsite-labels.js';
import { buildResourceRaw } from './campsite-raw.js';
import { parseResourceInventory, type ResourceInventory } from './campsite-resource.js';
import type { AspiraLeaf } from './leaf.js';
import { walkAspiraLeaves } from './leaves-walk.js';

export interface AspiraCampsitesDto {
  inventory: Envelope[];
  maps: unknown[];
  dictionaries: AspiraDictionaries;
}

const EMPTY_MAPS = 'empty /api/maps payload';
const FETCH_INVENTORY_HINT = '(run fetch_aspira_inventory.py first)';
const EXPECTED_OBJECT_PAYLOAD = 'expected JSON object payload';
const EMPTY_RESOURCE_ID = 'empty resource id';
const NON_NUMERIC_RESOURCE_ID = 'resource id is not numeric';
const EXPECTED_RESOURCE_OBJECT = 'expected resource JSON object';

function leafBookingRef(
  tenant: string,
  inv: ResourceInventory,
  leaf: AspiraLeaf | null,
  parentLeaf: AspiraLeaf | null,
): string | null {
  const transactionLocationId = leaf?.transactionLocationId ?? parentLeaf?.transactionLocationId;
  if (transactionLocationId === undefined) return null;
  const mapId = leaf?.mapId ?? inv.firstMapId ?? parentLeaf?.mapId;
  if (mapId === undefined || mapId === null) return null;
  const resourceLocationId = leaf?.resourceLocationId ?? inv.resourceLocationId ?? parentLeaf?.resourceLocationId;
  if (resourceLocationId === undefined || resourceLocationId === null) return null;
  return BookingProviderRef.aspira(tenant, transactionLocationId, mapId, resourceLocationId).serialize();
}

export class AspiraCampsitesEtl implements SourceEtl<AspiraCampsitesDto, CampsiteUpsertCandidate> {
  constructor(
    readonly etlSlug: string,
    readonly mapsInputSlug: string,
    readonly inventoryInputSlug: string,
    readonly dictionariesInputSlug: string | null,
    readonly aspiraTenant: string,
    readonly parentDataProvider: DataProvider,
  ) {}

  *parse(inputs: InputBundle): Iterable<ParseResult<AspiraCampsitesDto>> {
    const slugs = inputs.dataSourceSlugs();
    const listed = `[${slugs.join(', ')}]`;
    if (!slugs.includes(this.mapsInputSlug)) {
      throw new Error(`${this.etlSlug}: missing required input '${this.mapsInputSlug}' in ${listed}`);
    }
    if (!slugs.includes(this.inventoryInputSlug)) {
      throw new Error(`${this.etlSlug}: missing required input '${this.inventoryInputSlug}' in ${listed}`);
    }
    const inventory = inputs.envelopes(this.inventoryInputSlug);
    if (inventory.length === 0) {
      throw new Error(`${this.etlSlug}: no envelopes in '${this.inventoryInputSlug}' ${FETCH_INVENTORY_HINT}`);
    }
    const maps = jsonArrayOf(inputs.envelope(this.mapsInputSlug).payload);
    const dictionaries = this.dictionaries(inputs, slugs);
    if (maps.length === 0) {
      yield { ok: false, errors: [`${this.etlSlug}: ${EMPTY_MAPS}`] };
    } else {
      yield { ok: true, dto: { inventory, maps, dictionaries } };
    }
  }

  *transform(dto: AspiraCampsitesDto): Iterable<TransformResult<CampsiteUpsertCandidate>> {
    const leaves = walkAspiraLeaves(dto.maps);
    const leavesByMapId = new Map<bigint, AspiraLeaf>();
    const leavesByResourceLocationId = new Map<bigint, AspiraLeaf>();
    for (const leaf of leaves) {
      leavesByMapId.set(leaf.mapId, leaf);
      if (leaf.resourceLocationId !== null) leavesByResourceLocationId.set(leaf.resourceLocationId, leaf);
    }

    let emitted = 0;
    let unmatchedLeaf = 0;
    let totalRecords = 0;
    for (const envelope of dto.inventory) {
      const sourceId = envelope.part ?? envelope.request.url;
      const payload = envelope.payload;
      if (!isJsonObject(payload)) {
        yield { ok: false, sourceId, errors: [EXPECTED_OBJECT_PAYLOAD] };
        continue;
      }
      for (const [resourceId, raw] of documentEntries(payload)) {
        if (resourceId === '') {
          yield { ok: false, sourceId, errors: [EMPTY_RESOURCE_ID] };
          continue;
        }
        const resourceIdLong = kotlinStringToLongOrNull(resourceId);
        if (resourceIdLong === null) {
          yield { ok: false, sourceId: resourceId, errors: [NON_NUMERIC_RESOURCE_ID] };
          continue;
        }
        if (!isJsonObject(raw)) {
          yield { ok: false, sourceId: resourceId, errors: [EXPECTED_RESOURCE_OBJECT] };
          continue;
        }
        const inv = parseResourceInventory(resourceId, raw);
        totalRecords += 1;
        const leaf = inv.firstMapId === null ? null : (leavesByMapId.get(inv.firstMapId) ?? null);
        const parentLeaf =
          leaf ??
          (inv.resourceLocationId === null ? null : (leavesByResourceLocationId.get(inv.resourceLocationId) ?? null));
        if (inv.firstMapId !== null && leaf === null) unmatchedLeaf += 1;
        yield { ok: true, record: this.campsiteRecord(resourceIdLong, inv, leaf, parentLeaf, dto.dictionaries) };
        emitted += 1;
      }
    }
    STDERR_LOG.info(
      `${this.etlSlug}: emitted ${emitted} campsites from ${dto.inventory.length} inventory envelopes ` +
        `(${unmatchedLeaf} resources with no matching leaf in /api/maps)`,
    );
    if (totalRecords !== emitted) {
      STDERR_LOG.warn(`${this.etlSlug}: parsed ${totalRecords} inventory records but emitted ${emitted} campsites`);
    }
  }

  private dictionaries(inputs: InputBundle, slugs: readonly string[]): AspiraDictionaries {
    if (this.dictionariesInputSlug === null || !slugs.includes(this.dictionariesInputSlug)) return EMPTY_DICTIONARIES;
    const payload = inputs.envelope(this.dictionariesInputSlug).payload;
    return parseAspiraDictionaries(isJsonObject(payload) ? payload : null);
  }

  private campsiteRecord(
    resourceId: bigint,
    inv: ResourceInventory,
    leaf: AspiraLeaf | null,
    parentLeaf: AspiraLeaf | null,
    dictionaries: AspiraDictionaries,
  ): CampsiteUpsertCandidate {
    const categoryName =
      inv.resourceCategoryId === null ? undefined : dictionaries.resourceCategories.get(inv.resourceCategoryId);
    const description = inv.description === null ? null : nonBlank(stripTags(inv.description));
    return {
      dataProviderRef: DataProviderRef.aspiraCampsite(this.aspiraTenant, resourceId),
      bookingProvider: BookingProvider.ASPIRA,
      bookingProviderRef: leafBookingRef(this.aspiraTenant, inv, leaf, parentLeaf),
      parentDataProviderRef: this.parentRef(leaf ?? parentLeaf),
      name: inv.name ?? inv.resourceId,
      loopName: leaf?.name ?? parentLeaf?.name ?? null,
      kind: campsiteKindAspira(categoryName),
      kindListed: categoryName ?? null,
      equipment: allowedEquipmentNames(inv.allowedEquipment, dictionaries),
      maxPeople: inv.maxCapacity,
      attributes: campsiteAttributes(inv.definedAttributes, dictionaries),
      description,
      minPeople: inv.minCapacity,
      photos: inv.photos,
      sourcePayload: serializeOpaquePayload(buildResourceRaw(inv, leaf, parentLeaf, dictionaries)),
    };
  }

  private parentRef(parent: AspiraLeaf | null) {
    if (parent === null) return null;
    return this.parentDataProvider === DataProvider.STRAPI
      ? DataProviderRef.bcParks(parent.transactionLocationId, parent.mapId)
      : DataProviderRef.aspira(parent.transactionLocationId, parent.mapId);
  }
}
