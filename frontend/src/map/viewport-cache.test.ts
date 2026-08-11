import { describe, expect, test } from 'vitest';
import {
  VIEWPORT_CACHE_MAX_ENTRIES,
  VIEWPORT_CACHE_TTL_MS,
  bboxContains,
  createViewportCache,
  type CacheBbox,
} from './viewport-cache';

const CALIFORNIA: CacheBbox = [-124, 32, -114, 42];
const BAY_AREA: CacheBbox = [-123, 37, -121, 38];
/** Overlaps California but spills east of it, so neither contains the other. */
const GREAT_BASIN: CacheBbox = [-120, 35, -110, 42];

const KEY = 'campground,planet_fitness_location,tesla_supercharger|cg=1';

describe('bboxContains', () => {
  test('a bbox contains one nested inside it', () => {
    expect(bboxContains(CALIFORNIA, BAY_AREA)).toBe(true);
  });

  test('a bbox does not contain one that spills over an edge', () => {
    expect(bboxContains(BAY_AREA, CALIFORNIA)).toBe(false);
    expect(bboxContains(CALIFORNIA, GREAT_BASIN)).toBe(false);
  });

  test('identical bboxes contain each other', () => {
    expect(bboxContains(CALIFORNIA, CALIFORNIA)).toBe(true);
  });
});

describe('lookup', () => {
  test('a pan into a cached superset is a hit', () => {
    const cache = createViewportCache<string>();
    cache.put(CALIFORNIA, KEY, 'statewide');

    expect(cache.lookup(BAY_AREA, KEY)).toBe('statewide');
  });

  test('a pan outside the cached bbox is a miss', () => {
    const cache = createViewportCache<string>();
    cache.put(BAY_AREA, KEY, 'bay');

    expect(cache.lookup(GREAT_BASIN, KEY)).toBeNull();
  });

  test('a different key never matches, however contained the bbox', () => {
    const cache = createViewportCache<string>();
    cache.put(CALIFORNIA, 'planet_fitness_location,tesla_supercharger|cg=0', 'no campgrounds');

    expect(cache.lookup(BAY_AREA, KEY)).toBeNull();
  });

  test('the newest matching entry wins', () => {
    const cache = createViewportCache<string>();
    cache.put(CALIFORNIA, KEY, 'older');
    cache.put(CALIFORNIA, KEY, 'newer');

    expect(cache.lookup(BAY_AREA, KEY)).toBe('newer');
  });

  test('an empty cache misses', () => {
    expect(createViewportCache<string>().lookup(BAY_AREA, KEY)).toBeNull();
  });
});

describe('expiry', () => {
  test('an entry past its TTL is a miss', () => {
    const cache = createViewportCache<string>();
    cache.put(CALIFORNIA, KEY, 'statewide', 0);

    expect(cache.lookup(BAY_AREA, KEY, VIEWPORT_CACHE_TTL_MS)).toBe('statewide');
    expect(cache.lookup(BAY_AREA, KEY, VIEWPORT_CACHE_TTL_MS + 1)).toBeNull();
  });

  test('expired entries are dropped, not just skipped', () => {
    const cache = createViewportCache<string>();
    cache.put(CALIFORNIA, KEY, 'statewide', 0);

    cache.lookup(BAY_AREA, KEY, VIEWPORT_CACHE_TTL_MS + 1);

    expect(cache.size).toBe(0);
  });
});

describe('the ring', () => {
  test('holds at most the configured number of entries', () => {
    const cache = createViewportCache<number>();
    for (let i = 0; i < VIEWPORT_CACHE_MAX_ENTRIES + 4; i++) {
      cache.put([i, i, i + 1, i + 1], KEY, i);
    }

    expect(cache.size).toBe(VIEWPORT_CACHE_MAX_ENTRIES);
  });

  test('evicts the oldest entry first', () => {
    const cache = createViewportCache<string>({ maxEntries: 2 });
    cache.put(CALIFORNIA, KEY, 'first');
    cache.put(GREAT_BASIN, KEY, 'second');
    cache.put([-100, 30, -90, 40], KEY, 'third');

    expect(cache.lookup(BAY_AREA, KEY)).toBeNull();
    expect(cache.lookup(GREAT_BASIN, KEY)).toBe('second');
  });
});
