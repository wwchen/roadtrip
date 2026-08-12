import { describe, expect, test } from 'vitest';
import {
  BASE_VIEWPORT_CATEGORIES,
  CG_ZOOM_THRESHOLD,
  readMapViewport,
  viewportRequestFor,
  type ViewportSource,
} from './viewport';

const fakeMap = (
  bbox: [number, number, number, number],
  zoom: number,
): ViewportSource => ({
  getBounds: () => ({
    getWest: () => bbox[0],
    getSouth: () => bbox[1],
    getEast: () => bbox[2],
    getNorth: () => bbox[3],
  }),
  getZoom: () => zoom,
});

const request = (zoom: number, campgroundsUnlocked = false) =>
  viewportRequestFor({ bbox: [-124, 32, -114, 42], zoom, campgroundsUnlocked });

describe('readMapViewport', () => {
  test('flattens the bounds to [west, south, east, north]', () => {
    expect(readMapViewport(fakeMap([-124, 32, -114, 42], 7.4)).bbox).toEqual([-124, 32, -114, 42]);
  });

  test('floors the zoom', () => {
    expect(readMapViewport(fakeMap([0, 0, 1, 1], 7.9)).zoom).toBe(7);
  });
});

describe('categories', () => {
  test('the point layers are always requested', () => {
    expect(request(3).categories).toEqual([...BASE_VIEWPORT_CATEGORIES]);
  });

  test('campgrounds join in at the zoom the server will serve them', () => {
    expect(request(CG_ZOOM_THRESHOLD - 1).categories).not.toContain('campground');
    expect(request(CG_ZOOM_THRESHOLD).categories).toContain('campground');
  });

  test('an unlocked view keeps requesting campgrounds after zooming out', () => {
    const zoomedOut = request(3, true);

    expect(zoomedOut.categories).toContain('campground');
    expect(zoomedOut.campgroundsRequested).toBe(true);
  });

  test('campgroundsRequested reports the latch, not just the zoom', () => {
    expect(request(3).campgroundsRequested).toBe(false);
    expect(request(CG_ZOOM_THRESHOLD).campgroundsRequested).toBe(true);
  });
});

describe('the cache key', () => {
  test('is category-order independent', () => {
    expect(request(CG_ZOOM_THRESHOLD).cacheKey).toBe(
      'campground,planet_fitness_location,tesla_supercharger|cg=1',
    );
  });

  test('separates a request that will get campgrounds from one that will not', () => {
    const unlockedButTooFarOut = request(3, true);
    const closeEnough = request(CG_ZOOM_THRESHOLD, true);

    expect(unlockedButTooFarOut.categories).toEqual(closeEnough.categories);
    expect(unlockedButTooFarOut.cacheKey).not.toBe(closeEnough.cacheKey);
    expect(unlockedButTooFarOut.cacheKey.endsWith('|cg=0')).toBe(true);
    expect(closeEnough.cacheKey.endsWith('|cg=1')).toBe(true);
  });
});
