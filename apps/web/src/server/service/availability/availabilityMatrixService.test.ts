import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import type { EgressResult } from '../../client/availabilityEgress';
import type { AvailabilityEgressRequestDto } from '../../contract';
import { campsitesOf, type CampsiteKey } from '../../repo/availabilityReadRepo';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, NEAR_POI_ID, UNKNOWN_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { addDays, poiDateContext } from '../booking/dateContext';
import {
  AvailabilityTargetError,
  AvailabilityUnavailableError,
  AvailabilityWindowRefusedError,
  availabilityWeek,
  type EgressFetch,
  type WeekSettings,
} from './availabilityMatrixService';

const NOW = new Date('2026-11-05T12:00:00Z');
const TTL = 7200;
const SETTINGS: WeekSettings = { ttlSeconds: TTL, now: NOW };
const LATEST = '2027-11-05';
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
function storedAnswer(
  status: 'available' | 'closed',
  at: (index: number, date: string) => Date = () => NOW,
  known: (index: number) => boolean = () => true,
): EgressFetch {
  return async (_request: AvailabilityEgressRequestDto): Promise<EgressResult> => {
    const known$ = sites.map((_, index) => index).filter(known);
    return {
      kind: 'cells',
      body: {
        start_date: start,
        end_date: end,
        latest_date: LATEST,
        from_cache: true,
        campsites: known$.map((index) => ({ data_provider: ENABLED_PROVIDER, data_provider_ref: refOf(index) })),
        cells: known$.flatMap((index) =>
          [start, addDays(start, 1)].map((date) => ({
            campsite: { data_provider: ENABLED_PROVIDER, data_provider_ref: refOf(index) },
            date,
            status,
            observed_at: at(index, date).toISOString(),
          })),
        ),
      },
    };
  };
}
const failing = (status: number, code: string | null, body: object | null = null): EgressFetch => async () => ({ kind: 'error', status, code, body } as EgressResult);
const week = (fetch: EgressFetch | null, siteTypes: ('rv' | 'cabin')[] = []) =>
  availabilityWeek(fixture.readDb, NEAR_POI_ID, siteTypes, start, end, SETTINGS, fetch);

test('the matrix asks mini-ca for stored cells of its campground and the requested window', async () => {
  const fetch = vi.fn(storedAnswer('available'));
  await week(fetch);
  expect(fetch).toHaveBeenCalledWith({
    mode: 'stored',
    campground: { data_provider: ENABLED_PROVIDER, data_provider_ref: `ref-${NEAR_CAMPGROUND_ID}` },
    start_date: start,
    end_date: end,
  });
  await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], null, null, SETTINGS, fetch);
  expect(fetch).toHaveBeenLastCalledWith({ mode: 'stored', campground: expect.anything() });
});

test('a fully fresh week reports cache hit with the stalest cell age and the serving horizon', async () => {
  const result = await week(storedAnswer('available'));
  expect(result.state).toBe('success');
  expect(result.cache).toEqual({ hit: true, age_seconds: 0, ttl_seconds: TTL });
  expect(result.days).toHaveLength(2);
  expect(result.days[0]!.status).toBe('available');
  expect(result.latest_date).toBe(LATEST);
  expect(result.start_date).toBe(start);
  expect(result.watch_capabilities).toEqual({ trigger_kinds: [], add_to_cart: { state: 'unsupported' } });
});

test('one stale cell makes the week stale and age_seconds is that cell age', async () => {
  const result = await week(storedAnswer('available', (index, date) => (index === 1 && date === start ? STALE : NOW)));
  expect(result.cache?.age_seconds).toBe(3 * 3600);
});

test('a campsite mini-ca does not know shows unknown and does not keep the week stale', async () => {
  const result = await week(storedAnswer('available', () => NOW, (index) => index !== 2));
  expect(result.cache).toEqual({ hit: true, age_seconds: 0, ttl_seconds: TTL });
  const lastSite = String(sites[2]!.id);
  expect(result.days[0]!.cells[lastSite]?.status).toBe('unknown');
});

