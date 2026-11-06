import { afterAll, beforeAll, expect, test, vi } from 'vitest';
import {
  CRESCENT_POI_ID,
  DELETED_CAMPGROUND_POI_ID,
  DISABLED_PROVIDER,
  ENABLED_PROVIDERS,
  FAR_POI_ID,
  NEAR_POI_ID,
  OTHER_TYPE_POI_ID,
  TAHOE_BOUNDARY,
  UNSERVED_POI_ID,
  startCatalogFixture,
  type CatalogFixture,
} from '../testing/catalogFixture';
import { InvalidBoundaryError, searchWithinBoundary } from './campgroundSearchRepo';

const NOT_GEOJSON = 'not geojson';
const NON_ARRAY_COORDINATES = '{"type":"Polygon","coordinates":"nope"}';
const EMPTY_OBJECT = '{}';
const UNKNOWN_GEOMETRY_TYPE = '{"type":"Banana","coordinates":[[0,0]]}';
const GENEROUS_LIMIT = 100;
const TRUNCATING_LIMIT = 1;
const IN_BOUNDARY_COUNT = 2;

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

const search = (limit = GENEROUS_LIMIT, providers = ENABLED_PROVIDERS) =>
  searchWithinBoundary(fixture.readDb, TAHOE_BOUNDARY, limit, providers);

test('returns poi ids inside the boundary as strings, nearest first', async () => {
  const result = await search();
  expect(result.poiIds).toEqual([String(NEAR_POI_ID), String(FAR_POI_ID)]);
  for (const id of result.poiIds) expect(typeof id).toBe('string');
  expect(result.totalInBoundary).toBe(IN_BOUNDARY_COUNT);
  expect(result.totalMatching).toBe(IN_BOUNDARY_COUNT);
  expect(result.truncated).toBe(false);
});

test('a soft-deleted campground behind a live poi is not served', async () => {
  const result = await search();
  expect(result.poiIds).not.toContain(String(DELETED_CAMPGROUND_POI_ID));
});

test('a poi of another type inside the boundary is not served', async () => {
  const result = await search();
  expect(result.poiIds).not.toContain(String(OTHER_TYPE_POI_ID));
});

test('a geometry whose bounding box overlaps the boundary but whose centroid is outside is not inside it', async () => {
  const result = await search();
  expect(result.poiIds).not.toContain(String(CRESCENT_POI_ID));
});

test('a result is JSON-serializable', async () => {
  const result = await search();
  expect(() => JSON.stringify(result)).not.toThrow();
});

test('an empty provider set returns an empty result without querying', async () => {
  const execute = vi.spyOn(fixture.readDb, 'execute');
  const result = await searchWithinBoundary(fixture.readDb, TAHOE_BOUNDARY, GENEROUS_LIMIT, []);
  expect(execute).not.toHaveBeenCalled();
  execute.mockRestore();
  expect(result).toEqual({ poiIds: [], totalInBoundary: 0, totalMatching: 0, truncated: false });
});

test('a provider that is not enabled is not served', async () => {
  const result = await search(GENEROUS_LIMIT, [DISABLED_PROVIDER]);
  expect(result.poiIds).toEqual([String(UNSERVED_POI_ID)]);
});

test.each([NOT_GEOJSON, NON_ARRAY_COORDINATES, EMPTY_OBJECT, UNKNOWN_GEOMETRY_TYPE])(
  'a malformed boundary raises InvalidBoundaryError: %s',
  async (boundary) => {
    await expect(
      searchWithinBoundary(fixture.readDb, boundary, GENEROUS_LIMIT, ENABLED_PROVIDERS),
    ).rejects.toBeInstanceOf(InvalidBoundaryError);
  },
);

test('truncated is true when more rows match than the limit returns', async () => {
  const result = await search(TRUNCATING_LIMIT);
  expect(result.poiIds).toEqual([String(NEAR_POI_ID)]);
  expect(result.totalInBoundary).toBe(IN_BOUNDARY_COUNT);
  expect(result.totalMatching).toBe(IN_BOUNDARY_COUNT);
  expect(result.truncated).toBe(true);
});
