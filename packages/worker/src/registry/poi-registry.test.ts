import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { loadPoiRegistry } from './poi-registry.js';
import { importTargets } from './targets.js';

const PRODUCTION_REGISTRY_PATH = fileURLToPath(
  new URL('../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url),
);
const EXPECTED_POI_DATA_ROWS = 10;
const EXPECTED_CAMPSITE_DATA_ROWS = 8;
const EXPECTED_DATA_SOURCES = 26;
const DEFAULT_FUZZY_THRESHOLD = 0.5;

const PRODUCTION_REGISTRY = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);

const ALL_PRODUCTION_SLUGS: ReadonlySet<string> = new Set(
  [...PRODUCTION_REGISTRY.poiData, ...PRODUCTION_REGISTRY.campsiteData].flatMap((row) =>
    row.etls.map((etl) => etl.slug),
  ),
);

describe('poi registry', () => {
  test('the production registry yields the eighteen import targets in Kotlin order', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    const names = importTargets(registry, ALL_PRODUCTION_SLUGS).map(
      (t) => `${t.section}:${t.name}`,
    );
    expect(names).toEqual([
      'poi_data:Campflare Campgrounds',
      'poi_data:Rec.gov Campgrounds',
      'poi_data:Washington State Parks',
      'poi_data:BC Provincial Parks',
      'poi_data:Parks Canada',
      'poi_data:Alberta Provincial Parks',
      'poi_data:New York State Parks',
      'poi_data:California State Parks',
      'poi_data:Planet Fitness',
      'poi_data:Tesla Superchargers',
      'campsite_data:Campflare Campsites',
      'campsite_data:Rec.gov Campsites',
      'campsite_data:Washington Aspira Resources',
      'campsite_data:BC Aspira Resources',
      'campsite_data:Parks Canada Aspira Resources',
      'campsite_data:California State Park Sites',
      'campsite_data:Alberta Provincial Park Sites',
      'campsite_data:New York State Park Sites',
    ]);
  });

  test('a row whose etl slug is not implemented yields no target', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    const without = new Set([...ALL_PRODUCTION_SLUGS].filter((s) => s !== 'planet-fitness'));
    expect(importTargets(registry, without).map((t) => t.name)).not.toContain('Planet Fitness');
  });

  test('args and geometry survive the load', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    const wa = registry.poiDataByName('Washington State Parks')!.etls[0]!;
    expect(wa.args).toEqual({ host: 'washington.goingtocamp.com', tenant: 'wa' });
    expect(wa.inputs).toEqual([
      'aspira-maps-wa',
      'uscampgrounds',
      'aspira-inventory-wa',
      'aspira-dictionaries-wa',
    ]);
    expect(wa.geometry?.sources.map((s) => s.input)).toEqual(['uscampgrounds']);
    expect(wa.geometry?.match.parentFallback).toBe(true);
  });

  test('every section of the production registry loads with its declared row count', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    expect(registry.poiData).toHaveLength(EXPECTED_POI_DATA_ROWS);
    expect(registry.campsiteData).toHaveLength(EXPECTED_CAMPSITE_DATA_ROWS);
    expect(registry.dataSources).toHaveLength(EXPECTED_DATA_SOURCES);
    expect(registry.bookingProviders.length).toBeGreaterThan(0);
  });

  test('data sources keep the fetcher output prefix and dependencies in camelCase', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    const campsites = registry.dataSource('campflare-campsites-export')!;
    expect(campsites.dependsOn).toEqual(['campflare-campgrounds-export']);
    expect(campsites.fetcher.outputDirPrefix).toBe('data/raw/campflare-campsites-export');
    expect(campsites.fetcher.timeoutSec).toBe(7200);
    expect(campsites.fetcher.args).toEqual({
      slug: 'campflare-campsites-export',
      kind: 'campsites',
    });
    expect(registry.dataSource('no-such-slug')).toBeUndefined();
  });

  test('agency loads as a constant or a derived field', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    expect(registry.poiDataByName('Washington State Parks')!.agency).toEqual({
      kind: 'constant',
      value: 'WA State Parks',
    });
    expect(registry.poiDataByName('Rec.gov Campgrounds')!.agency).toEqual({
      kind: 'derived',
      field: 'ORGANIZATION[0].OrgName',
    });
    expect(registry.poiDataByName('Campflare Campgrounds')!.agency).toBeUndefined();
  });

  test('geometry sources carry their format, state and name property, and the match default', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    const wa = registry.poiDataByName('Washington State Parks')!.etls[0]!;
    expect(wa.geometry?.sources[0]).toEqual({
      input: 'uscampgrounds',
      format: 'uscampgrounds_csv',
      state: 'WA',
    });
    expect(wa.geometry?.match.fuzzyThreshold).toBe(DEFAULT_FUZZY_THRESHOLD);
    const pc = registry.poiDataByName('Parks Canada')!.etls[0]!;
    expect(pc.geometry?.sources.map((s) => s.nameProperty)).toEqual(['Name_e', undefined]);
  });

  test('campsite rows are found by name and a disabled row yields no target', () => {
    const registry = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);
    expect(registry.campsiteDataByName('Rec.gov Campsites')!.etls[0]!.slug).toBe(
      'recgov-campsites',
    );
    expect(registry.campsiteDataByName('missing')).toBeUndefined();
    const disabled = {
      ...registry,
      poiData: registry.poiData.map((row) =>
        row.name === 'Planet Fitness' ? { ...row, enabled: false } : row,
      ),
    };
    expect(importTargets(disabled, ALL_PRODUCTION_SLUGS).map((t) => t.name)).not.toContain(
      'Planet Fitness',
    );
  });
});
