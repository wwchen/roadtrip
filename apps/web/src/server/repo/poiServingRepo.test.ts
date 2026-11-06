import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, expect, test } from 'vitest';
import {
  ENABLED_PROVIDERS,
  NEAR_POI_ID,
  OTHER_TYPE_POI_ID,
  UNSERVED_POI_ID,
  startCatalogFixture,
  type CatalogFixture,
} from '../testing/catalogFixture';
import { fetchPois } from './poiServingRepo';

const TAHOE = { west: -121, south: 38, east: -119, north: 40 };
const DESERT = { west: -116, south: 40, east: -115, north: 41 };
const DENSE_COUNT = 200;
const DENSE_CAP = 100;
const DENSE_FIRST_ID = 50_000;
const GENEROUS_CAP = 2000;

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
  await fixture.writeDb.execute(sql`
    INSERT INTO pois (id, poi_type, geom)
    SELECT ${DENSE_FIRST_ID} + n, 'tesla_supercharger',
           ST_SetSRID(ST_MakePoint(-116 + ((n % 20) + 0.5) / 20.0, 40 + ((n / 20) + 0.5) / 10.0), 4326)
    FROM generate_series(0, ${DENSE_COUNT - 1}) AS n
  `);
});

afterAll(async () => {
  await fixture?.stop();
});

const ids = (rows: { id: bigint }[]) => rows.map((row) => row.id);

test('returns a slim row per POI in the bbox with category, subcategory and agency', async () => {
  const { rows, truncated } = await fetchPois(fixture.readDb, {
    bbox: TAHOE,
    categories: ['campground'],
    limit: GENEROUS_CAP,
    providers: ENABLED_PROVIDERS,
  });
  expect(truncated).toBe(false);
  const near = rows.find((row) => row.id === NEAR_POI_ID);
  expect(near).toMatchObject({ category: 'campground', lng: -120, lat: 39.05 });
  expect(near).toHaveProperty('subcategory');
  expect(near).toHaveProperty('agency');
});

test('excludes a campground from a disabled provider', async () => {
  const { rows } = await fetchPois(fixture.readDb, { bbox: TAHOE, categories: ['campground'], limit: GENEROUS_CAP, providers: ENABLED_PROVIDERS });
  expect(ids(rows)).not.toContain(UNSERVED_POI_ID);
});

test('only the requested categories are returned', async () => {
  const { rows } = await fetchPois(fixture.readDb, { bbox: TAHOE, categories: ['tesla_supercharger'], limit: GENEROUS_CAP, providers: ENABLED_PROVIDERS });
  expect(ids(rows)).toEqual([OTHER_TYPE_POI_ID]);
});

test('caps a dense bbox with truncated true, sampled across the bbox', async () => {
  const { rows, truncated } = await fetchPois(fixture.readDb, {
    bbox: DESERT,
    categories: ['tesla_supercharger'],
    limit: DENSE_CAP,
    providers: ENABLED_PROVIDERS,
  });
  expect(truncated).toBe(true);
  expect(rows).toHaveLength(DENSE_CAP);
  const middle = (DESERT.south + DESERT.north) / 2;
  expect(rows.some((row) => row.lat < middle)).toBe(true);
  expect(rows.some((row) => row.lat > middle)).toBe(true);
});

test('an empty bbox is an empty, untruncated result', async () => {
  const { rows, truncated } = await fetchPois(fixture.readDb, {
    bbox: { west: 10, south: 10, east: 11, north: 11 },
    categories: ['campground', 'tesla_supercharger'],
    limit: GENEROUS_CAP,
    providers: ENABLED_PROVIDERS,
  });
  expect(rows).toEqual([]);
  expect(truncated).toBe(false);
});