test('when mini-ca knows none of the campsites the week is empty', async () => {
  const result = await week(storedAnswer('available', () => NOW, () => false));
  expect(result.state).toBe('empty');
  expect(result.days).toEqual([]);
});

test('an unconfigured egress is unavailable', async () => {
  await expect(week(null)).rejects.toBeInstanceOf(AvailabilityUnavailableError);
});

test('an unconfigured egress still answers an empty week for a campground with no matching campsites', async () => {
  const result = await week(null, ['cabin']);
  expect(result.state).toBe('empty');
  expect(result.days).toEqual([]);
  expect([result.start_date, result.end_date]).toEqual([start, end]);
});

test.each([
  [0, 'timeout'],
  [302, 'egress_redirected'],
  [200, 'egress_not_json'],
  [503, 'rate_limited'],
])('a mini-ca answer of %s %s is unavailable and logged', async (status, code) => {
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  await expect(week(failing(status, code))).rejects.toBeInstanceOf(AvailabilityUnavailableError);
  expect(log).toHaveBeenCalled();
  log.mockRestore();
});

test('a campground mini-ca does not know is an empty week for the requested window, logged as drift', async () => {
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const result = await week(failing(404, 'not_found'));
  expect(result.state).toBe('empty');
  expect(result.days).toEqual([]);
  expect([result.start_date, result.end_date]).toEqual([start, end]);
  expect(log).toHaveBeenCalled();
  log.mockRestore();
});

test('with no dates requested, an unknown campground is an empty week from its earliest date', async () => {
  const log = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const result = await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], null, null, SETTINGS, failing(404, 'not_found'));
  expect([result.start_date, result.end_date]).toEqual([start, addDays(start, 7)]);
  log.mockRestore();
});

test('a window mini-ca refuses passes its error through', async () => {
  const body = { state: 'error', error: 'window_too_long', max_days: 30 };
  await expect(week(failing(400, 'window_too_long', body))).rejects.toMatchObject(new AvailabilityWindowRefusedError(body));
});

test('a campground mini-ca will not serve is unknown_campground', async () => {
  await expect(week(failing(501, 'unknown_campground'))).rejects.toMatchObject(new AvailabilityTargetError('unknown_campground'));
});

test('all campsites closed is closed_for_season with no days', async () => {
  const result = await week(storedAnswer('closed'));
  expect(result.state).toBe('closed_for_season');
  expect(result.days).toEqual([]);
});

test('campsites mini-ca does not know do not keep the others from closed_for_season', async () => {
  const result = await week(storedAnswer('closed', () => NOW, (index) => index !== 2));
  expect(result.state).toBe('closed_for_season');
  expect(result.days).toEqual([]);
});

test('site_type filters campsites and no matching campsite is empty', async () => {
  const rv = await week(storedAnswer('available'), ['rv']);
  expect(Object.keys(rv.days[0]!.cells)).toHaveLength(1);
  const cabins = await week(storedAnswer('available'), ['cabin']);
  expect(cabins.state).toBe('empty');
  expect(cabins.latest_date).toBe(LATEST);
});

test('an unknown POI is not_found', async () => {
  await expect(availabilityWeek(fixture.readDb, UNKNOWN_POI_ID, [], start, end, SETTINGS, null)).rejects.toMatchObject(
    new AvailabilityTargetError('not_found'),
  );
});

test('the matrix carries the capability the reader-aware lookup answers for its campsites', async () => {
  const capabilities = vi.fn().mockResolvedValue({ trigger_kinds: [], add_to_cart: { state: 'ready', provider: 'recgov', provider_display: 'Recreation.gov' } });
  const result = await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], start, end, SETTINGS, storedAnswer('available'), capabilities);
  expect(result.watch_capabilities.add_to_cart.state).toBe('ready');
  const [target] = capabilities.mock.calls[0]!;
  expect(target.campground.campgroundId).toBe(NEAR_CAMPGROUND_ID);
  expect(target.campsites.length).toBeGreaterThan(0);
});
