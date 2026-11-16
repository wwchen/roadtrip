import { campgrounds, campsites } from '@roadtrip/db';
import { eq } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { NEAR_CAMPGROUND_ID, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { campgroundBookingIdentities } from './availabilityReadRepo';

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

test('reads the campground identity and each named campsite identity', async () => {
  await fixture.writeDb.update(campgrounds).set({ bookingProvider: 'recgov', bookingProviderRef: '232447' }).where(eq(campgrounds.id, NEAR_CAMPGROUND_ID));
  const sites = await fixture.writeDb.select({ id: campsites.id }).from(campsites).where(eq(campsites.campgroundId, NEAR_CAMPGROUND_ID));
  const [first] = sites;
  await fixture.writeDb.update(campsites).set({ bookingAliases: [{ provider: 'recgov', ref: '300' }] }).where(eq(campsites.id, first!.id));

  const identities = await campgroundBookingIdentities(fixture.readDb, NEAR_CAMPGROUND_ID, [first!.id]);

  expect(identities.campground).toEqual({ bookingProvider: 'recgov', bookingProviderRef: '232447', bookingAliases: [] });
  expect(identities.campsites).toEqual([{ bookingProvider: null, bookingProviderRef: null, bookingAliases: [{ provider: 'recgov', ref: '300' }] }]);
});
