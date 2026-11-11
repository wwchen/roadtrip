import { shippedTenantRegistry } from '@roadtrip/domain';
import { expect, test } from 'vitest';
import { recgovTargetFor, type BookingIdentitySource } from './bookingTarget';

const RECGOV_CAMPGROUND: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] };
const tenants = shippedTenantRegistry();

test('a campsite whose own identity is rec.gov books on rec.gov', () => {
  const campsite: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '300', bookingAliases: [] };
  expect(recgovTargetFor(campsite, RECGOV_CAMPGROUND, tenants)).toEqual({ vendorSiteId: '300', providerDisplay: 'Recreation.gov' });
});

test('a Campflare site with a rec.gov alias books on rec.gov', () => {
  const campsite: BookingIdentitySource = {
    bookingProvider: 'campflare',
    bookingProviderRef: 'cf-1',
    bookingAliases: [{ provider: 'recgov', ref: '301' }],
  };
  const campground: BookingIdentitySource = {
    bookingProvider: 'campflare',
    bookingProviderRef: 'cf-cg',
    bookingAliases: [{ provider: 'recgov', ref: '232447' }],
  };
  expect(recgovTargetFor(campsite, campground, tenants)?.vendorSiteId).toBe('301');
});

test('no rec.gov identity on the campsite, or none on its campground, is no target', () => {
  const campflareOnly: BookingIdentitySource = { bookingProvider: 'campflare', bookingProviderRef: 'cf-1', bookingAliases: [] };
  const recgovSite: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '300', bookingAliases: [] };
  expect(recgovTargetFor(campflareOnly, RECGOV_CAMPGROUND, tenants)).toBeNull();
  expect(recgovTargetFor(recgovSite, campflareOnly, tenants)).toBeNull();
});

test('a blank rec.gov ref is no target', () => {
  const campsite: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '  ', bookingAliases: [] };
  expect(recgovTargetFor(campsite, RECGOV_CAMPGROUND, tenants)).toBeNull();
});
