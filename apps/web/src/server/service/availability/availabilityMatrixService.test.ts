import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import type { EgressResult } from '../../client/availabilityEgress';
import type { AvailabilityEgressRequestDto } from '../../contract';
import { campsitesOf, type CampsiteKey } from '../../repo/availabilityReadRepo';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, NEAR_POI_ID, UNKNOWN_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { addDays, poiDateContext } from '../booking/dateContext';
import { AvailabilityTargetError, availabilityWeek, type EgressFetch, type WeekSettings } from './availabilityMatrixService';

const NOW = new Date('2026-11-05T12:00:00Z');
const TTL = 7200;
const SETTINGS: WeekSettings = { ttlSeconds: TTL, enabledAvailabilityProviders: ['campflare'], now: NOW };
const STALE = new Date(NOW.getTime() - 3 * 3_600_000);

let fixture: CatalogFixture;
let sites: CampsiteKey[];
let start: string;
let end: string;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  sites = await campsitesOf(fixture.writeDb, NEAR_CAMPGROUND_ID, []);
  const [row] = await fixture.writeDb.execute<{ lat: number; lng: number }>(
    sql`SELECT ST_Y(ST_PointOnSurface(geom)) AS lat, ST_X(ST_PointOnSurface(geom)) AS lng FROM pois WHERE id = ${NEAR_POI_ID}`,
  );
  start = poiDateContext(Number(row!.lat), Number(row!.lng), NOW).earliestDate;
  end = addDays(start, 2);
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.execute(
    sql`UPDATE campgrounds SET booking_provider = 'campflare', booking_provider_ref = 'near' WHERE id = ${NEAR_CAMPGROUND_ID}`,
  );
});

const refOf = (index: number) => `site-${index}`;
function storedAnswer(status: 'available' | 'closed', at: (index: number, date: string) => Date = () => NOW): EgressFetch {
  return async (request: AvailabilityEgressRequestDto): Promise<EgressResult> => ({
    kind: 'cells',
    body: {
      start_date: request.start_date,
      end_date: request.end_date,
      cells: sites.flatMap((_, index) =>
        [request.start_date, addDays(request.start_date, 1)].map((date) => ({
          campsite: { data_provider: ENABLED_PROVIDER, data_provider_ref: refOf(index) },
          date,
          status,
          observed_at: at(index, date).toISOString(),
        })),
      ),
    },
  });
}
const week = (fetch: EgressFetch | null, siteTypes: ('rv' | 'cabin')[] = [], settings = SETTINGS) =>
  availabilityWeek(fixture.readDb, NEAR_POI_ID, siteTypes, start, end, settings, fetch);

test('the matrix asks mini-ca for stored cells of its campground and window', async () => {
  const fetch = vi.fn(storedAnswer('available'));
  await week(fetch);
  expect(fetch).toHaveBeenCalledWith({
    mode: 'stored',
    campground: { data_provider: ENABLED_PROVIDER, data_provider_ref: `ref-${NEAR_CAMPGROUND_ID}` },
    start_date: start,
    end_date: end,
  });
});

test('a fully fresh week reports cache hit with the stalest cell age and the serving horizon', async () => {
  const result = await week(storedAnswer('available'));
  expect(result.state).toBe('success');
  expect(result.cache).toEqual({ hit: true, age_seconds: 0, ttl_seconds: TTL });
  expect(result.days).toHaveLength(2);
  expect(result.days[0]!.status).toBe('available');
  expect(result.latest_date).toBe(addDays(start, 365));
  expect(result.watch_capabilities).toEqual({ trigger_kinds: [], add_to_cart: { state: 'unsupported' } });
});

test('one stale cell makes the week stale and age_seconds is that cell age', async () => {
  const result = await week(storedAnswer('available', (index, date) => (index === 1 && date === start ? STALE : NOW)));
  expect(result.cache?.age_seconds).toBe(3 * 3600);
});

test.each([
  ['unconfigured', null],
  ['failing', async (): Promise<EgressResult> => ({ kind: 'error', status: 0, code: 'timeout' })],
] as const)('an %s mini-ca leaves unknown cells and no cache', async (_label, fetch) => {
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const result = await week(fetch);
  expect(result.cache).toBeUndefined();
  expect(result.days.every((day) => day.status === 'unknown')).toBe(true);
  log.mockRestore();
});

test('all campsites closed is closed_for_season with no days', async () => {
  const result = await week(storedAnswer('closed'));
  expect(result.state).toBe('closed_for_season');
  expect(result.days).toEqual([]);
});

test('site_type filters campsites and no matching campsite is empty', async () => {
  const rv = await week(storedAnswer('available'), ['rv']);
  expect(Object.keys(rv.days[0]!.cells)).toHaveLength(1);
  const cabins = await week(storedAnswer('available'), ['cabin']);
  expect(cabins.state).toBe('empty');
});

test('an unclaimed campground is unknown_campground and an unknown POI is not_found', async () => {
  await expect(week(storedAnswer('available'), [], { ...SETTINGS, enabledAvailabilityProviders: [] })).rejects.toMatchObject(
    new AvailabilityTargetError('unknown_campground'),
  );
  await expect(availabilityWeek(fixture.readDb, UNKNOWN_POI_ID, [], start, end, SETTINGS, null)).rejects.toMatchObject(
    new AvailabilityTargetError('not_found'),
  );
});
