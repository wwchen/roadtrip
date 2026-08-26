import { afterEach, describe, expect, test, vi } from 'vitest';
import type { RasterSourceSpecification, StyleSpecification } from 'maplibre-gl';
import { applyClientConfig, resetClientConfig } from '@/api/client-config-api';
import {
  BASEMAPS,
  BASEMAP_STORAGE_KEY,
  DARK_BASEMAP,
  DEFAULT_BASEMAP,
  basemapStyle,
  forgetBasemapKey,
  initialBasemapKey,
  rememberBasemapKey,
  storedBasemapKey,
} from './basemaps';

/** The `basemap` raster source of an inline style, narrowed for assertions. */
function rasterSource(key: string): RasterSourceSpecification {
  const style = BASEMAPS[key].style as StyleSpecification;
  return style.sources.basemap as RasterSourceSpecification;
}

afterEach(() => {
  window.localStorage.clear();
  resetClientConfig();
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

  test('the Carto tiles request @2x', () => {
    for (const url of rasterSource('carto-voyager').tiles ?? []) {
      expect(url).toContain('@2x');
    }
  });

  test('Carto tile URLs include the configured basemaps key', () => {
    applyClientConfig({ carto_basemaps_api_key: 'carto key/1' });

    for (const url of rasterSourceFromStyle(basemapStyle('carto-voyager')).tiles ?? []) {
      expect(url).toContain('?key=carto%20key%2F1');
    }
  });

  test('Carto tile URLs stay keyless when no basemaps key is configured', () => {
    for (const url of rasterSourceFromStyle(basemapStyle('carto-dark')).tiles ?? []) {
      expect(url).not.toContain('?key=');
    }
  });
});

describe('initialBasemapKey', () => {
  test('defaults when nothing is remembered', () => {
    expect(initialBasemapKey('light')).toBe(DEFAULT_BASEMAP);
  });

  test('honours a remembered choice', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'carto-dark');
    expect(initialBasemapKey('light')).toBe('carto-dark');
  });

  test('falls back when the remembered basemap no longer exists', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'a-basemap-we-deleted');
    expect(initialBasemapKey('light')).toBe(DEFAULT_BASEMAP);
  });

  test('survives localStorage throwing', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(initialBasemapKey('light')).toBe(DEFAULT_BASEMAP);
  });
});

describe('theme-aware defaults', () => {
  test('light mode with nothing stored uses the light default', () => {
    expect(initialBasemapKey('light')).toBe(DEFAULT_BASEMAP);
  });

  test('dark mode with nothing stored uses the dark default', () => {
    expect(initialBasemapKey('dark')).toBe(DARK_BASEMAP);
  });

  test('an explicit pick outranks the mode', () => {
    rememberBasemapKey('osm');
    expect(initialBasemapKey('dark')).toBe('osm');
  });

  test('a stored key that no longer exists falls back to the mode default', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'a-basemap-we-dropped');
    expect(initialBasemapKey('dark')).toBe(DARK_BASEMAP);
  });

  test('storedBasemapKey reports whether the user has pinned one', () => {
    expect(storedBasemapKey()).toBeNull();
    rememberBasemapKey('osm');
    expect(storedBasemapKey()).toBe('osm');
  });

  test('forgetBasemapKey returns to auto', () => {
    rememberBasemapKey('osm');
    forgetBasemapKey();
    expect(storedBasemapKey()).toBeNull();
    expect(initialBasemapKey('dark')).toBe(DARK_BASEMAP);
  });

  test('the dark default is a real registry entry', () => {
    expect(BASEMAPS[DARK_BASEMAP]).toBeDefined();
  });
});

describe('rememberBasemapKey', () => {
  test('persists under the same key the vanilla map used', () => {
    rememberBasemapKey('osm');
    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('osm');
  });

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

function rasterSourceFromStyle(style: string | StyleSpecification): RasterSourceSpecification {
  if (typeof style === 'string') throw new Error('expected inline raster style');
  return style.sources.basemap as RasterSourceSpecification;
}
