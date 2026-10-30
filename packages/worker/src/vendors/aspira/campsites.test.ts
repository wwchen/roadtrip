import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DataProvider } from '@roadtrip/domain';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { adapterRegistry } from '../../etl/adapter-registry.js';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampsiteUpsertCandidate } from '../../model/campsite-candidate.js';
import { loadPoiRegistry, type EtlEntry } from '../../registry/poi-registry.js';
import type { AspiraCampsitesDto, AspiraCampsitesEtl } from './campsites.js';

const TEMP_DIR_PREFIX = 'aspira-campsites-';
const HTTP_OK = 200;
const RAW_DIR = '/unused';
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const WA_SLUG = 'aspira-wa-campsites';
const BC_SLUG = 'aspira-bc-campsites';
const WA_MAPS = 'aspira-maps-wa';
const WA_INVENTORY = 'aspira-inventory-wa';
const WA_DICTIONARIES = 'aspira-dictionaries-wa';
const BC_MAPS = 'aspira-maps-bc';
const BC_INVENTORY = 'aspira-inventory-bc';
const WIDE_ID = '9007199254740993';
const LEAF_TRANSACTION = '-2147483630';
const LEAF_MAP = '-2147483388';
const LEAF_RESOURCE_LOCATION = '-2147483624';
const OTHER_MAP = '-2147483615';
const CAMPSITE_CATEGORY = '-2147483648';
const CABIN_CATEGORY = '5';
const UNKNOWN_CATEGORY = '6';
const VEHICLE_ATTRIBUTE = '-32715';
const NAMELESS_ATTRIBUTE = '-40001';
const CHOICE_ATTRIBUTE = '-32737';
const EQUIPMENT_CATEGORY = '-32768';
const EQUIPMENT_SUB_CATEGORY = '-32767';

const REGISTRY = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
const CTX = TransformCtx.load(RAW_DIR, REGISTRY);

let dir = '';
let counter = 0;

