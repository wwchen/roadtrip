// The basemap registry.
import type { ThemeMode } from '@/lib/theme';

/** Where the chosen basemap is remembered. */
export const BASEMAP_STORAGE_KEY = 'basemap';

export const DEFAULT_BASEMAP = 'streets';

/** Carto's vector styles. They need no API key, where the retiring raster tiles now do,
 *  and they carry their own CARTO/OpenStreetMap attribution via the style's TileJSON. */
const cartoStyle = (style: string): string =>
  `https://basemaps.cartocdn.com/gl/${style}-gl-style/style.json`;

const openFreeMap = (style: string): string => `https://tiles.openfreemap.org/styles/${style}`;

export interface Basemap {
  /** The picker's tile label — a visual category, not the tile provider's own name. */
  name: string;
  /** The style URL for each mode. Every cartography carries both. */
  style: Readonly<{ light: string; dark: string }>;
  /** The picker tile's swatch preview. One token per basemap; `tokens.css` gives it
   *  a per-mode value, so the swatch previews the variant you would actually get. */
  swatch: string;
}

/**
 * Every basemap on offer — a **cartography**, not a brightness.
 *
 * Light and Dark used to sit in this list beside Streets and Terrain, which made
 * one control choose two unrelated things: picking Terrain in a dark UI handed you
 * a bright tan map, and "match the theme" swapped the whole cartography rather than
 * its brightness. Brightness is the app's theme now and never appears here; each
 * entry carries the light and dark tiles for the same map instead.
 */
export const BASEMAPS: Readonly<Record<string, Basemap>> = {
  streets: {
    name: 'Streets',
    style: { light: openFreeMap('liberty'), dark: openFreeMap('dark') },
    swatch: 'var(--rt-basemap-streets)',
  },
  outdoors: {
    name: 'Outdoors',
    style: { light: openFreeMap('bright'), dark: openFreeMap('fiord') },
    swatch: 'var(--rt-basemap-outdoors)',
  },
  terrain: {
    name: 'Terrain',
    style: { light: cartoStyle('voyager'), dark: cartoStyle('dark-matter') },
    swatch: 'var(--rt-basemap-terrain)',
  },
};

/**
 * Keys stored before basemaps became cartographies.
 *
 * The old list was provider-named and mixed the two axes, so a remembered
 * `carto-dark` is not a map anyone can be given back — it is "Terrain, dark", and
 * dark is the theme's business now. `osm` was OSM Standard, a street map that was
 * mislabelled Transit, so it lands on Streets. Mapped rather than dropped:
 * `storedBasemapKey` silently discards an unknown key, which would quietly reset
 * everyone who had ever touched the picker.
 */
const LEGACY_BASEMAP_KEYS: Readonly<Record<string, string>> = {
  'openfreemap-liberty': 'streets',
  'openfreemap-bright': 'outdoors',
  'carto-voyager': 'terrain',
  'carto-positron': 'terrain',
  'carto-dark': 'terrain',
  osm: 'streets',
};

/**
 * The user's explicit pick, or null when they have never made one.
 *
 * Reads defensively — Safari's private mode throws rather than returning null —
 * migrates a pre-split key, and drops one the registry no longer has: a stored key
 * outlives the registry, and one that was renamed would otherwise reach `setStyle`
 * as an undefined style and leave a blank map.
 */
export function storedBasemapKey(): string | null {
  let saved: string | null = null;
  try {
    saved = window.localStorage.getItem(BASEMAP_STORAGE_KEY);
  } catch {
    return null;
  }
  if (saved == null) return null;
  const migrated = LEGACY_BASEMAP_KEYS[saved] ?? saved;
  return migrated in BASEMAPS ? migrated : null;
}

/** The basemap to open with: the remembered cartography, or the default. */
export function initialBasemapKey(): string {
  return storedBasemapKey() ?? DEFAULT_BASEMAP;
}

/** Remember a basemap choice. Silent on failure — a blocked write must not break the map. */
export function rememberBasemapKey(key: string): void {
  try {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, key);
  } catch {
    // Private mode / quota. The map still works, the choice just is not persisted.
  }
}

/**
 * The style for a cartography in a mode.
 *
 * Falls back to the default rather than returning undefined: a stored key outlives
 * the registry, and one that was renamed would otherwise reach `setStyle` as an
 * undefined style and leave a blank map. Every cartography has tiles for both
 * modes, so resolving the key is the only lookup that can miss.
 */
export function basemapStyle(key: string, mode: ThemeMode): string {
  return (BASEMAPS[key] ?? BASEMAPS[DEFAULT_BASEMAP]).style[mode];
}

/**
 * Esri World Imagery, as an optional underlay.
 *
 * Kept as data rather than as the legacy `installSatellite()` function, because that
 * read a checkbox by id and mutated the shared `state.map`. The provider owns
 * insertion now; this is just the source and layer it inserts.
 */
export const SATELLITE_SOURCE_ID = 'esri-imagery';
export const SATELLITE_LAYER_ID = 'esri-imagery-raster';
export const SATELLITE_MAX_ZOOM = 19;
const SATELLITE_TILE_SIZE = 256;

export const satelliteSource = {
  type: 'raster' as const,
  tiles: [
    'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  ],
  tileSize: SATELLITE_TILE_SIZE,
  maxzoom: SATELLITE_MAX_ZOOM,
  attribution: 'Tiles &copy; Esri, Maxar, Earthstar Geographics',
};
