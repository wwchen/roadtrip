import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { campsiteIdsOf } from '../../repo/availabilityReadRepo';
import { recordObservations } from '../../repo/availabilityWriteRepo';
import { NEAR_CAMPGROUND_ID, NEAR_POI_ID, UNKNOWN_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import { addDays, poiDateContext } from '../booking/dateContext';
import { AvailabilityTargetError, availabilityWeek, type WeekSettings } from './availabilityMatrixService';

const NOW = new Date('2026-11-05T12:00:00Z');
const TTL = 7200;
const SETTINGS: WeekSettings = { ttlSeconds: TTL, enabledAvailabilityProviders: ['campflare'], now: NOW };
const STALE = new Date(NOW.getTime() - 3 * 3_600_000);

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
  end = addDays(start, 2);
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

const observeAll = (at: Date, status: 'available' | 'closed' = 'available') =>
  recordObservations(
    fixture.writeDb,
    siteIds.flatMap((campsiteId) => [start, addDays(start, 1)].map((date) => ({ campsiteId, date, status, observedAt: at }))),
  );

test('a fully fresh week reports cache hit with the stalest cell age and the serving horizon', async () => {
  await observeAll(NOW);
  const week = await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], start, end, SETTINGS);
  expect(week.state).toBe('success');
  expect(week.cache).toEqual({ hit: true, age_seconds: 0, ttl_seconds: TTL });
  expect(week.days).toHaveLength(2);
  expect(week.latest_date).toBe(addDays(start, 365));
  expect(week.watch_capabilities).toEqual({ trigger_kinds: [], add_to_cart: { state: 'unsupported' } });
});

test('one stale cell makes the week stale and age_seconds is that cell age', async () => {
  await observeAll(NOW);
  await recordObservations(fixture.writeDb, [{ campsiteId: siteIds[0]!, date: start, status: 'reserved', observedAt: STALE }]);
  await fixture.writeDb.execute(sql`UPDATE availability SET last_observed_at = ${STALE.toISOString()}::timestamptz WHERE campsite_id = ${siteIds[1]!} AND target_date = ${start}::date`);
  const week = await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], start, end, SETTINGS);
  expect(week.cache?.age_seconds).toBe(3 * 3600);
});

test('a week with no observations has unknown cells and cache null', async () => {
  const week = await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], start, end, SETTINGS);
  expect(week.cache).toBeUndefined();
  expect(week.days.every((day) => day.status === 'unknown')).toBe(true);
});

test('all campsites closed is closed_for_season with no days', async () => {
  await observeAll(NOW, 'closed');
  const week = await availabilityWeek(fixture.readDb, NEAR_POI_ID, [], start, end, SETTINGS);
  expect(week.state).toBe('closed_for_season');
  expect(week.days).toEqual([]);
});

test('site_type filters campsites and no matching campsite is empty', async () => {
  const rv = await availabilityWeek(fixture.readDb, NEAR_POI_ID, ['rv'], start, end, SETTINGS);
  expect(Object.keys(rv.days[0]!.cells)).toHaveLength(1);
  const cabins = await availabilityWeek(fixture.readDb, NEAR_POI_ID, ['cabin'], start, end, SETTINGS);
  expect(cabins.state).toBe('empty');
  expect(cabins.days).toEqual([]);
});

test('an unclaimed campground is unknown_campground and an unknown POI is not_found', async () => {
  await expect(availabilityWeek(fixture.readDb, NEAR_POI_ID, [], start, end, { ...SETTINGS, enabledAvailabilityProviders: [] })).rejects.toMatchObject(
    new AvailabilityTargetError('unknown_campground'),
  );
  await expect(availabilityWeek(fixture.readDb, UNKNOWN_POI_ID, [], start, end, SETTINGS)).rejects.toMatchObject(new AvailabilityTargetError('not_found'));
});
