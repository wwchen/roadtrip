import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import type { EgressResult } from '../../client/availabilityEgress';
import { campsiteIdsOf, currentCells } from '../../repo/availabilityReadRepo';
import { recordObservations } from '../../repo/availabilityWriteRepo';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, NEAR_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { addDays, poiDateContext } from '../booking/dateContext';
import type { WeekSettings } from './availabilityMatrixService';
import { planRefresh, runRefresh } from './availabilityRefreshService';

const NOW = new Date('2026-11-05T12:00:00Z');
const MINI_CA_OBSERVED = '2026-11-05T09:30:00.000Z';
const SETTINGS: WeekSettings = { ttlSeconds: 7200, enabledAvailabilityProviders: ['campflare'], now: NOW };

let fixture: CatalogFixture;
let siteIds: bigint[];
let start: string;
let end: string;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  siteIds = await campsiteIdsOf(fixture.writeDb, NEAR_CAMPGROUND_ID, []);
  const [row] = await fixture.writeDb.execute<{ lat: number; lng: number }>(
    sql`SELECT ST_Y(ST_PointOnSurface(geom)) AS lat, ST_X(ST_PointOnSurface(geom)) AS lng FROM pois WHERE id = ${NEAR_POI_ID}`,
  );
  start = poiDateContext(Number(row!.lat), Number(row!.lng), NOW).earliestDate;
  end = addDays(start, 1);
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.execute(sql`DELETE FROM availability`);
  await fixture.writeDb.execute(
    sql`UPDATE campgrounds SET booking_provider = 'campflare', booking_provider_ref = 'near' WHERE id = ${NEAR_CAMPGROUND_ID}`,
  );
});

const cellsFor = (refs: string[]) => refs.map((ref) => ({ campsite: { data_provider: ENABLED_PROVIDER, data_provider_ref: ref }, date: start, status: 'available' as const, observed_at: MINI_CA_OBSERVED }));
const answer = (refs: string[]): EgressResult => ({ kind: 'cells', body: { start_date: start, end_date: end, cells: cellsFor(refs) } });

async function stalePlan() {
  const plan = await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, SETTINGS, true);
  if (plan.kind !== 'fetch') throw new Error(`expected a fetch plan, got ${plan.kind}`);
  return plan;
}

test('a stale window plans a fetch with the campground provider pair', async () => {
  const plan = await stalePlan();
  expect(plan.request).toEqual({ campground: { data_provider: ENABLED_PROVIDER, data_provider_ref: `ref-${NEAR_CAMPGROUND_ID}` }, start_date: start, end_date: end });
});

test('a fresh window plans fresh', async () => {
  await recordObservations(fixture.writeDb, siteIds.map((campsiteId) => ({ campsiteId, date: start, status: 'available', observedAt: NOW })));
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, SETTINGS, true)).kind).toBe('fresh');
});

test('refresh unconfigured plans disabled and an unclaimed campground plans unsupported', async () => {
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, SETTINGS, false)).kind).toBe('disabled');
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, { ...SETTINGS, enabledAvailabilityProviders: [] }, true)).kind).toBe('unsupported');
});

test("runRefresh writes cells with mini-ca's observed_at", async () => {
  const plan = await stalePlan();
  const outcome = await runRefresh(plan, { db: fixture.writeDb, fetch: async () => answer(['site-0', 'site-1', 'site-2']) });
  expect(outcome).toEqual({ written: 3, dropped: 0, filled: 0 });
  const cells = await currentCells(fixture.readDb, siteIds, start, end);
  expect(cells.map((cell) => cell.lastObservedAt.toISOString())).toEqual([MINI_CA_OBSERVED, MINI_CA_OBSERVED, MINI_CA_OBSERVED]);
});

test('runRefresh drops and counts pairs Neon lacks', async () => {
  const plan = await stalePlan();
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  expect(await runRefresh(plan, { db: fixture.writeDb, fetch: async () => answer(['site-0', 'site-1', 'site-2', 'not-in-neon']) })).toEqual({
    written: 3,
    dropped: 1,
    filled: 0,
  });
  log.mockRestore();
});

test('campsites mini-ca did not answer are stored unknown, so the week becomes fresh', async () => {
  const plan = await stalePlan();
  const outcome = await runRefresh(plan, { db: fixture.writeDb, fetch: async () => answer(['site-0']) });
  expect(outcome).toEqual({ written: 1, dropped: 0, filled: 2 });
  const cells = await currentCells(fixture.readDb, siteIds, start, end);
  expect(cells.map((cell) => cell.status).sort()).toEqual(['available', 'unknown', 'unknown']);
  expect(cells.every((cell) => cell.lastObservedAt.toISOString() === MINI_CA_OBSERVED)).toBe(true);
  const later = { ...SETTINGS, now: new Date(Date.parse(MINI_CA_OBSERVED) + 60_000) };
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, later, true)).kind).toBe('fresh');
});

test.each([
  [{ kind: 'error', status: 0, code: 'timeout' }, 'timeout'],
  [{ kind: 'error', status: 404, code: 'not_found' }, 'not_found'],
] as const)('runRefresh on %j writes nothing', async (result, code) => {
  const plan = await stalePlan();
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  expect(await runRefresh(plan, { db: fixture.writeDb, fetch: async () => result })).toEqual({ failed: code });
  expect(await currentCells(fixture.readDb, siteIds, start, end)).toEqual([]);
  expect(log).toHaveBeenCalled();
  log.mockRestore();
});
