import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import type { EgressResult } from '../../client/availabilityEgress';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, NEAR_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { addDays, poiDateContext } from '../booking/dateContext';
import type { WeekSettings } from './availabilityMatrixService';
import { planRefresh, runRefresh } from './availabilityRefreshService';

const NOW = new Date('2026-11-05T12:00:00Z');
const SETTINGS: WeekSettings = { ttlSeconds: 7200, enabledAvailabilityProviders: ['campflare'], now: NOW };

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
  const plan = await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, SETTINGS, true);
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

test('refresh unconfigured plans disabled and an unclaimed campground plans unsupported', async () => {
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, SETTINGS, false)).kind).toBe('disabled');
  expect((await planRefresh(fixture.readDb, NEAR_POI_ID, start, end, { ...SETTINGS, enabledAvailabilityProviders: [] }, true)).kind).toBe(
    'unsupported',
  );
});

test('runRefresh asks mini-ca and writes nothing anywhere', async () => {
  const plan = await fetchPlan();
  const info = vi.spyOn(console, 'info').mockImplementation(() => {});
  const fetch = vi.fn(async (): Promise<EgressResult> => ({ kind: 'cells', body: { start_date: start, end_date: end, cells: [] } }));
  expect(await runRefresh(plan, fetch)).toEqual({ cells: 0 });
  expect(fetch).toHaveBeenCalledWith(plan.request);
  info.mockRestore();
});

test.each([
  [{ kind: 'error', status: 0, code: 'timeout' }, 'timeout'],
  [{ kind: 'error', status: 404, code: 'not_found' }, 'not_found'],
  [{ kind: 'error', status: 502, code: null }, 'unclassified'],
] as const)('runRefresh on %j reports and logs the failure', async (result, code) => {
  const plan = await fetchPlan();
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  expect(await runRefresh(plan, async () => result)).toEqual({ failed: code });
  expect(log).toHaveBeenCalled();
  log.mockRestore();
});
