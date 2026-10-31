import { DataProvider } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import { loadPoiRegistry, type EtlEntry } from '../registry/poi-registry.js';
import { AspiraCampgroundsEtl } from '../vendors/aspira/campgrounds.js';
import { AspiraCampsitesEtl } from '../vendors/aspira/campsites.js';
import { BcParksCampgroundsEtl } from '../vendors/bcparks/campgrounds.js';
import { adapterRegistry } from './adapter-registry.js';

const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const WA_SLUG = 'aspira-wa-campgrounds';
const BC_SLUG = 'aspira-bc-campgrounds';
const PC_SLUG = 'aspira-pc-campgrounds';
const ASPIRA_ADAPTER = 'AspiraCampgroundsEtl';
const ASPIRA_CAMPSITES_ADAPTER = 'AspiraCampsitesEtl';
const WA_CAMPSITES_SLUG = 'aspira-wa-campsites';
const BC_CAMPSITES_SLUG = 'aspira-bc-campsites';
const PC_CAMPSITES_SLUG = 'aspira-pc-campsites';
const BC_PARKS_ADAPTER = 'BcParksCampgroundsEtl';

const REGISTRY = loadPoiRegistry(PRODUCTION_REGISTRY_PATH);

function entryOf(slug: string): EtlEntry {
  const entry = REGISTRY.poiData.flatMap((row) => row.etls).find((etl) => etl.slug === slug);
  if (entry === undefined) throw new Error(`no etl ${slug}`);
  return entry;
}

function built(entry: EtlEntry): unknown {
  const factory = adapterRegistry[entry.adapter];
  if (factory === undefined) throw new Error(`no adapter ${entry.adapter}`);
  return factory(entry).etl;
}

function campsiteEntryOf(slug: string): EtlEntry {
  const entry = REGISTRY.campsiteData.flatMap((row) => row.etls).find((etl) => etl.slug === slug);
  if (entry === undefined) throw new Error(`no etl ${slug}`);
  return entry;
}

function withoutArg(entry: EtlEntry, key: string): EtlEntry {
  const { [key]: _removed, ...args } = entry.args;
  return { ...entry, args };
}

function without(entry: EtlEntry, field: 'geometry' | 'tenant'): EtlEntry {
  if (field === 'geometry') return { ...entry, geometry: undefined };
  const { [field]: _removed, ...args } = entry.args;
  return { ...entry, args };
}

describe('the campground adapters built from the production registry', () => {
  test.each([
    [WA_SLUG, ASPIRA_ADAPTER, AspiraCampgroundsEtl, 'wa'],
    [PC_SLUG, ASPIRA_ADAPTER, AspiraCampgroundsEtl, 'pc'],
    [BC_SLUG, BC_PARKS_ADAPTER, BcParksCampgroundsEtl, 'bc'],
  ])('%s is a %s carrying its slug, its tenant %s and its whole geometry policy', (slug, adapter, expected, tenant) => {
    const entry = entryOf(slug);
    expect(entry.adapter).toBe(adapter);
    const etl = built(entry);
    expect(etl).toBeInstanceOf(expected);
    expect(etl).toMatchObject({ etlSlug: slug, aspiraTenant: tenant, geometry: entry.geometry });
  });

  test.each([ASPIRA_ADAPTER, BC_PARKS_ADAPTER])('%s refuses a row with no geometry policy or no tenant', (adapter) => {
    const entry = entryOf(adapter === ASPIRA_ADAPTER ? WA_SLUG : BC_SLUG);
    expect(() => built(without(entry, 'geometry'))).toThrow(/geometry policy is required/);
    expect(() => built(without(entry, 'tenant'))).toThrow("Missing required ETL arg 'tenant'");
  });
});

describe('the Aspira campsite adapters built from the production registry', () => {
  test.each([
    [WA_CAMPSITES_SLUG, 'wa', 'aspira-maps-wa', 'aspira-inventory-wa', 'aspira-dictionaries-wa', DataProvider.ASPIRA],
    [BC_CAMPSITES_SLUG, 'bc', 'aspira-maps-bc', 'aspira-inventory-bc', 'aspira-dictionaries-bc', DataProvider.STRAPI],
    [PC_CAMPSITES_SLUG, 'pc', 'aspira-maps-pc', 'aspira-inventory-pc', 'aspira-dictionaries-pc', DataProvider.ASPIRA],
  ])('%s carries tenant %s, its three inputs and the parent provider it resolves against', (slug, tenant, maps, inventory, dictionaries, parent) => {
    const entry = campsiteEntryOf(slug);
    expect(entry.adapter).toBe(ASPIRA_CAMPSITES_ADAPTER);
    const etl = built(entry);
    expect(etl).toBeInstanceOf(AspiraCampsitesEtl);
    expect(etl).toMatchObject({
      etlSlug: slug,
      aspiraTenant: tenant,
      mapsInputSlug: maps,
      inventoryInputSlug: inventory,
      dictionariesInputSlug: dictionaries,
      parentDataProvider: parent,
    });
  });

  test('a row with no dictionaries input builds an adapter with none, and no parent provider means Aspira', () => {
    const entry = campsiteEntryOf(BC_CAMPSITES_SLUG);
    const bare = withoutArg(withoutArg(entry, 'dictionaries_input'), 'parent_data_provider');
    expect(built(bare)).toMatchObject({ dictionariesInputSlug: null, parentDataProvider: DataProvider.ASPIRA });
  });

  test.each([['tenant'], ['maps_input'], ['inventory_input']])('a row missing %s is refused', (key) => {
    expect(() => built(withoutArg(campsiteEntryOf(WA_CAMPSITES_SLUG), key))).toThrow(`Missing required ETL arg '${key}'`);
  });

  test('a parent provider that is not a data provider is refused with the Kotlin message', () => {
    const entry = campsiteEntryOf(BC_CAMPSITES_SLUG);
    expect(() => built({ ...entry, args: { ...entry.args, parent_data_provider: 'strapi' } })).toThrow(
      'Unknown data provider: strapi',
    );
  });

  test('a parent provider is read by its wire id, and an inherited property name is not one', () => {
    const entry = campsiteEntryOf(WA_CAMPSITES_SLUG);
    expect(built({ ...entry, args: { ...entry.args, parent_data_provider: 'bcparks-strapi' } })).toMatchObject({
      parentDataProvider: DataProvider.STRAPI,
    });
    expect(() => built({ ...entry, args: { ...entry.args, parent_data_provider: 'constructor' } })).toThrow(
      'Unknown data provider: constructor',
    );
  });
});