beforeAll(() => {
  dir = mkdtempSync(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
});

afterAll(() => {
  rmSync(dir, { recursive: true, force: true });
});

function envelopeOf(payloadJson: string): Envelope {
  counter += 1;
  const file = path.join(dir, `capture-${counter}.json`);
  writeFileSync(
    file,
    `{"fetcher":"f","fetcher_version":"1","fetched_at":"2026-06-17T00:00:00Z","request":{"url":"u","method":"GET"},` +
      `"response":{"status":${HTTP_OK}},"payload":${payloadJson}}`,
  );
  return parseEnvelope(file);
}

function entryOf(slug: string): EtlEntry {
  const entry = REGISTRY.campsiteData.flatMap((row) => row.etls).find((etl) => etl.slug === slug);
  if (entry === undefined) throw new Error(`no etl ${slug}`);
  return entry;
}

function etlOf(slug: string): AspiraCampsitesEtl {
  const entry = entryOf(slug);
  const factory = adapterRegistry[entry.adapter];
  if (factory === undefined) throw new Error(`no adapter ${entry.adapter}`);
  return factory(entry).etl as AspiraCampsitesEtl;
}

type Inputs = Record<string, string[]>;

function bundleOf(inputs: Inputs): InputBundle {
  return new InputBundle(new Map(Object.entries(inputs).map(([slug, payloads]) => [slug, payloads.map(envelopeOf)])));
}

function parsed(slug: string, inputs: Inputs): ParseResult<AspiraCampsitesDto>[] {
  return [...etlOf(slug).parse(bundleOf(inputs))];
}

function transformed(slug: string, inputs: Inputs): TransformResult<CampsiteUpsertCandidate>[] {
  const etl = etlOf(slug);
  const results: TransformResult<CampsiteUpsertCandidate>[] = [];
  for (const result of etl.parse(bundleOf(inputs))) {
    if (!result.ok) throw new Error(result.errors.join());
    results.push(...etl.transform(result.dto));
  }
  return results;
}

function recordsOf(slug: string, inputs: Inputs): CampsiteUpsertCandidate[] {
  return transformed(slug, inputs).map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function onlyRecord(slug: string, inputs: Inputs): CampsiteUpsertCandidate {
  const [record, ...rest] = recordsOf(slug, inputs);
  if (record === undefined || rest.length > 0) throw new Error('expected exactly one record');
  return record;
}

function title(text: string): string {
  return `[{"cultureName":"en-US","title":${JSON.stringify(text)}}]`;
}

const LINKED_MAPS =
  `[{"mapId":-2147483346,"localizedValues":${title('Northwest Washington State Parks')},"mapLinks":[` +
  `{"resourceLocationId":${LEAF_RESOURCE_LOCATION},"transactionLocationId":${LEAF_TRANSACTION},` +
  `"localizations":${title('Deception Pass')},"childMapId":${LEAF_MAP}}]}]`;

function leafMaps(mapId: string, name: string, resourceLocationId = LEAF_RESOURCE_LOCATION, parentName?: string): string {
  const parent =
    parentName === undefined
      ? ''
      : `,"parentMap":{"mapId":-5}},{"mapId":-5,"localizedValues":${title(parentName)}`;
  return (
    `[{"mapId":${mapId},"transactionLocationId":${LEAF_TRANSACTION},"resourceLocationId":${resourceLocationId},` +
    `"localizedValues":${title(name)}${parent}}]`
  );
}

function resourceJson(fields: Record<string, string>): string {
  return `{${Object.entries(fields)
    .map(([key, value]) => `${JSON.stringify(key)}:${value}`)
    .join(',')}}`;
}

function inventoryOf(resources: Record<string, string>): string {
  return `{${Object.entries(resources)
    .map(([id, resource]) => `${JSON.stringify(id)}:${resource}`)
    .join(',')}}`;
}

function locale(name: string, description?: string): string {
  const parts = [`"cultureName":"en-US"`, `"name":${JSON.stringify(name)}`];
  if (description !== undefined) parts.push(`"description":${JSON.stringify(description)}`);
  return `[{${parts.join(',')}}]`;
}

const BASIC_RESOURCE = {
  resourceLocationId: LEAF_RESOURCE_LOCATION,
  resourceCategoryId: CAMPSITE_CATEGORY,
  localizedValues: locale('31', '<p>Lakeside &amp; shaded</p>'),
  minCapacity: '2',
  mapIds: `[${OTHER_MAP}]`,
  allowedEquipment: '[]',
  definedAttributes:
    `[{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"attributeVisibility":0,"value":3,"values":[]},` +
    `{"attributeDefinitionId":${NAMELESS_ATTRIBUTE},"attributeVisibility":0,"value":7,"values":[]}]`,
};

const BASIC_INVENTORY = inventoryOf({ '-2147481558': resourceJson(BASIC_RESOURCE) });

function waInputs(inventories: string[], mapsJson = LINKED_MAPS, dictionaryJson: string | null = null): Inputs {
  return {
    [WA_MAPS]: [mapsJson],
    [WA_INVENTORY]: inventories,
    ...(dictionaryJson === null ? {} : { [WA_DICTIONARIES]: [dictionaryJson] }),
  };
}

function waRecord(resource: Record<string, string>, mapsJson = LINKED_MAPS, dictionaryJson: string | null = null) {
  return onlyRecord(WA_SLUG, waInputs([inventoryOf({ '1': resourceJson(resource) })], mapsJson, dictionaryJson));
}

function waResults(inventoryJson: string) {
  return transformed(WA_SLUG, waInputs([inventoryJson]));
}

const DICTIONARY = JSON.stringify({
  equipment: [
    {
      equipmentCategoryId: Number(EQUIPMENT_CATEGORY),
      localizedValues: [{ cultureName: 'en-US', name: 'Vehicle' }],
      subEquipmentCategories: [
        {
          subEquipmentCategoryId: Number(EQUIPMENT_SUB_CATEGORY),
          localizedValues: [{ cultureName: 'en-US', displayName: 'Trailer' }],
        },
      ],
    },
  ],
  resource_categories: [
    { resourceCategoryId: Number(CAMPSITE_CATEGORY), localizedValues: [{ cultureName: 'en-US', name: 'Campsite' }] },
    { resourceCategoryId: Number(CABIN_CATEGORY), localizedValues: [{ cultureName: 'en-US', title: 'Rustic Cabin' }] },
    { resourceCategoryId: Number(UNKNOWN_CATEGORY), localizedValues: [{ cultureName: 'en-US', name: 'Mystery' }] },
  ],
  attributes: {
    a: {
      attributeDefinitionId: Number(VEHICLE_ATTRIBUTE),
      localizedValues: [{ cultureName: 'en-US', name: 'Max Vehicle Length' }],
      values: [{ enumValue: 3, localizedValues: [{ cultureName: 'en-US', name: '32 ft' }] }],
    },
    b: {
      attributeDefinitionId: Number(CHOICE_ATTRIBUTE),
      localizedValues: [{ cultureName: 'en-US', name: 'Surface' }],
      values: [
        { enumValue: 0, localizedValues: [{ cultureName: 'en-US', name: 'Gravel' }] },
        { enumValue: 1, localizedValues: [{ cultureName: 'en-US', name: 'Grass' }] },
      ],
    },
  },
});

describe('the Aspira campsites adapter, parse', () => {
  test('a run with every input yields one DTO carrying the inventory, the maps and the dictionaries', () => {
    const [result, ...rest] = parsed(WA_SLUG, waInputs([BASIC_INVENTORY, BASIC_INVENTORY], LINKED_MAPS, DICTIONARY));
    expect(rest).toEqual([]);
    expect(result?.ok).toBe(true);
    if (result === undefined || !result.ok) throw new Error('expected a DTO');
    expect(result.dto.inventory).toHaveLength(2);
    expect(result.dto.maps).toHaveLength(1);
    expect(result.dto.dictionaries.resourceCategories.size).toBe(3);
    expect(result.dto.dictionaries.attributes.size).toBe(2);
    expect(result.dto.dictionaries.equipment.size).toBe(1);
  });

  test('a missing maps input throws and names every input it was given', () => {
    const inputs = waInputs([BASIC_INVENTORY]);
    delete inputs[WA_MAPS];
    expect(() => parsed(WA_SLUG, inputs)).toThrow(`${WA_SLUG}: missing required input '${WA_MAPS}' in [${WA_INVENTORY}]`);
  });

  test('a missing inventory input throws', () => {
    const inputs = waInputs([BASIC_INVENTORY]);
    delete inputs[WA_INVENTORY];
    expect(() => parsed(WA_SLUG, inputs)).toThrow(`${WA_SLUG}: missing required input '${WA_INVENTORY}' in [${WA_MAPS}]`);
  });

  test('an inventory input with no envelopes throws and says how to capture it', () => {
    expect(() => parsed(WA_SLUG, waInputs([]))).toThrow(
      `${WA_SLUG}: no envelopes in '${WA_INVENTORY}' (run fetch_aspira_inventory.py first)`,
    );
  });

  test.each([
    ['an object', '{}'],
    ['a string', '"maps"'],
    ['null', 'null'],
  ])('a maps payload that is %s throws', (_label, mapsJson) => {
    expect(() => parsed(WA_SLUG, waInputs([BASIC_INVENTORY], mapsJson))).toThrow('Element is not a JsonArray');
  });

  test('a multi-part maps input throws, since the adapter asks for the one envelope', () => {
    const inputs = waInputs([BASIC_INVENTORY]);
    inputs[WA_MAPS] = [LINKED_MAPS, LINKED_MAPS];
    expect(() => parsed(WA_SLUG, inputs)).toThrow(`input '${WA_MAPS}' is multi-part (2 pages)`);
  });

  test('an empty maps payload is a parse-level error and not a throw', () => {
    expect(parsed(WA_SLUG, waInputs([BASIC_INVENTORY], '[]'))).toEqual([
      { ok: false, errors: [`${WA_SLUG}: empty /api/maps payload`] },
    ]);
  });

  test.each([
    ['an array', '[]'],
    ['a string', '"x"'],
    ['null', 'null'],
  ])('a dictionaries payload that is %s is treated as no dictionaries', (_label, dictionaryJson) => {
    const [result] = parsed(WA_SLUG, waInputs([BASIC_INVENTORY], LINKED_MAPS, dictionaryJson));
    if (result === undefined || !result.ok) throw new Error('expected a DTO');
    expect(result.dto.dictionaries.resourceCategories.size).toBe(0);
  });

  test('a multi-part dictionaries input throws', () => {
    const inputs = waInputs([BASIC_INVENTORY], LINKED_MAPS, DICTIONARY);
    inputs[WA_DICTIONARIES] = [DICTIONARY, DICTIONARY];
    expect(() => parsed(WA_SLUG, inputs)).toThrow('is multi-part');
  });
});

describe('the Aspira campsites adapter, the rows it refuses', () => {
  test('an inventory payload that is not an object is reported against the envelope', () => {
    expect(waResults('[]')).toEqual([{ ok: false, sourceId: 'u', errors: ['expected JSON object payload'] }]);
  });

  test.each([
    ['an empty resource id', '""', 'u', 'empty resource id'],
    ['a non-numeric resource id', '"abc"', 'abc', 'resource id is not numeric'],
    ['a fractional resource id', '"1.5"', '1.5', 'resource id is not numeric'],
    ['an exponent resource id', '"1e2"', '1e2', 'resource id is not numeric'],
    ['a padded resource id', '" 7"', ' 7', 'resource id is not numeric'],
    ['a resource id beyond a Long', '"9223372036854775808"', '9223372036854775808', 'resource id is not numeric'],
  ])('%s is reported and skipped', (_label, keyJson, sourceId, error) => {
    const results = waResults(`{${keyJson}:{}}`);
    expect(results).toEqual([{ ok: false, sourceId, errors: [error] }]);
  });

  test.each([['5'], ['null'], ['"text"'], ['[]'], ['true']])('a resource that is %s is reported and skipped', (valueJson) => {
    expect(waResults(`{"7":${valueJson}}`)).toEqual([
      { ok: false, sourceId: '7', errors: ['expected resource JSON object'] },
    ]);
  });

  test('refused rows do not stop the rows around them', () => {
    const results = waResults(`{"-3":{},"x":{},"-1":{},"-2":5}`);
    expect(results.map((result) => result.ok)).toEqual([true, false, true, false]);
  });
});

describe('the Aspira campsites adapter, resource ids', () => {
  test.each([
    ['+7', 'wa:7'],
    ['007', 'wa:7'],
    ['-0', 'wa:0'],
    ['-2147481558', 'wa:-2147481558'],
    ['9223372036854775807', 'wa:9223372036854775807'],
    ['٣', 'wa:3'],
  ])('the key %s is read as String.toLongOrNull reads it, giving %s', (key, expected) => {
    const record = onlyRecord(WA_SLUG, waInputs([inventoryOf({ [key]: resourceJson({}) })]));
    expect(record.dataProviderRef.serialize()).toBe(expected);
  });

  test('two keys that spell one Long are both emitted, and the repo is what collapses them', () => {
    const records = recordsOf(WA_SLUG, waInputs([inventoryOf({ '007': resourceJson({}), '+7': resourceJson({}) })]));
    expect(records.map((record) => record.dataProviderRef.serialize())).toEqual(['wa:7', 'wa:7']);
  });

  test('the name falls back to the key, but an empty name is kept', () => {
    expect(waRecord({}).name).toBe('1');
    expect(waRecord({ localizedValues: locale('') }).name).toBe('');
    expect(waRecord({ localizedValues: '[]' }).name).toBe('1');
    expect(waRecord({ localizedValues: '[null,{"name":"second"}]' }).name).toBe('1');
    expect(waRecord({ localizedValues: '[{"name":null}]' }).name).toBe('1');
  });

  test('the key, not the resource body, is the campsite identity', () => {
    const record = onlyRecord(
      WA_SLUG,
      waInputs([inventoryOf({ '12': resourceJson({ resourceId: '99', resourceLocationId: '5' }) })]),
    );
    expect(record.dataProviderRef.serialize()).toBe('wa:12');
    expect(JSON.parse(record.sourcePayload as string)).toMatchObject({ resource_id: '12' });
  });
});

describe('the Aspira campsites adapter, finding the parent', () => {
  test('a resource whose map id matches a leaf is parented to that leaf and labelled by it', () => {
    const record = waRecord({ resourceLocationId: '1', mapIds: `[${LEAF_MAP}]` });
    expect(record.parentDataProviderRef?.serialize()).toBe(`${LEAF_TRANSACTION}:${LEAF_MAP}`);
    expect(record.parentDataProviderRef?.provider).toBe(DataProvider.ASPIRA);
    expect(record.loopName).toBe('Deception Pass');
    expect(record.bookingProviderRef).toBe(`wa:${LEAF_TRANSACTION}:${LEAF_MAP}:${LEAF_RESOURCE_LOCATION}`);
    const raw = JSON.parse(record.sourcePayload as string);
    expect(raw).toMatchObject({
      _parent_aspira_map_id: Number(LEAF_MAP),
      _parent_aspira_txn_loc: Number(LEAF_TRANSACTION),
      _parent_aspira_resource_loc: Number(LEAF_RESOURCE_LOCATION),
      _parent_leaf_name: 'Deception Pass',
    });
    expect(raw).not.toHaveProperty('_aspira_resource_map_id');
  });

  test('a resource whose map id matches nothing falls back to the leaf for its resource location', () => {
    const record = waRecord(BASIC_RESOURCE);
    expect(record.parentDataProviderRef?.serialize()).toBe(`${LEAF_TRANSACTION}:${LEAF_MAP}`);
    expect(record.loopName).toBe('Deception Pass');
    expect(record.bookingProviderRef).toBe(`wa:${LEAF_TRANSACTION}:${OTHER_MAP}:${LEAF_RESOURCE_LOCATION}`);
    expect(JSON.parse(record.sourcePayload as string)).toMatchObject({
      _parent_aspira_map_id: Number(LEAF_MAP),
      _aspira_resource_map_id: Number(OTHER_MAP),
    });
  });

  test('a resource with a map id the leaf already carries does not record a second map id', () => {
    const record = waRecord({ resourceLocationId: LEAF_RESOURCE_LOCATION, mapIds: `[${LEAF_MAP}, ${OTHER_MAP}]` });
    expect(JSON.parse(record.sourcePayload as string)).not.toHaveProperty('_aspira_resource_map_id');
  });

  test('only the first map id is consulted', () => {
    const record = waRecord({ resourceLocationId: '5', mapIds: `[${OTHER_MAP}, ${LEAF_MAP}]` });
    expect(record.parentDataProviderRef).toBeNull();
    expect(record.loopName).toBeNull();
  });

  test('a resource that matches no leaf at all has no parent, no loop and no booking reference', () => {
    const record = waRecord({ resourceLocationId: '5', mapIds: `[${OTHER_MAP}]` });
    expect(record.parentDataProviderRef).toBeNull();
    expect(record.loopName).toBeNull();
    expect(record.bookingProviderRef).toBeNull();
    expect(JSON.parse(record.sourcePayload as string)).toMatchObject({
      _parent_aspira_map_id: Number(OTHER_MAP),
      _parent_aspira_resource_loc: 5,
    });
  });

  test('a resource with neither map ids nor a resource location carries only its own id', () => {
    const record = waRecord({});
    expect(record.parentDataProviderRef).toBeNull();
    expect(record.bookingProviderRef).toBeNull();
    expect(JSON.parse(record.sourcePayload as string)).toEqual({ resource_id: '1' });
  });

  test('a leaf without a resource location still parents a matching resource, but cannot complete the booking reference', () => {
    const leaf =
      `[{"mapId":${LEAF_MAP},"transactionLocationId":${LEAF_TRANSACTION},"localizedValues":${title('Bare')}}]`;
    const withLocation = waRecord({ resourceLocationId: '9', mapIds: `[${LEAF_MAP}]` }, leaf);
    expect(withLocation.bookingProviderRef).toBe(`wa:${LEAF_TRANSACTION}:${LEAF_MAP}:9`);
    const without = waRecord({ mapIds: `[${LEAF_MAP}]` }, leaf);
    expect(without.parentDataProviderRef?.serialize()).toBe(`${LEAF_TRANSACTION}:${LEAF_MAP}`);
    expect(without.bookingProviderRef).toBeNull();
    expect(JSON.parse(without.sourcePayload as string)).not.toHaveProperty('_parent_aspira_resource_loc');
  });

  test('the parent leaf name and its own parent are recorded', () => {
    const record = waRecord({ mapIds: `[${LEAF_MAP}]` }, leafMaps(LEAF_MAP, 'Loop A', LEAF_RESOURCE_LOCATION, 'Park B'));
    expect(JSON.parse(record.sourcePayload as string)).toMatchObject({
      _parent_leaf_name: 'Loop A',
      _parent_leaf_parent_name: 'Park B',
    });
    expect(record.loopName).toBe('Loop A');
  });

  test('ids beyond 2^53 survive the parent reference, the booking reference and the payload', () => {
    const record = waRecord(
      { resourceLocationId: WIDE_ID, mapIds: `[${WIDE_ID}]` },
      leafMaps(WIDE_ID, 'Wide', WIDE_ID),
    );
    expect(record.parentDataProviderRef?.serialize()).toBe(`${LEAF_TRANSACTION}:${WIDE_ID}`);
    expect(record.bookingProviderRef).toBe(`wa:${LEAF_TRANSACTION}:${WIDE_ID}:${WIDE_ID}`);
    expect(record.sourcePayload).toContain(`"_parent_aspira_map_id":${WIDE_ID}`);
    expect(record.sourcePayload).toContain(`"_parent_aspira_resource_loc":${WIDE_ID}`);
  });

  test('the Parks Canada and Washington rows parent to Aspira campgrounds and the BC row to the Strapi ones', () => {
    const wa = etlOf(WA_SLUG);
    const bc = etlOf(BC_SLUG);
    expect(wa.parentDataProvider).toBe(DataProvider.ASPIRA);
    expect(bc.parentDataProvider).toBe(DataProvider.STRAPI);
    const record = onlyRecord(BC_SLUG, {
      [BC_MAPS]: [LINKED_MAPS],
      [BC_INVENTORY]: [inventoryOf({ '1': resourceJson(BASIC_RESOURCE) })],
    });
    expect(record.parentDataProviderRef?.provider).toBe(DataProvider.STRAPI);
    expect(record.parentDataProviderRef?.serialize()).toBe(`${LEAF_TRANSACTION}:${LEAF_MAP}`);
    expect(record.dataProviderRef.provider).toBe(DataProvider.ASPIRA);
    expect(record.dataProviderRef.serialize()).toBe('bc:1');
    expect(record.bookingProviderRef).toBe(`bc:${LEAF_TRANSACTION}:${OTHER_MAP}:${LEAF_RESOURCE_LOCATION}`);
  });
});

describe('the Aspira campsites adapter, the numeric reads', () => {
  test.each([
    ['null', 'null'],
    ['a string that is not a number', '"abc"'],
    ['a fraction', '1.5'],
    ['a spelling with a fractional part', '25.0'],
    ['an integer beyond a Long', '9223372036854775808'],
    ['an object', '{}'],
    ['an array', '[]'],
  ])('a resource location id that is %s throws, as JsonPrimitive.long does', (_label, valueJson) => {
    expect(() => waResults(`{"1":{"resourceLocationId":${valueJson}}}`)).toThrow();
  });

  test.each([
    ['a number', '100', 100n],
    ['a string of digits', '"100"', 100n],
    ['an exponent spelling the lexer accepts', '1e2', 100n],
    ['a spelling past 2^53', WIDE_ID, BigInt(WIDE_ID)],
  ])('a resource location id that is %s is read', (_label, valueJson, expected) => {
    const record = waRecord({ resourceLocationId: valueJson, mapIds: `[${LEAF_MAP}]` }, leafMaps(LEAF_MAP, 'L', '1'));
    expect(record.bookingProviderRef).toBe(`wa:${LEAF_TRANSACTION}:${LEAF_MAP}:1`);
    expect(JSON.parse(String(waRecord({ resourceLocationId: valueJson }).sourcePayload))).toMatchObject({
      _parent_aspira_resource_loc: Number(expected),
    });
  });

  test.each([['null'], ['"abc"'], ['1.5'], ['{}'], ['[]']])('a map id of %s throws', (valueJson) => {
    expect(() => waResults(`{"1":{"mapIds":[${LEAF_MAP},${valueJson}]}}`)).toThrow();
  });

  test('a map id list that is not an array is read as no map ids', () => {
    expect(waRecord({ resourceLocationId: '5', mapIds: `{"a":1}` }).parentDataProviderRef).toBeNull();
    expect(waRecord({ resourceLocationId: '5', mapIds: 'null' }).parentDataProviderRef).toBeNull();
  });

  test.each([
    ['an integer', '4', 4],
    ['a string of digits', '"6"', 6],
    ['an integer respelled with a fractional part', '4.0', null],
    ['an integer respelled as an exponent', '4e0', 4],
    ['a fraction', '4.5', null],
    ['a number beyond an Int', '3000000000', null],
    ['null', 'null', null],
    ['a boolean', 'true', null],
    ['negative zero', '-0', 0],
  ])('maxCapacity %s reads as %s', (_label, valueJson, expected) => {
    expect(waRecord({ maxCapacity: valueJson }).maxPeople).toBe(expected);
    expect(waRecord({ minCapacity: valueJson }).minPeople).toBe(expected);
  });

  test.each([['{}'], ['[]']])('a capacity that is %s throws, since it is not a primitive', (valueJson) => {
    expect(() => waRecord({ maxCapacity: valueJson })).toThrow();
    expect(() => waRecord({ resourceCategoryId: valueJson })).toThrow();
    expect(() => waRecord({ maxBoatLength: valueJson })).toThrow();
  });

  test('a name that is an object throws, and a numeric name is read as its text', () => {
    expect(() => waRecord({ localizedValues: '[{"name":{}}]' })).toThrow();
    expect(waRecord({ localizedValues: '[{"name":25.0}]' }).name).toBe('25.0');
    expect(waRecord({ localizedValues: '[{"name":true}]' }).name).toBe('true');
  });

  test('the capacities, the boat length and the category id reach the payload as numbers', () => {
    const raw = JSON.parse(
      String(waRecord({ maxCapacity: '8', minCapacity: '"2"', maxBoatLength: '30', resourceCategoryId: '4' }).sourcePayload),
    );
    expect(raw).toMatchObject({ max_capacity: 8, min_capacity: 2, max_boat_length: 30, resource_category_id: 4 });
  });
});

describe('the Aspira campsites adapter, labels from the dictionaries', () => {
  test('the resource category gives the kind, the listed kind and a name in the payload', () => {
    const cabin = waRecord({ resourceCategoryId: CABIN_CATEGORY }, LINKED_MAPS, DICTIONARY);
    expect(cabin.kind).toBe('cabin');
    expect(cabin.kindListed).toBe('Rustic Cabin');
    expect(JSON.parse(String(cabin.sourcePayload))).toMatchObject({
      resource_category_id: Number(CABIN_CATEGORY),
      resource_category_name: 'Rustic Cabin',
    });
    const standard = waRecord({ resourceCategoryId: CAMPSITE_CATEGORY }, LINKED_MAPS, DICTIONARY);
    expect(standard.kind).toBe('standard');
    expect(standard.kindListed).toBe('Campsite');
    const unknown = waRecord({ resourceCategoryId: UNKNOWN_CATEGORY }, LINKED_MAPS, DICTIONARY);
    expect(unknown.kind).toBe('other');
    expect(unknown.kindListed).toBe('Mystery');
  });

  test('a category the dictionaries do not list has no listed kind and no name in the payload', () => {
    const record = waRecord({ resourceCategoryId: '777' }, LINKED_MAPS, DICTIONARY);
    expect(record.kind).toBe('other');
    expect(record.kindListed).toBeNull();
    const raw = JSON.parse(String(record.sourcePayload));
    expect(raw).toMatchObject({ resource_category_id: 777 });
    expect(raw).not.toHaveProperty('resource_category_name');
  });

  test('with no dictionaries a category is still recorded by id and the kind is other', () => {
    const record = waRecord({ resourceCategoryId: CABIN_CATEGORY });
    expect(record.kind).toBe('other');
    expect(record.kindListed).toBeNull();
  });

  test.each([
    ['resource_categories', 'resource_categories'],
    ['resourceCategories', 'resourceCategories'],
    ['resourcecategory', 'resourcecategory'],
  ])('the categories are found under the key %s', (_label, key) => {
    const dictionary = JSON.stringify({
      [key]: [{ resourceCategoryId: 5, localizedValues: [{ cultureName: 'en-US', name: 'Yurt' }] }],
    });
    expect(waRecord({ resourceCategoryId: '5' }, LINKED_MAPS, dictionary).kindListed).toBe('Yurt');
  });

  test('a first key that is present but not an array does not hide a later one', () => {
    const dictionary = JSON.stringify({
      resource_categories: {},
      resourceCategories: [{ resourceCategoryId: 5, localizedValues: [{ name: 'Yurt' }] }],
    });
    expect(waRecord({ resourceCategoryId: '5' }, LINKED_MAPS, dictionary).kindListed).toBe('Yurt');
  });

  test('a category with no usable label is left out of the dictionary', () => {
    const dictionary = JSON.stringify({
      resource_categories: [
        { resourceCategoryId: 5 },
        { resourceCategoryId: 6, localizedValues: [] },
        { resourceCategoryId: 7, localizedValues: [null] },
        { resourceCategoryId: 8, localizedValues: [{ name: null, displayName: null }] },
      ],
    });
    for (const id of ['5', '6', '7', '8']) {
      expect(waRecord({ resourceCategoryId: id }, LINKED_MAPS, dictionary).kindListed).toBeNull();
    }
  });

  test('a label is the first of name, displayName and title that is not null, and only the first locale counts', () => {
    const dictionary = JSON.stringify({
      resource_categories: [
        { resourceCategoryId: 1, localizedValues: [{ name: null, displayName: 'D', title: 'T' }] },
        { resourceCategoryId: 2, localizedValues: [{ title: 'T' }] },
        { resourceCategoryId: 3, localizedValues: [{ name: '', title: 'T' }] },
        { resourceCategoryId: 4, localizedValues: [{ name: 7 }] },
        { resourceCategoryId: 5, localizedValues: [{ cultureName: 'fr' }, { name: 'second' }] },
      ],
    });
    const listed = ['1', '2', '3', '4', '5'].map(
      (id) => waRecord({ resourceCategoryId: id }, LINKED_MAPS, dictionary).kindListed,
    );
    expect(listed).toEqual(['D', 'T', '', '7', null]);
  });

  test('a label that is an object makes the whole dictionary throw', () => {
    const dictionary = JSON.stringify({
      resource_categories: [{ resourceCategoryId: 1, localizedValues: [{ name: {} }] }],
    });
    expect(() => waRecord({}, LINKED_MAPS, dictionary)).toThrow();
  });

  test('the equipment names are the sub-category labels, and an unknown sub-category is dropped', () => {
    const equipment = (ids: string) => `[${ids}]`;
    const known = `{"equipmentCategoryId":${EQUIPMENT_CATEGORY},"subEquipmentCategoryId":${EQUIPMENT_SUB_CATEGORY}}`;
    const unknown = `{"equipmentCategoryId":${EQUIPMENT_CATEGORY},"subEquipmentCategoryId":1}`;
    const partial = `{"equipmentCategoryId":${EQUIPMENT_CATEGORY}}`;
    const record = waRecord(
      { allowedEquipment: equipment(`${known},${unknown},${partial},5,null,{}`) },
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(record.equipment).toEqual(['Trailer']);
    const raw = JSON.parse(String(record.sourcePayload));
    expect(raw.allowed_equipment).toEqual([
      {
        equipment_category_id: Number(EQUIPMENT_CATEGORY),
        sub_equipment_category_id: Number(EQUIPMENT_SUB_CATEGORY),
        equipment_category_name: 'Vehicle',
        name: 'Trailer',
      },
      { equipment_category_id: Number(EQUIPMENT_CATEGORY), sub_equipment_category_id: 1 },
      { equipment_category_id: Number(EQUIPMENT_CATEGORY) },
      {},
    ]);
  });

  test('equipment without a dictionary yields no names but still records the ids', () => {
    const record = waRecord({
      allowedEquipment: `[{"equipmentCategoryId":1,"subEquipmentCategoryId":2}]`,
    });
    expect(record.equipment).toEqual([]);
    expect(JSON.parse(String(record.sourcePayload)).allowed_equipment).toEqual([
      { equipment_category_id: 1, sub_equipment_category_id: 2 },
    ]);
  });

  test('allowedEquipment that is not an array is absent from the payload, and an empty one is present', () => {
    expect(JSON.parse(String(waRecord({ allowedEquipment: '{}' }).sourcePayload))).not.toHaveProperty('allowed_equipment');
    expect(JSON.parse(String(waRecord({ allowedEquipment: '[]' }).sourcePayload))).toMatchObject({ allowed_equipment: [] });
  });

  test('an equipment entry whose category id is an object throws', () => {
    expect(() => waRecord({ allowedEquipment: '[{"equipmentCategoryId":{}}]' })).toThrow();
  });

  test('an equipment category later in the dictionary replaces an earlier one with the same ids', () => {
    const dictionary = JSON.stringify({
      equipment: [
        { equipmentCategoryId: 1, localizedValues: [{ name: 'First' }], subEquipmentCategories: [
          { subEquipmentCategoryId: 2, localizedValues: [{ name: 'Old' }] },
        ] },
        { equipmentCategoryId: 1, localizedValues: [{ name: 'Second' }], subEquipmentCategories: [
          { subEquipmentCategoryId: 2, localizedValues: [{ name: 'New' }] },
          { localizedValues: [{ name: 'No id' }] },
        ] },
        { equipmentCategoryId: 3 },
      ],
    });
    const record = waRecord({ allowedEquipment: `[{"equipmentCategoryId":1,"subEquipmentCategoryId":2}]` }, LINKED_MAPS, dictionary);
    expect(record.equipment).toEqual(['New']);
    expect(JSON.parse(String(record.sourcePayload)).allowed_equipment[0].equipment_category_name).toBe('Second');
  });

  test('a named attribute takes the label of its value, or of the first of its values, or the raw scalar', () => {
    const attributes = (...entries: string[]) => ({ definedAttributes: `[${entries.join(',')}]` });
    const labelled = waRecord(attributes(`{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"value":3}`), LINKED_MAPS, DICTIONARY);
    expect(labelled.attributes).toEqual([{ name: 'Max Vehicle Length', value: '32 ft' }]);
    const fromValues = waRecord(
      attributes(`{"attributeDefinitionId":${CHOICE_ATTRIBUTE},"value":null,"values":[1,0]}`),
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(fromValues.attributes).toEqual([{ name: 'Surface', value: 'Grass' }]);
    const skipsUnlabelled = waRecord(
      attributes(`{"attributeDefinitionId":${CHOICE_ATTRIBUTE},"values":[9,0]}`),
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(skipsUnlabelled.attributes).toEqual([{ name: 'Surface', value: 'Gravel' }]);
    const rawScalar = waRecord(
      attributes(`{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"value":4}`),
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(rawScalar.attributes).toEqual([{ name: 'Max Vehicle Length', value: '4' }]);
    const rawText = waRecord(
      attributes(`{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"value":"wide"}`),
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(rawText.attributes).toEqual([{ name: 'Max Vehicle Length', value: 'wide' }]);
    const noValue = waRecord(attributes(`{"attributeDefinitionId":${VEHICLE_ATTRIBUTE}}`), LINKED_MAPS, DICTIONARY);
    expect(noValue.attributes).toEqual([{ name: 'Max Vehicle Length' }]);
  });

  test('a respelled scalar reads as the label of its integer only when the lexer accepts the spelling', () => {
    const attribute = (valueJson: string) =>
      waRecord({ definedAttributes: `[{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"value":${valueJson}}]` }, LINKED_MAPS, DICTIONARY)
        .attributes;
    expect(attribute('3')).toEqual([{ name: 'Max Vehicle Length', value: '32 ft' }]);
    expect(attribute('"3"')).toEqual([{ name: 'Max Vehicle Length', value: '32 ft' }]);
    expect(attribute('3e0')).toEqual([{ name: 'Max Vehicle Length', value: '32 ft' }]);
    expect(attribute('3.0')).toEqual([{ name: 'Max Vehicle Length', value: '3.0' }]);
    expect(attribute('1e20')).toEqual([{ name: 'Max Vehicle Length', value: '1e20' }]);
    expect(attribute('true')).toEqual([{ name: 'Max Vehicle Length', value: 'true' }]);
  });

  test('an attribute with no definition, no name, a non-object entry or an unknown id is dropped', () => {
    const record = waRecord(
      {
        definedAttributes:
          `[5,null,{},{"attributeDefinitionId":null},{"attributeDefinitionId":999},{"attributeDefinitionId":${NAMELESS_ATTRIBUTE},"value":7}]`,
      },
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(record.attributes).toEqual([]);
  });

  test('an attribute whose scalar is an object throws, and so does a value that is', () => {
    expect(() =>
      waRecord({ definedAttributes: `[{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"value":{}}]` }, LINKED_MAPS, DICTIONARY),
    ).toThrow();
    expect(() =>
      waRecord({ definedAttributes: `[{"attributeDefinitionId":${CHOICE_ATTRIBUTE},"values":[{}]}]` }, LINKED_MAPS, DICTIONARY),
    ).toThrow();
  });

  test('an attribute dictionary is read from an array as well as from an object, under any of its three keys', () => {
    const entry = {
      attributeDefinitionId: 1,
      localizedValues: [{ name: 'Shade' }],
      values: [{ enumValue: 4, localizedValues: [{ name: 'Full' }] }],
    };
    for (const shape of [
      { attributes: [entry] },
      { attribute_filterable: { x: entry } },
      { attributeFilterable: [entry] },
    ]) {
      const record = waRecord(
        { definedAttributes: '[{"attributeDefinitionId":1,"value":4}]' },
        LINKED_MAPS,
        JSON.stringify(shape),
      );
      expect(record.attributes).toEqual([{ name: 'Shade', value: 'Full' }]);
    }
  });

  test('an attribute dictionary that is null or a scalar yields no definitions, and the first key present wins', () => {
    const entry = { attributeDefinitionId: 1, localizedValues: [{ name: 'Shade' }] };
    const definition = (shape: unknown) =>
      waRecord({ definedAttributes: '[{"attributeDefinitionId":1,"value":4}]' }, LINKED_MAPS, JSON.stringify(shape))
        .attributes;
    expect(definition({ attributes: null, attribute_filterable: [entry] })).toEqual([]);
    expect(definition({ attributes: 5, attribute_filterable: [entry] })).toEqual([]);
    expect(definition({ attributes: [entry], attribute_filterable: [] })).toEqual([{ name: 'Shade', value: '4' }]);
  });

  test('the payload flattens each attribute, keeps its raw value and values, and adds labels when there are any', () => {
    const record = waRecord(
      {
        definedAttributes:
          `[{"attributeDefinitionId":${VEHICLE_ATTRIBUTE},"attributeId":5,"attributeVisibility":0,"value":3,"values":[]},` +
          `{"attributeDefinitionId":${CHOICE_ATTRIBUTE},"value":null,"values":[0,1,9]},` +
          `{"attributeDefinitionId":999,"value":"x"},{"value":2},{},5]`,
      },
      LINKED_MAPS,
      DICTIONARY,
    );
    expect(JSON.parse(String(record.sourcePayload)).defined_attributes).toEqual([
      { definition_id: Number(VEHICLE_ATTRIBUTE), name: 'Max Vehicle Length', value: 3, values: [], value_labels: ['32 ft'] },
      { definition_id: Number(CHOICE_ATTRIBUTE), name: 'Surface', value: null, values: [0, 1, 9], value_labels: ['Gravel', 'Grass'] },
      { definition_id: 999, value: 'x' },
      { value: 2 },
      {},
    ]);
  });

  test('definedAttributes that is not an array is absent from the payload', () => {
    expect(JSON.parse(String(waRecord({ definedAttributes: 'null' }).sourcePayload))).not.toHaveProperty('defined_attributes');
  });
});

describe('the Aspira campsites adapter, the payload is the vendor JSON', () => {
  test('a value and values keep the spelling the vendor wrote, including the ones a double would change', () => {
    const record = waRecord({
      definedAttributes:
        `[{"attributeDefinitionId":1,"value":1.0,"values":[2.50]},` +
        `{"attributeDefinitionId":2,"value":1.10},` +
        `{"attributeDefinitionId":3,"value":1e20},` +
        `{"attributeDefinitionId":4,"value":7.02e-05},` +
        `{"attributeDefinitionId":5,"value":-0},` +
        `{"attributeDefinitionId":6,"value":${WIDE_ID},"values":[${WIDE_ID}]}]`,
    });
    const text = String(record.sourcePayload);
    expect(text).toContain('"value":1.0,"values":[2.50]');
    expect(text).toContain('"value":1.10');
    expect(text).toContain('"value":1e20');
    expect(text).toContain('"value":7.02e-05');
    expect(text).toContain('"value":-0');
    expect(text).toContain(`"value":${WIDE_ID},"values":[${WIDE_ID}]`);
  });

  test.each([
    '0.0',
    '-0.0',
    '1.0',
    '1.10',
    '2.50',
    '100.00',
    '1e2',
    '1E5',
    '1e+2',
    '1e20',
    '7.02e-05',
    '5e-324',
    '1e400',
    '-1e400',
    '9007199254740992',
    '9007199254740993',
    '-9007199254740993',
    '123456789012345678901234567890',
    '0.1000000000000000055511151231257827',
    '1.7976931348623157e308',
  ])('the vendor spelling %s survives as a value, a values element, and inside a nested object and array', (literal) => {
    const record = waRecord({
      definedAttributes:
        `[{"attributeDefinitionId":1,"value":${literal},"values":[${literal}]},` +
        `{"attributeDefinitionId":2,"value":{"a":${literal}},"values":[[${literal}],{"b":[${literal}]}]}]`,
    });
    const text = String(record.sourcePayload);
    expect(text).toContain(`"value":${literal},"values":[${literal}]`);
    expect(text).toContain(`"value":{"a":${literal}},"values":[[${literal}],{"b":[${literal}]}]`);
  });

  test('a nested object or array kept as a value is copied whole', () => {
    const record = waRecord({
      definedAttributes: `[{"attributeDefinitionId":1,"value":{"a":[1.0,{"b":null}]},"values":[{"c":1.50}]}]`,
    });
    expect(String(record.sourcePayload)).toContain('"value":{"a":[1.0,{"b":null}]},"values":[{"c":1.50}]');
  });

  test('the description is stored as the vendor wrote it, while the typed column strips the tags', () => {
    const record = waRecord({ localizedValues: locale('1', '<p>Lakeside &amp; <b>shaded</b></p>\r\n') });
    expect(record.description).toBe('Lakeside &amp; shaded');
    expect(JSON.parse(String(record.sourcePayload)).description).toBe('<p>Lakeside &amp; <b>shaded</b></p>\r\n');
  });

  test('a description that strips to nothing is no description, and the raw one is still stored', () => {
    for (const description of ['', '   ', '<br/>', '<p> </p>']) {
      const record = waRecord({ localizedValues: locale('1', description) });
      expect(record.description).toBeNull();
      expect(JSON.parse(String(record.sourcePayload)).description).toBe(description);
    }
  });

  test('no description leaves the key out of the payload', () => {
    expect(JSON.parse(String(waRecord({ localizedValues: locale('1') }).sourcePayload))).not.toHaveProperty('description');
  });

  test('a resource carries no price and no schedule, so the vendor columns stay empty', () => {
    const record = waRecord({ ...BASIC_RESOURCE, feeScheduleId: '-32557', dateScheduleId: '3', order: '10' });
    expect(record.price).toBeUndefined();
    expect(record.schedule).toBeUndefined();
    expect(JSON.parse(String(record.sourcePayload))).not.toHaveProperty('feeScheduleId');
  });
});

describe('the Aspira campsites adapter, photos', () => {
  const entry = (url: string) => `{"photoUrlResult":{"url":${url}},"aspectType":0}`;

  test('distinct urls come out in source order, trimmed, with entries lacking a usable url skipped', () => {
    const photos = [
      entry('"https://x/a.jpg"'),
      'null',
      '{"photoUrlResult":"nope"}',
      entry('{}'),
      entry('[]'),
      '{"aspectType":0}',
      entry('""'),
      entry('"   "'),
      entry('null'),
      entry('"https://x/b.jpg"'),
      entry('"https://x/a.jpg"'),
      entry('"  https://x/b.jpg  "'),
      entry('7'),
    ];
    expect(waRecord({ photos: `[${photos.join(',')}]` }).photos).toEqual([
      { url: 'https://x/a.jpg' },
      { url: 'https://x/b.jpg' },
      { url: '7' },
    ]);
  });

  test('a resource without photos, or with photos that are not an array, has none', () => {
    expect(waRecord({}).photos).toEqual([]);
    expect(waRecord({ photos: '{}' }).photos).toEqual([]);
    expect(waRecord({ photos: 'null' }).photos).toEqual([]);
  });
});

describe('the Aspira campsites adapter, across envelopes', () => {
  test('each envelope is read in turn and every park contributes its own rows', () => {
    const first = inventoryOf({ '-1': resourceJson({}), '-2': resourceJson({}) });
    const second = inventoryOf({ '-3': resourceJson({}) });
    const records = recordsOf(WA_SLUG, waInputs([first, second]));
    expect(records.map((record) => record.dataProviderRef.serialize())).toEqual(['wa:-1', 'wa:-2', 'wa:-3']);
  });

  test('the same resource id in two envelopes is emitted twice, and the repo collapses it', () => {
    const records = recordsOf(WA_SLUG, waInputs([inventoryOf({ '-1': resourceJson({}) }), inventoryOf({ '-1': resourceJson({}) })]));
    expect(records).toHaveLength(2);
  });

  test('a dictionary the tenant did not capture leaves every label empty without failing the run', () => {
    const record = waRecord(BASIC_RESOURCE, LINKED_MAPS, null);
    expect(record.attributes).toEqual([]);
    expect(record.kindListed).toBeNull();
  });
});
