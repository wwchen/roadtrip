import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { adapterRegistry } from '../../etl/adapter-registry.js';
import { parseEnvelope, type Envelope } from '../../etl/envelope.js';
import { InputBundle } from '../../etl/input-bundle.js';
import type { ParseResult, TransformResult } from '../../etl/source-etl.js';
import { TransformCtx } from '../../etl/transform-ctx.js';
import type { CampgroundUpsertCandidate } from '../../model/campground-candidate.js';
import { loadPoiRegistry, type EtlEntry } from '../../registry/poi-registry.js';
import { AspiraCampgroundsEtl } from './campgrounds.js';
import type { AspiraJoinDto } from './join-dto.js';

const TEMP_DIR_PREFIX = 'aspira-campgrounds-';
const HTTP_OK = 200;
const RAW_DIR = '/unused';
const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const WA_SLUG = 'aspira-wa-campgrounds';
const PC_SLUG = 'aspira-pc-campgrounds';
const WA_MAPS = 'aspira-maps-wa';
const WA_CSV = 'uscampgrounds';
const WA_INVENTORY = 'aspira-inventory-wa';
const WA_DICTIONARIES = 'aspira-dictionaries-wa';
const PC_MAPS = 'aspira-maps-pc';
const PC_ACCOMMODATION = 'apca-accommodation';
const PC_PLACES = 'apca-places';
const PC_INVENTORY = 'aspira-inventory-pc';
const PC_DICTIONARIES = 'aspira-dictionaries-pc';
const WIDE_ID = '9007199254740993';
const CAMPSITE_CATEGORY = 1;
const PARKING_CATEGORY = 2;

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
  const entry = REGISTRY.poiData.flatMap((row) => row.etls).find((etl) => etl.slug === slug);
  if (entry === undefined) throw new Error(`no etl ${slug}`);
  return entry;
}

function etlOf(slug: string): AspiraCampgroundsEtl {
  const entry = entryOf(slug);
  const factory = adapterRegistry[entry.adapter];
  if (factory === undefined) throw new Error(`no adapter ${entry.adapter}`);
  return factory(entry).etl as AspiraCampgroundsEtl;
}

type Inputs = Record<string, string[]>;

function bundleOf(inputs: Inputs): InputBundle {
  return new InputBundle(new Map(Object.entries(inputs).map(([slug, payloads]) => [slug, payloads.map(envelopeOf)])));
}

function parsed(slug: string, inputs: Inputs): ParseResult<AspiraJoinDto>[] {
  return [...etlOf(slug).parse(bundleOf(inputs))];
}

function transformed(slug: string, inputs: Inputs): TransformResult<CampgroundUpsertCandidate>[] {
  const etl = etlOf(slug);
  const results: TransformResult<CampgroundUpsertCandidate>[] = [];
  for (const result of etl.parse(bundleOf(inputs))) {
    if (!result.ok) throw new Error(result.errors.join());
    results.push(...etl.transform(result.dto, CTX));
  }
  return results;
}

function recordsOf(slug: string, inputs: Inputs): CampgroundUpsertCandidate[] {
  return transformed(slug, inputs).map((result) => {
    if (!result.ok) throw new Error(result.errors.join());
    return result.record;
  });
}

function csvRow(name: string, state: string, longitude = '-122.5', latitude = '47.5'): string {
  return [longitude, latitude, '', '', name, '', '', '', '', '', '', '', state].join(',');
}

function csv(...rows: string[]): string {
  return JSON.stringify(rows.join('\n'));
}

function title(text: string): string {
  return `[{"cultureName":"en-US","title":${JSON.stringify(text)}}]`;
}

function leafNode(
  mapId: string,
  name: string,
  fields: { transactionLocationId?: string; resourceLocationId?: string | null; parentMapId?: string } = {},
): string {
  const parts = [
    `"mapId":${mapId}`,
    `"transactionLocationId":${fields.transactionLocationId ?? '7'}`,
    `"localizedValues":${title(name)}`,
  ];
  if (fields.resourceLocationId !== null) parts.push(`"resourceLocationId":${fields.resourceLocationId ?? '100'}`);
  if (fields.parentMapId !== undefined) parts.push(`"parentMap":{"mapId":${fields.parentMapId}}`);
  return `{${parts.join(',')}}`;
}

function parentNode(mapId: string, name: string): string {
  return `{"mapId":${mapId},"localizedValues":${title(name)}}`;
}

