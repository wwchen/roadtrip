import { afterAll, beforeAll, expect, test } from 'vitest';
import { CRESCENT_NAME, ENABLED_PROVIDERS, NEAR_NAME, startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { searchPoisByName } from './poiSearchRepo';

const LIMIT = 10;
let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

const names = async (q: string, categories: string[] = [], limit = LIMIT) =>
  (await searchPoisByName(fixture.readDb, q, categories, limit, ENABLED_PROVIDERS)).map((hit) => hit.name);

test('matches every term of a multi-word query', async () => {
  expect(await names('near camp')).toEqual([NEAR_NAME]);
});

test('a single term matches anywhere in the name, including a polygon POI', async () => {
  expect((await names('campground')).length).toBeGreaterThan(1);
  expect(await names('cres')).toEqual([CRESCENT_NAME]);
});

test('filters by categories', async () => {
  expect(await names('type poi', ['campground'])).toEqual([]);
  expect(await names('type poi', ['tesla_supercharger'])).toEqual(['Other Type Poi']);
});

test('excludes a campground from a disabled provider', async () => {
  expect(await names('unserved')).toEqual([]);
});

test('a hit carries its id, category, region and coordinates', async () => {
  const [hit] = await searchPoisByName(fixture.readDb, 'near camp', [], LIMIT, ENABLED_PROVIDERS);
  expect(hit).toMatchObject({ name: NEAR_NAME, category: 'campground', region: 'CA', lng: -120, lat: 39.05 });
  expect(typeof hit?.id).toBe('bigint');
});

test('LIKE wildcards in the query are literal', async () => {
  expect(await names('%%')).toEqual([]);
});
