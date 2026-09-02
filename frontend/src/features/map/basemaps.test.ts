import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  BASEMAPS,
  BASEMAP_STORAGE_KEY,
  DEFAULT_BASEMAP,
  basemapStyle,
  initialBasemapKey,
  rememberBasemapKey,
  storedBasemapKey,
} from './basemaps';

const MODES = ['light', 'dark'] as const;

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('the registry', () => {
  test('every basemap has a name and a light style', () => {
    for (const [key, basemap] of Object.entries(BASEMAPS)) {
      expect(basemap.name, key).toBeTruthy();
      expect(basemap.style.light, key).toBeTruthy();
    }
  });

  test('the default is a real entry, and has tiles for both modes', () => {
    expect(BASEMAPS[DEFAULT_BASEMAP]).toBeDefined();
    expect(BASEMAPS[DEFAULT_BASEMAP].style.light).toBeTruthy();
    expect(BASEMAPS[DEFAULT_BASEMAP].style.dark).toBeTruthy();
  });

  // The bug this whole split exists for: Light and Dark used to be entries here,
  // so one control chose both the cartography and the brightness and picking a map
  // could leave a bright basemap inside a dark UI.
  test('no entry is a brightness — that is the theme, not a map', () => {
    const names = Object.values(BASEMAPS).map((b) => b.name);
    expect(names).not.toContain('Light');
    expect(names).not.toContain('Dark');
  });

  test('every style is a keyless style URL', () => {
    for (const [key, basemap] of Object.entries(BASEMAPS)) {
      for (const mode of MODES) {
        expect(basemap.style[mode], `${key}.${mode}`).toMatch(/^https:\/\//);
        expect(basemap.style[mode], `${key}.${mode}`).not.toContain('key=');
      }
    }
  });

  test('the Carto basemaps are vector styles, since the raster tiles now need an API key', () => {
    for (const mode of MODES) {
      expect(BASEMAPS.terrain.style[mode], mode).toMatch(
        /^https:\/\/basemaps\.cartocdn\.com\/gl\/[a-z-]+-gl-style\/style\.json$/,
      );
    }
  });
});

describe('every cartography works in both modes', () => {
  // The picker shows the whole registry in either mode, so an entry missing one
  // mode's tiles would be a tile that silently hands you a different map. Transit
  // (OSM raster, light-only) was that entry, and dropping it is what lets the
  // picker stop filtering.
  test.each(Object.keys(BASEMAPS))('%s has light and dark tiles', (key) => {
    expect(BASEMAPS[key].style.light).toBeTruthy();
    expect(BASEMAPS[key].style.dark).toBeTruthy();
  });
});

describe('initialBasemapKey', () => {
  test('defaults when nothing is remembered', () => {
    expect(initialBasemapKey()).toBe(DEFAULT_BASEMAP);
  });

  test('honours a remembered choice', () => {
    rememberBasemapKey('terrain');
    expect(initialBasemapKey()).toBe('terrain');
  });

  test('does not depend on the mode: brightness is not a basemap any more', () => {
    rememberBasemapKey('terrain');
    expect(initialBasemapKey()).toBe('terrain');
    expect(basemapStyle('terrain', 'light')).not.toBe(basemapStyle('terrain', 'dark'));
  });

  test('falls back when the remembered basemap no longer exists', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'a-basemap-we-deleted');
    expect(initialBasemapKey()).toBe(DEFAULT_BASEMAP);
  });

  test('survives localStorage throwing', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(initialBasemapKey()).toBe(DEFAULT_BASEMAP);
  });
});

// A key stored before the split names a provider and, for two of them, a
// brightness. Dropping them would silently reset everyone who had ever touched the
// picker, so each maps onto the cartography it was.
describe('keys stored before the split', () => {
  test.each([
    ['openfreemap-liberty', 'streets'],
    ['openfreemap-bright', 'outdoors'],
    ['carto-voyager', 'terrain'],
    ['carto-positron', 'terrain'],
    ['carto-dark', 'terrain'],
    ['osm', 'streets'],
  ])('%s becomes %s', (stored, expected) => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, stored);
    expect(storedBasemapKey()).toBe(expected);
  });

  test('a key from neither list is still dropped', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'a-basemap-we-dropped');
    expect(storedBasemapKey()).toBeNull();
  });
});

describe('rememberBasemapKey', () => {
  test('persists under the same key the vanilla map used', () => {
    rememberBasemapKey('outdoors');
    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('outdoors');
  });

  test('a failed write does not throw', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => rememberBasemapKey('outdoors')).not.toThrow();
  });
});

describe('basemapStyle', () => {
  test('resolves a known key in each mode', () => {
    expect(basemapStyle('terrain', 'light')).toBe(BASEMAPS.terrain.style.light);
    expect(basemapStyle('terrain', 'dark')).toBe(BASEMAPS.terrain.style.dark);
  });

  test('falls back rather than returning undefined', () => {
    expect(basemapStyle('nope', 'light')).toBe(BASEMAPS[DEFAULT_BASEMAP].style.light);
  });
});
