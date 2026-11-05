import { beforeEach, expect, test, vi } from 'vitest';

const fetchPois = vi.fn();
vi.mock('../db', () => ({ serverDb: async () => ({}) }));
vi.mock('../repo/poiServingRepo', () => ({ fetchPois }));

const { CAMPGROUND_MIN_ZOOM, POI_LIMIT, viewportPois } = await import('./poiService');

const BBOX = { west: 0, south: 0, east: 1, north: 1 };

beforeEach(() => {
  fetchPois.mockReset();
  fetchPois.mockResolvedValue({ rows: [], truncated: false });
});

test('campgrounds are dropped below CAMPGROUND_MIN_ZOOM and kept at it', async () => {
  await viewportPois(BBOX, CAMPGROUND_MIN_ZOOM - 1, ['campground', 'tesla_supercharger']);
  expect(fetchPois.mock.calls[0]?.[1]).toMatchObject({ categories: ['tesla_supercharger'], limit: POI_LIMIT });
  await viewportPois(BBOX, CAMPGROUND_MIN_ZOOM, ['campground']);
  expect(fetchPois.mock.calls[1]?.[1]).toMatchObject({ categories: ['campground'] });
});

test('no categories means every served category', async () => {
  await viewportPois(BBOX, null, null);
  expect(fetchPois.mock.calls[0]?.[1].categories).toEqual(['campground', 'tesla_supercharger', 'planet_fitness_location']);
});

test('nothing visible is an empty collection without a query', async () => {
  expect(await viewportPois(BBOX, 3, ['campground'])).toEqual({ type: 'FeatureCollection', truncated: false, features: [] });
  expect(fetchPois).not.toHaveBeenCalled();
});

test('rows become slim point features', async () => {
  fetchPois.mockResolvedValue({
    rows: [{ id: 7n, category: 'campground', subcategory: 'state', agency: 'BC Parks', lng: -120.1, lat: 52.2 }],
    truncated: true,
  });
  expect(await viewportPois(BBOX, 8, ['campground'])).toEqual({
    type: 'FeatureCollection',
    truncated: true,
    features: [
      {
        type: 'Feature',
        id: 7,
        geometry: { type: 'Point', coordinates: [-120.1, 52.2] },
        properties: { category: 'campground', subcategory: 'state', agency: 'BC Parks' },
      },
    ],
  });
});

test('a missing subcategory and agency are omitted, not null', async () => {
  fetchPois.mockResolvedValue({ rows: [{ id: 8n, category: 'tesla_supercharger', subcategory: null, agency: null, lng: 0, lat: 0 }], truncated: false });
  const { features } = await viewportPois(BBOX, 8, ['tesla_supercharger']);
  expect(features[0]?.properties).toEqual({ category: 'tesla_supercharger' });
});

test('a query under two characters is an empty result, not an error, and runs no query', async () => {
  const { searchPois } = await import('./poiService');
  expect(await searchPois('a', 10, [])).toEqual({ results: [] });
});
