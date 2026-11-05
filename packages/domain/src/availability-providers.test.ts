import { expect, test } from 'vitest';
import { AVAILABILITY_PROVIDERS, servingAvailabilityProvider } from './availability-providers.js';
import { bookingIdentities } from './booking-identities.js';
import type { BookingAlias } from './campground.js';
import { shippedTenantRegistry } from './booking-tenants.js';

const ALL_ENABLED = AVAILABILITY_PROVIDERS.map((provider) => provider.id);
const tenants = shippedTenantRegistry();
const serving = (provider: string, ref: string, enabled: readonly string[] = ALL_ENABLED, aliases: BookingAlias[] = []) =>
  servingAvailabilityProvider(bookingIdentities(provider, ref, aliases), enabled, tenants)?.id ?? null;

test('the table lists each provider once, in Kotlin registration order, with its window limits', () => {
  expect(AVAILABILITY_PROVIDERS).toEqual([
    { id: 'recgov', bookingHorizonDays: 180, maxPollWindowDays: 90 },
    { id: 'campflare', bookingHorizonDays: 365, maxPollWindowDays: 60 },
    { id: 'reservecalifornia', bookingHorizonDays: 183, maxPollWindowDays: 30 },
    { id: 'aspira', bookingHorizonDays: 365, maxPollWindowDays: 30 },
    { id: 'reserveamerica', bookingHorizonDays: 270, maxPollWindowDays: 30 },
  ]);
});

test.each([
  ['recgov', '232447', 'recgov'],
  ['campflare', 'mahood', 'campflare'],
  ['reservecalifornia', '718:1,2', 'reservecalifornia'],
  ['aspira', 'bc:1:2:null', 'aspira'],
  ['reserveamerica', 'NY:123', 'reserveamerica'],
])('serving provider follows each Kotlin claim: %s', (provider, ref, expected) => {
  expect(serving(provider, ref)).toBe(expected);
});

test('an alias claims for its provider, and registration order breaks ties', () => {
  expect(serving('campflare', 'mahood', ALL_ENABLED, [{ provider: 'recgov', ref: '232450' }])).toBe('recgov');
  expect(serving('campflare', 'mahood', ['campflare'], [{ provider: 'recgov', ref: '232450' }])).toBe('campflare');
});

test('a disabled provider claims nothing', () => {
  expect(serving('aspira', 'bc:1:2:null', ['campflare'])).toBeNull();
});

test('aspira and reserveamerica claim only tenants the registry knows', () => {
  expect(serving('aspira', 'zz:1:2:null')).toBeNull();
  expect(serving('reserveamerica', 'ZZ:123')).toBeNull();
});

test('an unclaimed campground has no serving provider', () => {
  expect(servingAvailabilityProvider([], ALL_ENABLED, tenants)).toBeNull();
});
