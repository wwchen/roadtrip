// The basemap registry. Pure, so it is pinned directly — the DOM half of
// web/basemap.js (a <select> and a checkbox wired by id) is React's job now and is
// not ported.
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { RasterSourceSpecification, StyleSpecification } from 'maplibre-gl';
import {
  BASEMAPS,
  BASEMAP_STORAGE_KEY,
  DEFAULT_BASEMAP,
  basemapStyle,
  initialBasemapKey,
  rememberBasemapKey,
} from './basemaps';

/** The `basemap` raster source of an inline style, narrowed for assertions. */
function rasterSource(key: string): RasterSourceSpecification {
  const style = BASEMAPS[key].style as StyleSpecification;
  return style.sources.basemap as RasterSourceSpecification;
}

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('the registry', () => {
  test('every basemap has a name and a style', () => {
    for (const [key, basemap] of Object.entries(BASEMAPS)) {
      expect(basemap.name, key).toBeTruthy();
      expect(basemap.style, key).toBeTruthy();
    }
  });

  test('the default is a real entry', () => {
    expect(BASEMAPS[DEFAULT_BASEMAP]).toBeDefined();
  });

  test('the vector basemaps are style URLs and the raster ones are inline styles', () => {
    expect(typeof BASEMAPS['openfreemap-liberty'].style).toBe('string');
    expect(typeof BASEMAPS['carto-dark'].style).toBe('object');
    expect(typeof BASEMAPS.osm.style).toBe('object');
  });

  test('raster styles carry attribution, since the tiles are used under licence', () => {
    for (const key of ['carto-voyager', 'carto-positron', 'carto-dark', 'osm']) {
      expect(rasterSource(key).attribution, key).toBeTruthy();
    }
  });

  // Retina tiles, or the Carto basemaps look soft on a 2x display.
  test('the Carto tiles request @2x', () => {
    for (const url of rasterSource('carto-voyager').tiles ?? []) {
      expect(url).toContain('@2x');
    }
  });
});

describe('initialBasemapKey', () => {
  test('defaults when nothing is remembered', () => {
    expect(initialBasemapKey()).toBe(DEFAULT_BASEMAP);
  });

  test('honours a remembered choice', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'carto-dark');
    expect(initialBasemapKey()).toBe('carto-dark');
  });

  // A stored key outlives the registry. Handing setStyle an undefined style would
  // leave a blank map, so an unknown key has to fall back.
  test('falls back when the remembered basemap no longer exists', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'a-basemap-we-deleted');
    expect(initialBasemapKey()).toBe(DEFAULT_BASEMAP);
  });

  // Safari's private mode throws on localStorage rather than returning null.
  test('survives localStorage throwing', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(initialBasemapKey()).toBe(DEFAULT_BASEMAP);
  });
});

describe('rememberBasemapKey', () => {
  test('persists under the same key the vanilla map used', () => {
    rememberBasemapKey('osm');
    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('osm');
  });

  // A blocked write must not break the map, only the persistence.
  test('a failed write does not throw', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => rememberBasemapKey('osm')).not.toThrow();
  });
});

describe('basemapStyle', () => {
  test('resolves a known key', () => {
    expect(basemapStyle('osm')).toBe(BASEMAPS.osm.style);
  });

  test('falls back rather than returning undefined', () => {
    expect(basemapStyle('nope')).toBe(BASEMAPS[DEFAULT_BASEMAP].style);
  });
});
