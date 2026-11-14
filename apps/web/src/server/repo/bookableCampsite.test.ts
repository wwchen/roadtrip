import { campgrounds, campsites } from '@roadtrip/db';
import { eq } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import { NEAR_CAMPGROUND_ID, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { findBookableCampsite } from './campsiteCatalogRepo';

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

test('a campsite reads with its own and its campground identities', async () => {
  await fixture.writeDb
    .update(campgrounds)
    .set({ bookingAliases: [{ provider: 'recgov', ref: '232447' }] })
    .where(eq(campgrounds.id, NEAR_CAMPGROUND_ID));
  const [site] = await fixture.writeDb
    .insert(campsites)
    .values({
      campgroundId: NEAR_CAMPGROUND_ID,
      dataProvider: 'campflare',
      dataProviderRef: 'bookable-1',
      bookingAliases: [{ provider: 'recgov', ref: '301' }],
      name: 'Bookable',
      kind: 'tent',
    })
    .returning({ id: campsites.id });

  const found = await findBookableCampsite(fixture.readDb, site!.id);

  expect(found).toMatchObject({
    id: site!.id,
    dataProvider: 'campflare',
    dataProviderRef: 'bookable-1',
    bookingAliases: [{ provider: 'recgov', ref: '301' }],
    campground: { dataProvider: 'campflare', dataProviderRef: `ref-${NEAR_CAMPGROUND_ID}`, bookingAliases: [{ provider: 'recgov', ref: '232447' }] },
  });
});

test('an unknown campsite reads as null', async () => {
  expect(await findBookableCampsite(fixture.readDb, 987654321n)).toBeNull();
});
