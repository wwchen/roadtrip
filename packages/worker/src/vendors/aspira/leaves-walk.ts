import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull, jsonArrayOf, longOrNull } from '../../etl/kotlin-json.js';
import { kotlinStringToLongOrNull } from '../../etl/kotlin-number.js';
import { kotlinEqualsIgnoreCase, nonBlank } from '../../etl/kotlin-text.js';
import type { AspiraLeaf } from './leaf.js';

const MAP_ID_KEY = 'mapId';
const TRANSACTION_LOCATION_ID_KEY = 'transactionLocationId';
const RESOURCE_LOCATION_ID_KEY = 'resourceLocationId';
const PARENT_MAP_KEY = 'parentMap';
const MAP_LINKS_KEY = 'mapLinks';
const CHILD_MAP_ID_KEY = 'childMapId';
const LOCALIZATIONS_KEY = 'localizations';
const LOCALIZED_VALUES_KEY = 'localizedValues';
const CULTURE_NAME_KEY = 'cultureName';
const TITLE_KEY = 'title';
const ENGLISH_CULTURE_PREFIX = 'en';

function arrayOrThrow(value: unknown): unknown[] | null {
  return value === undefined ? null : jsonArrayOf(value);
}

function arrayOrNull(value: unknown): unknown[] | null {
  return Array.isArray(value) ? value : null;
}

function objectOrNull(value: unknown): JsonObject | null {
  return isJsonObject(value) ? value : null;
}

function requiredLexedLong(value: unknown, label: string): bigint {
  const parsed = longOrNull(value);
  if (parsed === null) throw new Error(`aspira maps walk: ${label} is not a Long`);
  return parsed;
}

function strictLong(value: unknown): bigint | null {
  const content = contentOrNull(value);
  return content === null ? null : kotlinStringToLongOrNull(content);
}

function localizedTitle(entries: readonly unknown[] | null): string | null {
  if (entries === null) return null;
  for (const entry of entries) {
    const object = objectOrNull(entry);
    if (object === null) continue;
    const culture = contentOrNull(fieldOf(object, CULTURE_NAME_KEY));
    if (culture === null) continue;
    if (!kotlinEqualsIgnoreCase(culture.slice(0, ENGLISH_CULTURE_PREFIX.length), ENGLISH_CULTURE_PREFIX)) continue;
    const title = nonBlank(contentOrNull(fieldOf(object, TITLE_KEY)));
    if (title !== null) return title;
  }
  return null;
}

function parentMapId(node: JsonObject): bigint | null {
  const parentMap = objectOrNull(fieldOf(node, PARENT_MAP_KEY));
  if (parentMap === null) return null;
  const raw = fieldOf(parentMap, MAP_ID_KEY);
  return raw === undefined ? null : requiredLexedLong(raw, `${PARENT_MAP_KEY}.${MAP_ID_KEY}`);
}

function titleFor(node: JsonObject, byId: ReadonlyMap<bigint, JsonObject>): string | null {
  const direct = localizedTitle(arrayOrThrow(fieldOf(node, LOCALIZED_VALUES_KEY)));
  if (direct !== null) return direct;
  const parentId = parentMapId(node);
  if (parentId === null) return null;
  const parent = byId.get(parentId);
  if (parent === undefined) return null;
  const ourId = requiredLexedLong(fieldOf(node, MAP_ID_KEY), MAP_ID_KEY);
  const mapLinks = arrayOrNull(fieldOf(parent, MAP_LINKS_KEY));
  if (mapLinks === null) return null;
  for (const link of mapLinks) {
    const object = objectOrNull(link);
    if (object === null) continue;
    if (strictLong(fieldOf(object, CHILD_MAP_ID_KEY)) === ourId) {
      return localizedTitle(arrayOrNull(fieldOf(object, LOCALIZATIONS_KEY)));
    }
  }
  return null;
}

function parentNameFor(node: JsonObject, byId: ReadonlyMap<bigint, JsonObject>): string | null {
  const parentId = parentMapId(node);
  if (parentId === null) return null;
  const parent = byId.get(parentId);
  if (parent === undefined) return null;
  return localizedTitle(arrayOrNull(fieldOf(parent, LOCALIZED_VALUES_KEY)));
}

export function walkAspiraLeaves(maps: readonly unknown[]): AspiraLeaf[] {
  const byId = new Map<bigint, JsonObject>();
  for (const node of maps) {
    const object = objectOrNull(node);
    if (object === null) continue;
    const raw = fieldOf(object, MAP_ID_KEY);
    if (raw === undefined) throw new Error(`aspira maps walk: node without mapId: ${JSON.stringify(object)}`);
    byId.set(requiredLexedLong(raw, MAP_ID_KEY), object);
  }

  const seen = new Set<bigint>();
  const leaves: AspiraLeaf[] = [];

  for (const node of maps) {
    const object = objectOrNull(node);
    if (object === null) continue;
    const transactionLocationId = strictLong(fieldOf(object, TRANSACTION_LOCATION_ID_KEY));
    if (transactionLocationId === null) continue;
    const mapId = requiredLexedLong(fieldOf(object, MAP_ID_KEY), MAP_ID_KEY);
    if (seen.has(mapId)) continue;
    const title = titleFor(object, byId);
    if (title === null) continue;
    leaves.push({
      name: title,
      transactionLocationId,
      mapId,
      resourceLocationId: strictLong(fieldOf(object, RESOURCE_LOCATION_ID_KEY)),
      parentName: parentNameFor(object, byId),
    });
    seen.add(mapId);
  }

  for (const node of maps) {
    const nodeObject = objectOrNull(node);
    if (nodeObject === null) continue;
    const mapLinks = arrayOrNull(fieldOf(nodeObject, MAP_LINKS_KEY));
    if (mapLinks === null) continue;
    for (const link of mapLinks) {
      const object = objectOrNull(link);
      if (object === null) continue;
      const transactionLocationId = strictLong(fieldOf(object, TRANSACTION_LOCATION_ID_KEY));
      if (transactionLocationId === null) continue;
      const childMapId = strictLong(fieldOf(object, CHILD_MAP_ID_KEY));
      if (childMapId === null) continue;
      if (seen.has(childMapId)) continue;
      const title = localizedTitle(arrayOrNull(fieldOf(object, LOCALIZATIONS_KEY)));
      if (title === null) continue;
      leaves.push({
        name: title,
        transactionLocationId,
        mapId: childMapId,
        resourceLocationId: strictLong(fieldOf(object, RESOURCE_LOCATION_ID_KEY)),
        parentName: localizedTitle(arrayOrNull(fieldOf(nodeObject, LOCALIZED_VALUES_KEY))),
      });
      seen.add(childMapId);
    }
  }

  return leaves;
}
