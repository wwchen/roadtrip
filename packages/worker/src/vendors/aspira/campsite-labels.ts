import type { CampsiteAttribute } from '@roadtrip/domain';
import { fieldOf, isJsonObject, type JsonObject } from '../../etl/json-decode.js';
import { contentOrNull, intOrNull } from '../../etl/kotlin-json.js';
import {
  DICTIONARY_FIELD,
  equipmentKey,
  type AspiraDictionaries,
  type AttributeDefinition,
} from './campsite-dictionaries.js';

export const ATTRIBUTE_FIELD = {
  value: 'value',
  values: 'values',
} as const;

export function equipmentIdsOf(item: JsonObject): { categoryId: number | null; subCategoryId: number | null } {
  return {
    categoryId: intOrNull(fieldOf(item, DICTIONARY_FIELD.equipmentCategoryId)),
    subCategoryId: intOrNull(fieldOf(item, DICTIONARY_FIELD.subEquipmentCategoryId)),
  };
}

export function allowedEquipmentNames(
  equipment: readonly unknown[] | null,
  dictionaries: AspiraDictionaries,
): string[] {
  const names: string[] = [];
  for (const raw of equipment ?? []) {
    if (!isJsonObject(raw)) continue;
    const { categoryId, subCategoryId } = equipmentIdsOf(raw);
    if (categoryId === null || subCategoryId === null) continue;
    const name = dictionaries.equipment.get(equipmentKey(categoryId, subCategoryId))?.subCategoryName;
    if (name !== undefined && name !== null) names.push(name);
  }
  return names;
}

export function attributeValueLabels(attribute: JsonObject, definition: AttributeDefinition | undefined): string[] {
  if (definition === undefined) return [];
  const scalar = intOrNull(fieldOf(attribute, ATTRIBUTE_FIELD.value));
  if (scalar !== null) {
    const label = definition.valueLabels.get(scalar);
    if (label !== undefined) return [label];
  }
  const values = fieldOf(attribute, ATTRIBUTE_FIELD.values);
  if (!Array.isArray(values)) return [];
  const labels: string[] = [];
  for (const value of values) {
    const enumValue = intOrNull(value);
    const label = enumValue === null ? undefined : definition.valueLabels.get(enumValue);
    if (label !== undefined) labels.push(label);
  }
  return labels;
}

export function definitionOf(attribute: JsonObject, dictionaries: AspiraDictionaries): AttributeDefinition | undefined {
  const id = intOrNull(fieldOf(attribute, DICTIONARY_FIELD.attributeDefinitionId));
  return id === null ? undefined : dictionaries.attributes.get(id);
}

export function campsiteAttributes(
  attributes: readonly unknown[] | null,
  dictionaries: AspiraDictionaries,
): CampsiteAttribute[] {
  const named: CampsiteAttribute[] = [];
  for (const raw of attributes ?? []) {
    if (!isJsonObject(raw)) continue;
    const definition = definitionOf(raw, dictionaries);
    const name = definition?.name;
    if (name === undefined || name === null) continue;
    const value = attributeValueLabels(raw, definition)[0] ?? contentOrNull(fieldOf(raw, ATTRIBUTE_FIELD.value));
    named.push(value === null ? { name } : { name, value });
  }
  return named;
}
