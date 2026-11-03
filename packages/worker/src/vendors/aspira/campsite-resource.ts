import type { CatalogPhoto } from '@roadtrip/domain';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull, intOrNull, longOf } from '../../etl/kotlin-json.js';
import { kotlinTrim, nonBlank } from '../../etl/kotlin-text.js';
import { DICTIONARY_FIELD, firstLocaleOf } from './campsite-dictionaries.js';

export interface ResourceInventory {
  resourceId: string;
  name: string | null;
  description: string | null;
  resourceCategoryId: number | null;
  resourceLocationId: bigint | null;
  maxCapacity: number | null;
  minCapacity: number | null;
  maxBoatLength: number | null;
  allowedEquipment: unknown[] | null;
  definedAttributes: unknown[] | null;
  firstMapId: bigint | null;
  photos: CatalogPhoto[];
}

export const RESOURCE_FIELD = {
  description: 'description',
  resourceCategoryId: 'resourceCategoryId',
  resourceLocationId: 'resourceLocationId',
  maxCapacity: 'maxCapacity',
  minCapacity: 'minCapacity',
  maxBoatLength: 'maxBoatLength',
  allowedEquipment: 'allowedEquipment',
  definedAttributes: 'definedAttributes',
  mapIds: 'mapIds',
  photos: 'photos',
  photoUrlResult: 'photoUrlResult',
  photoUrl: 'url',
} as const;

function arrayOrNull(value: unknown): unknown[] | null {
  return Array.isArray(value) ? value : null;
}

function strictLongField(source: JsonObject, key: string): bigint | null {
  const value = fieldOf(source, key);
  return value === undefined ? null : longOf(value);
}

function firstMapIdOf(source: JsonObject): bigint | null {
  const mapIds = arrayOrNull(fieldOf(source, RESOURCE_FIELD.mapIds));
  if (mapIds === null) return null;
  const parsed = mapIds.map(longOf);
  return parsed[0] ?? null;
}

function photoUrlOf(entry: unknown): string | null {
  if (!isJsonObject(entry)) return null;
  const result = fieldOf(entry, RESOURCE_FIELD.photoUrlResult);
  if (!isJsonObject(result)) return null;
  const url = fieldOf(result, RESOURCE_FIELD.photoUrl);
  if (url === undefined || Array.isArray(url) || isJsonObject(url)) return null;
  const content = contentOrNull(url);
  return content === null ? null : nonBlank(kotlinTrim(content));
}

function resourcePhotos(source: JsonObject): CatalogPhoto[] {
  const seen = new Set<string>();
  const photos: CatalogPhoto[] = [];
  for (const entry of arrayOrNull(fieldOf(source, RESOURCE_FIELD.photos)) ?? []) {
    const url = photoUrlOf(entry);
    if (url === null || seen.has(url)) continue;
    seen.add(url);
    photos.push({ url });
  }
  return photos;
}

export function parseResourceInventory(resourceId: string, source: JsonObject): ResourceInventory {
  const locale = firstLocaleOf(source);
  return {
    resourceId,
    name: locale === null ? null : contentOrNull(fieldOf(locale, DICTIONARY_FIELD.name)),
    description: locale === null ? null : contentOrNull(fieldOf(locale, RESOURCE_FIELD.description)),
    resourceCategoryId: intOrNull(fieldOf(source, RESOURCE_FIELD.resourceCategoryId)),
    resourceLocationId: strictLongField(source, RESOURCE_FIELD.resourceLocationId),
    maxCapacity: intOrNull(fieldOf(source, RESOURCE_FIELD.maxCapacity)),
    minCapacity: intOrNull(fieldOf(source, RESOURCE_FIELD.minCapacity)),
    maxBoatLength: intOrNull(fieldOf(source, RESOURCE_FIELD.maxBoatLength)),
    allowedEquipment: arrayOrNull(fieldOf(source, RESOURCE_FIELD.allowedEquipment)),
    definedAttributes: arrayOrNull(fieldOf(source, RESOURCE_FIELD.definedAttributes)),
    firstMapId: firstMapIdOf(source),
    photos: resourcePhotos(source),
  };
}