function maps(...nodes: string[]): string {
  return `[${nodes.join(',')}]`;
}

function inventory(...resources: string[]): string {
  return `{${resources.map((resource, index) => `"r${index}":${resource}`).join(',')}}`;
}

function resource(resourceLocationId: string, mapIds: string[], category = CAMPSITE_CATEGORY): string {
  return `{"resourceLocationId":${resourceLocationId},"resourceCategoryId":${category},"mapIds":[${mapIds.join(',')}]}`;
}

const DICTIONARY =
  `{"resource_categories":[{"resourceCategoryId":${CAMPSITE_CATEGORY},"showResourceCapacityOnline":true},` +
  `{"resourceCategoryId":${PARKING_CATEGORY},"showResourceCapacityOnline":false}]}`;

function waInputs(mapsJson: string, csvJson: string, inventoryJson = '{}', dictionaryJson: string | null = null): Inputs {
  return {
    [WA_MAPS]: [mapsJson],
    [WA_CSV]: [csvJson],
    [WA_INVENTORY]: [inventoryJson],
    ...(dictionaryJson === null ? {} : { [WA_DICTIONARIES]: [dictionaryJson] }),
  };
}

const WA_CSV_ROWS = csv(
  csvRow('Brooks Memorial State Park', 'WA', '-120.667', '46.039'),
  csvRow('Manchester State Park', 'WA', '-122.545', '47.548'),
  csvRow('Elsewhere', 'SD', '-101.7', '43.1'),
);

describe('the Aspira campgrounds adapter, parse', () => {
  test('a run with every declared input yields one DTO carrying the leaves and the geometry sources', () => {
    const [result, ...rest] = parsed(WA_SLUG, waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS));
    expect(rest).toEqual([]);
    expect(result?.ok).toBe(true);
    if (result === undefined || !result.ok) throw new Error('expected a DTO');
    expect(result.dto.leaves.map((leaf) => leaf.name)).toEqual(['Brooks Memorial']);
    expect(result.dto.geomSources.map(([slug]) => slug)).toEqual([WA_CSV]);
    expect(result.dto.dictionaryPayload).toBeNull();
    expect(result.dto.inventoryEnvelopes).toHaveLength(1);
  });

  test('a declared geometry source missing from the inputs is reported, not thrown', () => {
    const inputs = waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS);
    delete inputs[WA_CSV];
    expect(parsed(WA_SLUG, inputs)).toEqual([
      { ok: false, errors: [`declared geometry source '${WA_CSV}' is not among this run's inputs`] },
    ]);
  });

  test('a declared geometry source with no envelopes is reported', () => {
    const inputs = waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS);
    inputs[WA_CSV] = [];
    expect(parsed(WA_SLUG, inputs)).toEqual([
      { ok: false, errors: [`declared geometry source '${WA_CSV}' has no envelopes`] },
    ]);
  });

  test('an input that is neither declared nor a role feed is reported, and every error is collected', () => {
    const inputs = waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS);
    delete inputs[WA_CSV];
    inputs.stray = ['{}'];
    expect(parsed(WA_SLUG, inputs)).toEqual([
      {
        ok: false,
        errors: [
          `declared geometry source '${WA_CSV}' is not among this run's inputs`,
          "input 'stray' is neither a declared geometry source nor the maps, inventory or dictionaries feed",
        ],
      },
    ]);
  });

  test('without a maps input the run reports it, after any other error', () => {
    const inputs = waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS);
    delete inputs[WA_MAPS];
    expect(parsed(WA_SLUG, inputs)).toEqual([{ ok: false, errors: ['no /api/maps input declared'] }]);
  });

  test('a maps feed with no leaves is reported', () => {
    expect(parsed(WA_SLUG, waInputs(maps(parentNode('1', 'Park')), WA_CSV_ROWS))).toEqual([
      { ok: false, errors: ['no leaves from /api/maps'] },
    ]);
  });

  test('a maps payload that is not an array throws', () => {
    expect(() => parsed(WA_SLUG, waInputs('{"mapId":1}', WA_CSV_ROWS))).toThrow('Element is not a JsonArray');
  });

  test('a multi-part maps feed throws, since the walk reads exactly one envelope', () => {
    const inputs = waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS);
    inputs[WA_MAPS] = [maps(leafNode('1', 'Brooks Memorial')), maps(leafNode('2', 'Manchester'))];
    expect(() => parsed(WA_SLUG, inputs)).toThrow(/multi-part/);
  });

  test('a dictionary payload that is not an object is read as no dictionary', () => {
    const [result] = parsed(WA_SLUG, waInputs(maps(leafNode('1', 'Brooks Memorial')), WA_CSV_ROWS, '{}', '[1,2]'));
    expect(result?.ok && result.dto.dictionaryPayload).toBeNull();
  });
});

