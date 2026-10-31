import type { Envelope } from '../../etl/envelope.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { longOrNull } from '../../etl/kotlin-json.js';
import { bookableFlagByCategoryId, isBookableResource } from './inventory-categories.js';
import type { AspiraLeaf } from './leaf.js';

const RESOURCE_LOCATION_ID_KEY = 'resourceLocationId';
const MAP_IDS_KEY = 'mapIds';
const SINGLE_MAP = 1;

export interface AspiraBookingCtaRef {
  mapId: bigint;
  resourceLocationId: bigint;
}

function mapIdsOf(resource: JsonObject): bigint[] {
  const raw = fieldOf(resource, MAP_IDS_KEY);
  if (!Array.isArray(raw)) return [];
  const ids: bigint[] = [];
  for (const element of raw) {
    const id = longOrNull(element);
    if (id !== null) ids.push(id);
  }
  return ids;
}

export function bookableMapIdsByResourceLocationId(
  inventory: readonly Envelope[],
  dictionaryPayload: JsonObject | null,
): Map<bigint, Set<bigint>> {
  const bookableByCategoryId = bookableFlagByCategoryId(dictionaryPayload);
  const mapIds = new Map<bigint, Set<bigint>>();
  for (const envelope of inventory) {
    const payload = envelope.payload;
    if (!isJsonObject(payload)) continue;
    for (const raw of Object.values(payload)) {
      if (!isJsonObject(raw)) continue;
      if (!isBookableResource(raw, bookableByCategoryId)) continue;
      const resourceLocationId = longOrNull(fieldOf(raw, RESOURCE_LOCATION_ID_KEY));
      if (resourceLocationId === null) continue;
      const ids = mapIdsOf(raw);
      if (ids.length === 0) continue;
      const known = mapIds.get(resourceLocationId) ?? new Set<bigint>();
      for (const id of ids) known.add(id);
      mapIds.set(resourceLocationId, known);
    }
  }
  return mapIds;
}

export function bookingCtaRefForLeaf(
  leaf: AspiraLeaf,
  bookableMapIds: ReadonlyMap<bigint, ReadonlySet<bigint>>,
): AspiraBookingCtaRef | null {
  const resourceLocationId = leaf.resourceLocationId;
  if (resourceLocationId === null) return null;
  const mapIds = bookableMapIds.get(resourceLocationId);
  if (mapIds === undefined) return null;
  const [only] = mapIds;
  return {
    mapId: mapIds.size === SINGLE_MAP && only !== undefined ? only : leaf.mapId,
    resourceLocationId,
  };
}
