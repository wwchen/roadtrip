import { fieldOf, isJsonObject } from '../../etl/json-decode.js';
import { intOrNull } from '../../etl/kotlin-json.js';
import { DICTIONARY_FIELD, equipmentKey, type AspiraDictionaries } from './campsite-dictionaries.js';
import { ATTRIBUTE_FIELD, attributeValueLabels, equipmentIdsOf } from './campsite-labels.js';
import type { ResourceInventory } from './campsite-resource.js';
import type { AspiraLeaf } from './leaf.js';

const RAW_KEY = {
  resourceId: 'resource_id',
  parentMapId: '_parent_aspira_map_id',
  parentTransactionLocation: '_parent_aspira_txn_loc',
  parentResourceLocation: '_parent_aspira_resource_loc',
  parentLeafName: '_parent_leaf_name',
  parentLeafParentName: '_parent_leaf_parent_name',
  resourceMapId: '_aspira_resource_map_id',
  description: 'description',
  resourceCategoryId: 'resource_category_id',
  resourceCategoryName: 'resource_category_name',
  maxCapacity: 'max_capacity',
  minCapacity: 'min_capacity',
  maxBoatLength: 'max_boat_length',
  allowedEquipment: 'allowed_equipment',
  definedAttributes: 'defined_attributes',
} as const;

const EQUIPMENT_KEY = {
  categoryId: 'equipment_category_id',
  subCategoryId: 'sub_equipment_category_id',
  categoryName: 'equipment_category_name',
  name: 'name',
} as const;

const ATTRIBUTE_KEY = {
  definitionId: 'definition_id',
  name: 'name',
  value: 'value',
  values: 'values',
  valueLabels: 'value_labels',
} as const;

type RawObject = Record<string, unknown>;

function enrichAllowedEquipment(equipment: readonly unknown[], dictionaries: AspiraDictionaries): RawObject[] {
  const enriched: RawObject[] = [];
  for (const raw of equipment) {
    if (!isJsonObject(raw)) continue;
    const { categoryId, subCategoryId } = equipmentIdsOf(raw);
    const label =
      categoryId !== null && subCategoryId !== null
        ? dictionaries.equipment.get(equipmentKey(categoryId, subCategoryId))
        : undefined;
    const item: RawObject = {};
    if (categoryId !== null) item[EQUIPMENT_KEY.categoryId] = categoryId;
    if (subCategoryId !== null) item[EQUIPMENT_KEY.subCategoryId] = subCategoryId;
    if (label?.categoryName != null) item[EQUIPMENT_KEY.categoryName] = label.categoryName;
    if (label?.subCategoryName != null) item[EQUIPMENT_KEY.name] = label.subCategoryName;
    enriched.push(item);
  }
  return enriched;
}

function flattenAttributes(attributes: readonly unknown[], dictionaries: AspiraDictionaries): RawObject[] {
  const flattened: RawObject[] = [];
  for (const raw of attributes) {
    if (!isJsonObject(raw)) continue;
    const definitionId = intOrNull(fieldOf(raw, DICTIONARY_FIELD.attributeDefinitionId));
    const definition = definitionId === null ? undefined : dictionaries.attributes.get(definitionId);
    const item: RawObject = {};
    if (definitionId !== null) {
      item[ATTRIBUTE_KEY.definitionId] = definitionId;
      if (definition?.name != null) item[ATTRIBUTE_KEY.name] = definition.name;
    }
    const value = fieldOf(raw, ATTRIBUTE_FIELD.value);
    if (value !== undefined) item[ATTRIBUTE_KEY.value] = value;
    const values = fieldOf(raw, ATTRIBUTE_FIELD.values);
    if (values !== undefined) item[ATTRIBUTE_KEY.values] = values;
    const labels = attributeValueLabels(raw, definition);
    if (labels.length > 0) item[ATTRIBUTE_KEY.valueLabels] = labels;
    flattened.push(item);
  }
  return flattened;
}

function parentLinkage(raw: RawObject, inv: ResourceInventory, leaf: AspiraLeaf | null, parentLeaf: AspiraLeaf | null) {
  const parent = leaf ?? parentLeaf;
  if (parent === null) {
    if (inv.firstMapId !== null) raw[RAW_KEY.parentMapId] = inv.firstMapId;
    if (inv.resourceLocationId !== null) raw[RAW_KEY.parentResourceLocation] = inv.resourceLocationId;
    return;
  }
  raw[RAW_KEY.parentMapId] = parent.mapId;
  raw[RAW_KEY.parentTransactionLocation] = parent.transactionLocationId;
  if (parent.resourceLocationId !== null) raw[RAW_KEY.parentResourceLocation] = parent.resourceLocationId;
  raw[RAW_KEY.parentLeafName] = parent.name;
  if (parent.parentName !== null) raw[RAW_KEY.parentLeafParentName] = parent.parentName;
  if (leaf === null && inv.firstMapId !== null && inv.firstMapId !== parent.mapId) {
    raw[RAW_KEY.resourceMapId] = inv.firstMapId;
  }
}

export function buildResourceRaw(
  inv: ResourceInventory,
  leaf: AspiraLeaf | null,
  parentLeaf: AspiraLeaf | null,
  dictionaries: AspiraDictionaries,
): RawObject {
  const raw: RawObject = { [RAW_KEY.resourceId]: inv.resourceId };
  parentLinkage(raw, inv, leaf, parentLeaf);
  if (inv.description !== null) raw[RAW_KEY.description] = inv.description;
  if (inv.resourceCategoryId !== null) {
    raw[RAW_KEY.resourceCategoryId] = inv.resourceCategoryId;
    const categoryName = dictionaries.resourceCategories.get(inv.resourceCategoryId);
    if (categoryName !== undefined) raw[RAW_KEY.resourceCategoryName] = categoryName;
  }
  if (inv.maxCapacity !== null) raw[RAW_KEY.maxCapacity] = inv.maxCapacity;
  if (inv.minCapacity !== null) raw[RAW_KEY.minCapacity] = inv.minCapacity;
  if (inv.maxBoatLength !== null) raw[RAW_KEY.maxBoatLength] = inv.maxBoatLength;
  if (inv.allowedEquipment !== null) {
    raw[RAW_KEY.allowedEquipment] = enrichAllowedEquipment(inv.allowedEquipment, dictionaries);
  }
  if (inv.definedAttributes !== null) {
    raw[RAW_KEY.definedAttributes] = flattenAttributes(inv.definedAttributes, dictionaries);
  }
  return raw;
}
