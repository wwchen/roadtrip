import { documentValues } from '../../etl/document-order.js';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull, intOrNull } from '../../etl/kotlin-json.js';

export interface EquipmentLabel {
  categoryName: string | null;
  subCategoryName: string | null;
}

export interface AttributeDefinition {
  name: string | null;
  valueLabels: ReadonlyMap<number, string>;
}

export interface AspiraDictionaries {
  equipment: ReadonlyMap<string, EquipmentLabel>;
  resourceCategories: ReadonlyMap<number, string>;
  attributes: ReadonlyMap<number, AttributeDefinition>;
}

export const EMPTY_DICTIONARIES: AspiraDictionaries = {
  equipment: new Map(),
  resourceCategories: new Map(),
  attributes: new Map(),
};

export const DICTIONARY_FIELD = {
  localizedValues: 'localizedValues',
  name: 'name',
  displayName: 'displayName',
  title: 'title',
  equipmentCategoryId: 'equipmentCategoryId',
  subEquipmentCategoryId: 'subEquipmentCategoryId',
  attributeDefinitionId: 'attributeDefinitionId',
} as const;

const PAYLOAD_KEYS = {
  equipment: 'equipment',
  resourceCategories: ['resource_categories', 'resourceCategories', 'resourcecategory'],
  attributes: ['attributes', 'attribute_filterable', 'attributeFilterable'],
} as const;

const DICTIONARY_ENTRY_KEYS = {
  subEquipmentCategories: 'subEquipmentCategories',
  resourceCategoryId: 'resourceCategoryId',
  values: 'values',
  enumValue: 'enumValue',
} as const;

const EQUIPMENT_KEY_SEPARATOR = ':';
const LABEL_KEYS = [DICTIONARY_FIELD.name, DICTIONARY_FIELD.displayName, DICTIONARY_FIELD.title] as const;

export function equipmentKey(categoryId: number, subCategoryId: number): string {
  return `${categoryId}${EQUIPMENT_KEY_SEPARATOR}${subCategoryId}`;
}

export function firstLocaleOf(source: JsonObject): JsonObject | null {
  const localized = fieldOf(source, DICTIONARY_FIELD.localizedValues);
  const first: unknown = Array.isArray(localized) ? localized[0] : undefined;
  return isJsonObject(first) ? first : null;
}

function localizedLabel(source: JsonObject): string | null {
  const locale = firstLocaleOf(source);
  if (locale === null) return null;
  for (const key of LABEL_KEYS) {
    const label = contentOrNull(fieldOf(locale, key));
    if (label !== null) return label;
  }
  return null;
}

function arrayField(source: JsonObject, key: string): unknown[] | null {
  const value = fieldOf(source, key);
  return Array.isArray(value) ? value : null;
}

function firstArray(source: JsonObject, keys: readonly string[]): unknown[] | null {
  for (const key of keys) {
    const found = arrayField(source, key);
    if (found !== null) return found;
  }
  return null;
}

function firstElement(source: JsonObject, keys: readonly string[]): unknown {
  for (const key of keys) {
    const found = fieldOf(source, key);
    if (found !== undefined) return found;
  }
  return undefined;
}

function parseEquipment(equipment: readonly unknown[] | null): Map<string, EquipmentLabel> {
  const out = new Map<string, EquipmentLabel>();
  if (equipment === null) return out;
  for (const rawCategory of equipment) {
    if (!isJsonObject(rawCategory)) continue;
    const categoryId = intOrNull(fieldOf(rawCategory, DICTIONARY_FIELD.equipmentCategoryId));
    if (categoryId === null) continue;
    const categoryName = localizedLabel(rawCategory);
    const subs = arrayField(rawCategory, DICTIONARY_ENTRY_KEYS.subEquipmentCategories);
    if (subs === null) continue;
    for (const rawSub of subs) {
      if (!isJsonObject(rawSub)) continue;
      const subId = intOrNull(fieldOf(rawSub, DICTIONARY_FIELD.subEquipmentCategoryId));
      if (subId === null) continue;
      out.set(equipmentKey(categoryId, subId), { categoryName, subCategoryName: localizedLabel(rawSub) });
    }
  }
  return out;
}

function parseResourceCategories(categories: readonly unknown[] | null): Map<number, string> {
  const out = new Map<number, string>();
  if (categories === null) return out;
  for (const raw of categories) {
    if (!isJsonObject(raw)) continue;
    const id = intOrNull(fieldOf(raw, DICTIONARY_ENTRY_KEYS.resourceCategoryId));
    if (id === null) continue;
    const name = localizedLabel(raw);
    if (name === null) continue;
    out.set(id, name);
  }
  return out;
}

function parseAttributeValueLabels(values: readonly unknown[] | null): Map<number, string> {
  const out = new Map<number, string>();
  if (values === null) return out;
  for (const raw of values) {
    if (!isJsonObject(raw)) continue;
    const enumValue = intOrNull(fieldOf(raw, DICTIONARY_ENTRY_KEYS.enumValue));
    if (enumValue === null) continue;
    const label = localizedLabel(raw);
    if (label === null) continue;
    out.set(enumValue, label);
  }
  return out;
}

function attributeDefinitionElements(attributes: unknown): unknown[] {
  if (Array.isArray(attributes)) return attributes;
  return isJsonObject(attributes) ? documentValues(attributes) : [];
}

function parseAttributes(attributes: unknown): Map<number, AttributeDefinition> {
  const out = new Map<number, AttributeDefinition>();
  if (attributes === undefined) return out;
  for (const raw of attributeDefinitionElements(attributes)) {
    if (!isJsonObject(raw)) continue;
    const id = intOrNull(fieldOf(raw, DICTIONARY_FIELD.attributeDefinitionId));
    if (id === null) continue;
    out.set(id, {
      name: localizedLabel(raw),
      valueLabels: parseAttributeValueLabels(arrayField(raw, DICTIONARY_ENTRY_KEYS.values)),
    });
  }
  return out;
}

export function parseAspiraDictionaries(payload: JsonObject | null): AspiraDictionaries {
  if (payload === null) return EMPTY_DICTIONARIES;
  const resourceCategories = firstArray(payload, PAYLOAD_KEYS.resourceCategories);
  return {
    equipment: parseEquipment(arrayField(payload, PAYLOAD_KEYS.equipment)),
    resourceCategories: parseResourceCategories(resourceCategories),
    attributes: parseAttributes(firstElement(payload, PAYLOAD_KEYS.attributes)),
  };
}
