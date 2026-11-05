import { campsites } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { campsiteIdsByProviderRef, currentCells, providerRefKey } from './availabilityReadRepo';
import { recordObservations, type Observation } from './availabilityWriteRepo';

const DAY_ONE = '2026-12-01';
const DAY_TWO = '2026-12-02';
const DAY_THREE = '2026-12-03';
const EARLY = new Date('2026-11-05T10:00:00Z');
const LATER = new Date('2026-11-05T11:00:00Z');
const LATEST = new Date('2026-11-05T12:00:00Z');
const DELETED_REF = 'deleted-site';

let fixture: CatalogFixture;
let siteIds: bigint[];

beforeAll(async () => {
  fixture = await startCatalogFixture();
  await fixture.writeDb.insert(campsites).values({
    campgroundId: NEAR_CAMPGROUND_ID,
    dataProvider: ENABLED_PROVIDER,
    dataProviderRef: DELETED_REF,
    name: 'Gone',
    kind: 'tent',
    deletedAt: new Date(),
  });
  const ids = await campsiteIdsByProviderRef(fixture.writeDb, NEAR_CAMPGROUND_ID);
  siteIds = [...ids.values()].sort((a, b) => (a < b ? -1 : 1));
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.execute(sql`DELETE FROM availability`);
});

const rows = async () =>
  fixture.writeDb.execute<{ campsite_id: string; target_date: string; status: string; last_observed_at: Date; previous_id: string | null }>(
    sql`SELECT campsite_id, target_date::text AS target_date, status::text AS status, last_observed_at, previous_id FROM availability ORDER BY id`,
  );

const observe = (campsiteId: bigint, date: string, status: Observation['status'], observedAt: Date): Observation => ({
  campsiteId,
  date,
  status,
  observedAt,
});

test('campsiteIdsByProviderRef keys live campsites by provider pair and skips deleted ones', async () => {
  const ids = await campsiteIdsByProviderRef(fixture.writeDb, NEAR_CAMPGROUND_ID);
  expect(ids.has(providerRefKey(ENABLED_PROVIDER, 'site-0'))).toBe(true);
  expect(ids.has(providerRefKey(ENABLED_PROVIDER, DELETED_REF))).toBe(false);
});

test('an unchanged status bumps last_observed_at in place', async () => {
  const site = siteIds[0]!;
  await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', EARLY)]);
  const inserted = await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', LATER)]);
  const stored = await rows();
  expect(inserted).toBe(0);
  expect(stored).toHaveLength(1);
  expect(new Date(stored[0]!.last_observed_at).toISOString()).toBe(LATER.toISOString());
});

test('a changed status inserts a row linked by previous_id', async () => {
  const site = siteIds[0]!;
  await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', EARLY)]);
  const inserted = await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'reserved', LATER)]);
  const stored = await rows();
  expect(inserted).toBe(1);
  expect(stored).toHaveLength(2);
  expect(stored[1]!.status).toBe('reserved');
  expect(stored[1]!.previous_id).not.toBeNull();
});

test('an older observation never moves last_observed_at backwards', async () => {
  const site = siteIds[0]!;
  await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', LATER)]);
  await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', EARLY)]);
  expect(new Date((await rows())[0]!.last_observed_at).toISOString()).toBe(LATER.toISOString());
});

test('an older changed observation never displaces a newer current row', async () => {
  const site = siteIds[0]!;
  await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', LATER)]);
  expect(await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'reserved', EARLY)])).toBe(0);
  expect(await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'reserved', LATEST)])).toBe(1);
  const [current] = await currentCells(fixture.writeDb, [site], DAY_ONE, DAY_TWO);
  expect(current?.status).toBe('reserved');
  expect(current?.lastObservedAt.toISOString()).toBe(LATEST.toISOString());
});

test('the latest observation per cell wins within one batch', async () => {
  const site = siteIds[0]!;
  await recordObservations(fixture.writeDb, [
    observe(site, DAY_ONE, 'reserved', LATEST),
    observe(site, DAY_ONE, 'available', EARLY),
  ]);
  const stored = await rows();
  expect(stored).toHaveLength(1);
  expect(stored[0]!.status).toBe('reserved');
});

test('two concurrent writes of the same change leave one new row', async () => {
  const site = siteIds[0]!;
  await recordObservations(fixture.writeDb, [observe(site, DAY_ONE, 'available', EARLY)]);
  const change = [observe(site, DAY_ONE, 'reserved', LATER)];
  await Promise.all([recordObservations(fixture.writeDb, change), recordObservations(fixture.writeDb, change)]);
  expect((await rows()).filter((row) => row.status === 'reserved')).toHaveLength(1);
});

test('currentCells returns the newest row per cell inside the window only', async () => {
  const [first, second] = siteIds;
  await recordObservations(fixture.writeDb, [
    observe(first!, DAY_ONE, 'available', EARLY),
    observe(first!, DAY_TWO, 'available', EARLY),
    observe(second!, DAY_ONE, 'available', EARLY),
    observe(first!, DAY_THREE, 'available', EARLY),
  ]);
  await recordObservations(fixture.writeDb, [observe(first!, DAY_ONE, 'reserved', LATER)]);
  const cells = await currentCells(fixture.writeDb, [first!, second!], DAY_ONE, DAY_THREE);
  expect(cells).toHaveLength(3);
  const firstDayOne = cells.find((cell) => cell.campsiteId === first && cell.date === DAY_ONE);
  expect(firstDayOne?.status).toBe('reserved');
  expect(firstDayOne?.lastObservedAt.toISOString()).toBe(LATER.toISOString());
  expect(cells.some((cell) => cell.date === DAY_THREE)).toBe(false);
});

test('an empty campsite list reads no cells', async () => {
  expect(await currentCells(fixture.writeDb, [], DAY_ONE, DAY_THREE)).toEqual([]);
  expect(await recordObservations(fixture.writeDb, [])).toBe(0);
});
