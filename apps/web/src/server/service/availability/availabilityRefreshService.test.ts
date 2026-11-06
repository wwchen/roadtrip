import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import type { EgressResult } from '../../client/availabilityEgress';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, NEAR_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { addDays, poiDateContext } from '../booking/dateContext';
import { planRefresh, runRefresh } from './availabilityRefreshService';

const NOW = new Date('2026-11-05T12:00:00Z');

let fixture: CatalogFixture;
let start: string;
let end: string;

beforeAll(async () => {
  fixture = await startCatalogFixture();
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
  await fixture.writeDb.execute(
    sql`UPDATE campgrounds SET booking_provider = 'campflare', booking_provider_ref = 'near' WHERE id = ${NEAR_CAMPGROUND_ID}`,
  );
});

async function fetchPlan() {
  const plan = await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, true);
  if (plan.kind !== 'fetch') throw new Error(`expected a fetch plan, got ${plan.kind}`);
  return plan;
}

test('a refresh plans a refresh-mode request with the campground provider pair', async () => {
  expect((await fetchPlan()).request).toEqual({
    mode: 'refresh',
    campground: { data_provider: ENABLED_PROVIDER, data_provider_ref: `ref-${NEAR_CAMPGROUND_ID}` },
    start_date: start,
    end_date: end,
  });
});

test('refresh unconfigured plans disabled', async () => {
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, false)).kind).toBe('disabled');
});

test('a refresh passes the requested window to mini-ca as given, which resolves it', async () => {
  const plan = await planRefresh(fixture.readDb, NEAR_POI_ID, null, null, true);
  expect(plan).toEqual({ kind: 'fetch', request: { mode: 'refresh', campground: expect.anything() } });
});

test('runRefresh asks mini-ca and writes nothing anywhere', async () => {
  const plan = await fetchPlan();
  const info = vi.spyOn(console, 'info').mockImplementation(() => {});
  const fetch = vi.fn(async (): Promise<EgressResult> => ({ kind: 'cells', body: { start_date: start, end_date: end, campsites: [], cells: [] } }));
  expect(await runRefresh(plan, fetch)).toEqual({ cells: 0 });
  expect(fetch).toHaveBeenCalledWith(plan.request);
  info.mockRestore();
});

test.each([
  [{ kind: 'error', status: 0, code: 'timeout', body: null }, 'timeout'],
  [{ kind: 'error', status: 404, code: 'not_found', body: null }, 'not_found'],
  [{ kind: 'error', status: 502, code: null, body: null }, 'unclassified'],
] as const)('runRefresh on %j reports and logs the failure', async (result, code) => {
  const plan = await fetchPlan();
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  expect(await runRefresh(plan, async () => result)).toEqual({ failed: code });
  expect(log).toHaveBeenCalled();
  log.mockRestore();
});
