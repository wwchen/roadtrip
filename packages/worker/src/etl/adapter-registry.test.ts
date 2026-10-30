import { describe, expect, test } from 'vitest';
import { loadPoiRegistry, type EtlEntry } from '../registry/poi-registry.js';
import { AspiraCampgroundsEtl } from '../vendors/aspira/campgrounds.js';
import { BcParksCampgroundsEtl } from '../vendors/bcparks/campgrounds.js';
import { adapterRegistry } from './adapter-registry.js';

const PRODUCTION_REGISTRY_PATH = decodeURIComponent(
  new URL('../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname,
);
const WA_SLUG = 'aspira-wa-campgrounds';
const BC_SLUG = 'aspira-bc-campgrounds';
const PC_SLUG = 'aspira-pc-campgrounds';
const ASPIRA_ADAPTER = 'AspiraCampgroundsEtl';
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