describe('the Aspira campgrounds adapter, transform on the Washington row', () => {
  test('an exact match emits the point, the booking surface and an exact provenance', () => {
    const [record, ...rest] = recordsOf(
      WA_SLUG,
      waInputs(
        maps(leafNode('11', 'Brooks Memorial State Park', { transactionLocationId: WIDE_ID, resourceLocationId: '100' })),
        WA_CSV_ROWS,
        inventory(resource('100', ['11'])),
      ),
    );
    expect(rest).toEqual([]);
    expect(record).toMatchObject({
      bookingProvider: 'aspira',
      bookingProviderRef: `wa:${WIDE_ID}:11:100`,
      name: 'Brooks Memorial State Park',
      parentName: null,
      latitude: 46.039,
      longitude: -120.667,
      kind: 'state',
      location: { latitude: 46.039, longitude: -120.667 },
      reservationUrl: 'https://washington.goingtocamp.com/',
      links: [{ url: 'https://washington.goingtocamp.com/' }],
      management: { agency: 'WA State Parks' },
      sourceUrl: 'https://washington.goingtocamp.com/',
      geometryProvenance: { match_kind: 'exact', source: WA_CSV, matched_name: 'brooks memorial' },
    });
    expect(record?.dataProviderRef.serialize()).toBe(`${WIDE_ID}:11`);
    expect(record?.sourcePayload).toBe(
      `{"name":"Brooks Memorial State Park","transactionLocationId":${WIDE_ID},"mapId":11,"resourceLocationId":100}`,
    );
  });

  test('a fuzzy match records its score, and a parent fallback records the parent key', () => {
    const records = recordsOf(
      WA_SLUG,
      waInputs(
        maps(
          parentNode('50', 'Manchester State Park'),
          leafNode('11', 'Manchester Beach'),
          leafNode('12', 'Unrelated Loop A', { resourceLocationId: '101', parentMapId: '50' }),
        ),
        WA_CSV_ROWS,
      ),
    );
    expect(records.map((record) => record.geometryProvenance)).toEqual([
      { match_kind: 'fuzzy', source: WA_CSV, matched_name: 'manchester', score: 0.5 },
      { match_kind: 'parent', source: WA_CSV, matched_name: 'manchester' },
    ]);
    expect(records[1]?.parentName).toBe('Manchester State Park');
  });

  test('a container leaf, a leaf with no match and an unmatched orphan are dropped', () => {
    const records = recordsOf(
      WA_SLUG,
      waInputs(
        maps(
          leafNode('11', 'Manchester State Park', { resourceLocationId: null }),
          leafNode('12', 'Zzz Yyy', { resourceLocationId: '101' }),
          leafNode('13', 'Brooks Memorial', { resourceLocationId: '102' }),
        ),
        WA_CSV_ROWS,
      ),
    );
    expect(records.map((record) => record.name)).toEqual(['Brooks Memorial']);
  });

  test('a leaf with no inventory entry is still emitted, with no booking reference', () => {
    const [record] = recordsOf(WA_SLUG, waInputs(maps(leafNode('11', 'Brooks Memorial')), WA_CSV_ROWS));
    expect(record?.bookingProvider).toBe('aspira');
    expect(record?.bookingProviderRef).toBeNull();
  });

  test('one inventory map id names the booking map, and two fall back to the leaf map id', () => {
    const single = recordsOf(
      WA_SLUG,
      waInputs(maps(leafNode('11', 'Brooks Memorial')), WA_CSV_ROWS, inventory(resource('100', ['900']))),
    );
    const several = recordsOf(
      WA_SLUG,
      waInputs(maps(leafNode('11', 'Brooks Memorial')), WA_CSV_ROWS, inventory(resource('100', ['900', '901']))),
    );
    expect(single[0]?.bookingProviderRef).toBe('wa:7:900:100');
    expect(several[0]?.bookingProviderRef).toBe('wa:7:11:100');
  });

  test('a resource location of only non-bookable categories is dropped when the dictionary says so', () => {
    const inputs = waInputs(
      maps(leafNode('11', 'Brooks Memorial', { resourceLocationId: '100' }), leafNode('12', 'Manchester', { resourceLocationId: '101' })),
      WA_CSV_ROWS,
      inventory(resource('100', ['11'], PARKING_CATEGORY), resource('101', ['12'], CAMPSITE_CATEGORY)),
      DICTIONARY,
    );
    expect(recordsOf(WA_SLUG, inputs).map((record) => record.name)).toEqual(['Manchester']);
    const withoutDictionary = waInputs(
      maps(leafNode('11', 'Brooks Memorial', { resourceLocationId: '100' }), leafNode('12', 'Manchester', { resourceLocationId: '101' })),
      WA_CSV_ROWS,
      inventory(resource('100', ['11'], PARKING_CATEGORY), resource('101', ['12'], CAMPSITE_CATEGORY)),
    );
    expect(recordsOf(WA_SLUG, withoutDictionary).map((record) => record.name)).toEqual(['Brooks Memorial', 'Manchester']);
  });

  test('a geometry index with no key makes the transform throw', () => {
    const elsewhere = csv(csvRow('Elsewhere', 'SD'));
    expect(() => transformed(WA_SLUG, waInputs(maps(leafNode('11', 'Brooks Memorial')), elsewhere))).toThrow(/geometry index is empty/);
  });

  test('a leaf naming a point that two feed rows share takes the first row', () => {
    const duplicated = csv(
      csvRow('Manchester State Park', 'WA', '-122.0', '47.0'),
      csvRow('Manchester', 'WA', '-123.0', '48.0'),
    );
    const [record] = recordsOf(WA_SLUG, waInputs(maps(leafNode('11', 'Manchester')), duplicated));
    expect([record?.latitude, record?.longitude]).toEqual([47, -122]);
  });
});

