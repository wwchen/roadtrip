import { BOOKING_VENDOR_PROFILES } from '@roadtrip/domain';
import { expect, test } from 'vitest';
import { loadPoiRegistry } from './poi-registry.js';

const PRODUCTION_REGISTRY_PATH = new URL('../../../../backend/src/main/resources/poi-registry.yaml', import.meta.url).pathname;

test("the domain's booking tenants match poi-registry.yaml's booking_providers", () => {
  const fromYaml = loadPoiRegistry(PRODUCTION_REGISTRY_PATH).bookingProviders.map((entry) => ({
    provider: entry.id,
    displayName: entry.displayName,
    sells: entry.sells,
    tenants: entry.tenants.map((tenant) => ({
      provider: entry.id,
      code: tenant.code ?? null,
      host: tenant.host,
      displayName: tenant.displayName ?? entry.displayName,
    })),
  }));
  expect(BOOKING_VENDOR_PROFILES).toEqual(fromYaml);
});
