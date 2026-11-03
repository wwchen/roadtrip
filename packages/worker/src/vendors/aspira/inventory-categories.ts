import type { Envelope } from '../../etl/envelope.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { booleanOrNull, contentOrNull, intOrNull } from '../../etl/kotlin-json.js';
import { kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';

const RESOURCE_CATEGORIES_KEY = 'resource_categories';
const RESOURCE_CATEGORY_ID_KEY = 'resourceCategoryId';
const SHOW_CAPACITY_ONLINE_KEY = 'showResourceCapacityOnline';
const RESOURCE_LOCATION_ID_KEY = 'resourceLocationId';
const BOOKABLE_BY_DEFAULT = true;

export function bookableFlagByCategoryId(dictionaryPayload: JsonObject | null): Map<number, boolean> {
  const categories = dictionaryPayload === null ? undefined : fieldOf(dictionaryPayload, RESOURCE_CATEGORIES_KEY);
  const out = new Map<number, boolean>();
  if (!Array.isArray(categories)) return out;
  for (const raw of categories) {
    if (!isJsonObject(raw)) continue;
    const id = intOrNull(fieldOf(raw, RESOURCE_CATEGORY_ID_KEY));
    if (id === null) continue;
    out.set(id, booleanOrNull(fieldOf(raw, SHOW_CAPACITY_ONLINE_KEY)) ?? BOOKABLE_BY_DEFAULT);
  }
  return out;
}

export function isBookableResource(resource: JsonObject, bookableByCategoryId: ReadonlyMap<number, boolean>): boolean {
  if (bookableByCategoryId.size === 0) return true;
  const categoryId = intOrNull(fieldOf(resource, RESOURCE_CATEGORY_ID_KEY));
  if (categoryId === null) return BOOKABLE_BY_DEFAULT;
  return bookableByCategoryId.get(categoryId) ?? BOOKABLE_BY_DEFAULT;
}

export function resourceLocationIdOf(resource: JsonObject): bigint | null {
  const content = contentOrNull(fieldOf(resource, RESOURCE_LOCATION_ID_KEY));
  return content === null ? null : kotlinStringToLongOrNull(content);
}

export function nonBookableResourceLocationIds(
  inventory: readonly Envelope[],
  dictionaryPayload: JsonObject | null,
): Set<bigint> {
  const bookableByCategoryId = bookableFlagByCategoryId(dictionaryPayload);
  const nonBookable = new Set<bigint>();
  if (bookableByCategoryId.size === 0) return nonBookable;

  const flagsByResourceLocation = new Map<bigint, boolean[]>();
  for (const envelope of inventory) {
    const payload = envelope.payload;
    if (!isJsonObject(payload)) continue;
    for (const raw of Object.values(payload)) {
      if (!isJsonObject(raw)) continue;
      const resourceLocationId = resourceLocationIdOf(raw);
      if (resourceLocationId === null) continue;
      const flags = flagsByResourceLocation.get(resourceLocationId) ?? [];
      flags.push(isBookableResource(raw, bookableByCategoryId));
      flagsByResourceLocation.set(resourceLocationId, flags);
    }
  }

  for (const [resourceLocationId, flags] of flagsByResourceLocation) {
    if (flags.length > 0 && !flags.some((bookable) => bookable)) nonBookable.add(resourceLocationId);
  }
  return nonBookable;
}