describe('the Aspira campgrounds adapter, transform on the Parks Canada row', () => {
  const accommodation = (name: string, longitude: string, latitude: string) =>
    `{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"Name_e":${JSON.stringify(name)}},` +
    `"geometry":{"type":"Point","coordinates":[${longitude},${latitude}]}}]}`;
  const places = (name: string, longitude: string, latitude: string) =>
    `{"features":[{"attributes":{"DESC_EN":${JSON.stringify(name)}},"centroid":{"x":${longitude},"y":${latitude}}}]}`;
  const pcInputs = (mapsJson: string, accommodationJson: string, placesJson: string): Inputs => ({
    [PC_MAPS]: [mapsJson],
    [PC_ACCOMMODATION]: [accommodationJson],
    [PC_PLACES]: [placesJson],
    [PC_INVENTORY]: ['{}'],
    [PC_DICTIONARIES]: [DICTIONARY],
  });

  test('a name both feeds claim resolves to the first declared source', () => {
    const [record] = recordsOf(
      PC_SLUG,
      pcInputs(
        maps(leafNode('11', 'Banff National Park')),
        accommodation('Banff', '-115.5', '51.1'),
        places('Banff National Park of Canada', '-116.0', '51.5'),
      ),
    );
    expect(record).toMatchObject({
      kind: 'federal',
      latitude: 51.1,
      longitude: -115.5,
      management: { agency: 'Parks Canada' },
      geometryProvenance: { match_kind: 'exact', source: PC_ACCOMMODATION, matched_name: 'banff' },
    });
  });

  test('a name only the second feed has resolves to it', () => {
    const [record] = recordsOf(
      PC_SLUG,
      pcInputs(
        maps(leafNode('11', 'Yoho National Park')),
        accommodation('Banff', '-115.5', '51.1'),
        places('Yoho National Park of Canada', '-116.5', '51.4'),
      ),
    );
    expect(record?.geometryProvenance).toEqual({ match_kind: 'exact', source: PC_PLACES, matched_name: 'yoho' });
  });

  test('a feed that does not contain a geojson point contributes nothing', () => {
    const polygonOnly =
      '{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"Name_e":"Banff"},"geometry":{"type":"Polygon","coordinates":[]}}]}';
    expect(() =>
      transformed(PC_SLUG, pcInputs(maps(leafNode('11', 'Banff')), polygonOnly, '{"features":[]}')),
    ).toThrow(/geometry index is empty/);
  });
});
