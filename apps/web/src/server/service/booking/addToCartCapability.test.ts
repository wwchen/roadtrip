import { shippedTenantRegistry } from '@roadtrip/domain';
import { expect, test, vi } from 'vitest';
import { capabilityDto } from '../../mapper/http/capabilityHttp';
import { memoryVault } from '../../testing/memoryVault';
import type { ActiveUser } from '../auth/model';
import type { BookingIdentitySource } from './bookingTarget';
import { addToCartCapability, readerStanding } from './addToCartCapability';
import type { ReaderPorts } from './ports';

const READER: ActiveUser = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const STORED = { username: 'rec@example.com', password: 'hunter2-recognisable' };
const RECGOV_CAMPGROUND: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] };
const RECGOV_SITE: BookingIdentitySource = { bookingProvider: 'recgov', bookingProviderRef: '300', bookingAliases: [] };
const CAMPFLARE_SITE: BookingIdentitySource = { bookingProvider: 'campflare', bookingProviderRef: 'cf', bookingAliases: [] };
const tenants = shippedTenantRegistry();
const NAMED = { provider: 'recgov', provider_display: 'Recreation.gov' };

function readerPorts(overrides: Partial<ReaderPorts> = {}): ReaderPorts {
  return {
    signInConfigured: vi.fn().mockReturnValue(true),
    currentUser: vi.fn().mockResolvedValue(READER),
    vault: memoryVault(STORED).vault,
    ...overrides,
  };
}

test('a credentialed reader on an all-rec.gov matrix is ready', () => {
  expect(capabilityDto(addToCartCapability([RECGOV_SITE, RECGOV_SITE], RECGOV_CAMPGROUND, 'ready', tenants))).toEqual({ state: 'ready', ...NAMED });
});

test('a reader without credentials is no_credentials', () => {
  expect(capabilityDto(addToCartCapability([RECGOV_SITE], RECGOV_CAMPGROUND, 'no_credentials', tenants))).toEqual({ state: 'no_credentials', ...NAMED });
});

test('no reader is signed_out', () => {
  expect(capabilityDto(addToCartCapability([RECGOV_SITE], RECGOV_CAMPGROUND, 'signed_out', tenants))).toEqual({ state: 'signed_out', ...NAMED });
});

test('one site with no rec.gov target makes the matrix unsupported, before signed_out', () => {
  expect(capabilityDto(addToCartCapability([RECGOV_SITE, CAMPFLARE_SITE], RECGOV_CAMPGROUND, 'signed_out', tenants))).toEqual({ state: 'unsupported' });
});

test('an empty matrix is unsupported', () => {
  expect(capabilityDto(addToCartCapability([], RECGOV_CAMPGROUND, 'ready', tenants))).toEqual({ state: 'unsupported' });
});

test('with sign-in off the matrix is unsupported, before signed_out', () => {
  expect(capabilityDto(addToCartCapability([RECGOV_SITE], RECGOV_CAMPGROUND, 'sign_in_off', tenants))).toEqual({ state: 'unsupported' });
});

test('with sign-in off no user is looked up', async () => {
  const ports = readerPorts({ signInConfigured: vi.fn().mockReturnValue(false) });
  expect(await readerStanding(ports)).toBe('sign_in_off');
  expect(ports.currentUser).not.toHaveBeenCalled();
});

test("a signed-out reader's vault is not read", async () => {
  const { vault } = memoryVault(STORED);
  const status = vi.spyOn(vault, 'status');
  expect(await readerStanding(readerPorts({ currentUser: vi.fn().mockResolvedValue(null), vault }))).toBe('signed_out');
  expect(status).not.toHaveBeenCalled();
});

test('stored credentials are ready', async () => {
  expect(await readerStanding(readerPorts({ vault: memoryVault(STORED).vault }))).toBe('ready');
});

test.each([
  ['no row', memoryVault(null).vault],
  ['a row with no key', memoryVault(STORED, false).vault],
  ['a row sealed under another key', memoryVault(STORED, 'key_changed').vault],
])('no row, or a row with no key, is no_credentials (%s)', async (_name, vault) => {
  expect(await readerStanding(readerPorts({ vault }))).toBe('no_credentials');
});
