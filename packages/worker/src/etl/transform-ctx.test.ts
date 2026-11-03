import { describe, expect, test } from 'vitest';
import type { EtlEntry, PoiDataEntry, PoiRegistry } from '../registry/poi-registry.js';
import { TransformCtx } from './transform-ctx.js';

const RAW_DIR = '/data/raw';

function etl(slug: string, args: Record<string, string> = {}): EtlEntry {
  return { slug, adapter: 'FakeEtl', inputs: [], args };
}

function poi(overrides: Partial<PoiDataEntry> & Pick<PoiDataEntry, 'name' | 'etls'>): PoiDataEntry {
  return { enabled: true, category: 'campground', ...overrides };
}

function registryOf(poiData: PoiDataEntry[]): PoiRegistry {
  return {
    dataSources: [],
    poiData,
    campsiteData: [],
    bookingProviders: [],
    dataSource: () => undefined,
    poiDataByName: (name) => poiData.find((row) => row.name === name),
    campsiteDataByName: () => undefined,
  };
}

const CTX = TransformCtx.load(
  RAW_DIR,
  registryOf([
    poi({
      name: 'WA',
      subcategory: 'state_park',
      agency: { kind: 'constant', value: 'Washington State Parks' },
      etls: [etl('wa-campgrounds', { host: 'washington.goingtocamp.com', tenant: 'wa' })],
    }),
    poi({
      name: 'Rec.gov',
      agency: { kind: 'derived', field: 'OrgName' },
      etls: [etl('recgov-campgrounds')],
    }),
    poi({ name: 'Gyms', category: 'gym', etls: [etl('planet-fitness')] }),
  ]),
);

describe('TransformCtx', () => {
  test('carries the raw dir', () => {
    expect(CTX.rawDir).toBe(RAW_DIR);
  });

  test('subcategory and agency come from the poi_data row of the terminal etl', () => {
    expect(CTX.subcategoryFor('wa-campgrounds')).toBe('state_park');
    expect(CTX.agencyFor('wa-campgrounds')).toEqual({ kind: 'constant', value: 'Washington State Parks' });
    expect(CTX.subcategoryFor('planet-fitness')).toBeUndefined();
    expect(CTX.agencyFor('planet-fitness')).toBeUndefined();
    expect(CTX.subcategoryFor('no-such-slug')).toBeUndefined();
  });

  test('a row with several etls keys subcategory and agency by the last one only, and args by all', () => {
    const chained = TransformCtx.load(
      RAW_DIR,
      registryOf([
        poi({
          name: 'Chain',
          subcategory: 'bucket',
          agency: { kind: 'constant', value: 'Agency' },
          etls: [etl('first', { a: '1' }), etl('last', { b: '2' })],
        }),
      ]),
    );
    expect(chained.subcategoryFor('first')).toBeUndefined();
    expect(chained.subcategoryFor('last')).toBe('bucket');
    expect(chained.argFor('first', 'a')).toBe('1');
    expect(chained.argFor('last', 'b')).toBe('2');
  });

  test('argFor reads per-etl YAML args and returns undefined for absent keys', () => {
    expect(CTX.argFor('wa-campgrounds', 'host')).toBe('washington.goingtocamp.com');
    expect(CTX.argFor('wa-campgrounds', 'missing')).toBeUndefined();
    expect(CTX.argFor('no-such-slug', 'host')).toBeUndefined();
  });

  test('argFor does not resolve names Object.prototype owns', () => {
    expect(CTX.argFor('wa-campgrounds', 'constructor')).toBeUndefined();
    expect(CTX.argFor('wa-campgrounds', 'toString')).toBeUndefined();
  });

  test('requiredConstantAgency returns a constant and names the problem otherwise', () => {
    expect(CTX.requiredConstantAgency('wa-campgrounds')).toBe('Washington State Parks');
    expect(() => CTX.requiredConstantAgency('recgov-campgrounds')).toThrow(
      "recgov-campgrounds uses poi_data.agency.derived_from_field='OrgName', not a constant agency",
    );
    expect(() => CTX.requiredConstantAgency('planet-fitness')).toThrow(
      'planet-fitness is missing required poi_data.agency',
    );
  });
});
