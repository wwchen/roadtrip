import { shippedTenantRegistry } from '@roadtrip/domain';
import { expect, test } from 'vitest';
import type { AppUserRow } from '../../repo/appUserRepo';
import type { BookingIdentitySource } from './bookingTarget';
import { addToCartCapability } from './addToCartCapability';

const READER: AppUserRow = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system' };
const RECGOV_CAMPGROUND: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] };
const RECGOV_SITE: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '300', bookingAliases: [] };
const CAMPFLARE_SITE: BookingIdentitySource = { bookingProvider: 'campflare', bookingProviderRef: 'cf', bookingAliases: [] };
const tenants = shippedTenantRegistry();
const NAMED = { provider: 'recgov', provider_display: 'Recreation.gov' };

test('a credentialed reader on an all-rec.gov matrix is ready', () => {
  expect(addToCartCapability([RECGOV_SITE, RECGOV_SITE], RECGOV_CAMPGROUND, READER, true, tenants)).toEqual({ state: 'ready', ...NAMED });
});

test('a reader without credentials is no_credentials', () => {
  expect(addToCartCapability([RECGOV_SITE], RECGOV_CAMPGROUND, READER, false, tenants)).toEqual({ state: 'no_credentials', ...NAMED });
});

test('no reader is signed_out', () => {
  expect(addToCartCapability([RECGOV_SITE], RECGOV_CAMPGROUND, null, false, tenants)).toEqual({ state: 'signed_out', ...NAMED });
});

test('one site with no rec.gov target makes the matrix unsupported, before signed_out', () => {
  expect(addToCartCapability([RECGOV_SITE, CAMPFLARE_SITE], RECGOV_CAMPGROUND, null, false, tenants)).toEqual({ state: 'unsupported' });
});

test('an empty matrix is unsupported', () => {
  expect(addToCartCapability([], RECGOV_CAMPGROUND, READER, true, tenants)).toEqual({ state: 'unsupported' });
});
