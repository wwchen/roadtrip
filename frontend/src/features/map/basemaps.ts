// The basemap registry.
import type { StyleSpecification } from 'maplibre-gl';
import type { ThemeMode } from '@/lib/theme';

/** Where the chosen basemap is remembered. */
export const BASEMAP_STORAGE_KEY = 'basemap';

export const DEFAULT_BASEMAP = 'openfreemap-liberty';

/** Retina raster tiles, so the Carto basemaps are not soft on a 2x display. */
const CARTO_TILE_SUFFIX = '{z}/{x}/{y}@2x.png';
const CARTO_SUBDOMAINS = ['a', 'b', 'c', 'd'] as const;
const RASTER_TILE_SIZE = 256;

const OSM_ATTRIBUTION = '&copy; OpenStreetMap contributors';
const CARTO_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> ' +
  '&copy; <a href="https://carto.com/attributions">CARTO</a>';

/** A single-source raster style, which is all the non-vector basemaps need. */
function rasterStyle(tiles: string[], attribution: string): StyleSpecification {
  return {
    version: 8,
    sources: {
      basemap: { type: 'raster', tiles, tileSize: RASTER_TILE_SIZE, attribution },
    },
    layers: [{ id: 'basemap', type: 'raster', source: 'basemap' }],
  };
}

const cartoStyle = (variant: string): StyleSpecification =>
  rasterStyle(
    CARTO_SUBDOMAINS.map(
      (s) => `https://${s}.basemaps.cartocdn.com/rastertiles/${variant}/${CARTO_TILE_SUFFIX}`,
    ),
    CARTO_ATTRIBUTION,
  );

export interface Basemap {
  /** The picker's tile label — a visual category, not the tile provider's own name. */
  name: string;
  /** A style URL for the vector basemaps, or an inline style for the raster ones. */
  style: string | StyleSpecification;
  /** The picker tile's swatch preview, as a CSS `background` value. */
  swatch: string;
}

/** Every basemap on offer. Free and key-less, named by visual category so the
 *  picker reads as "what will this look like" rather than "whose tiles are these". */
export const BASEMAPS: Readonly<Record<string, Basemap>> = {
  'openfreemap-liberty': {
    name: 'Streets',
    style: 'https://tiles.openfreemap.org/styles/liberty',
    swatch: 'linear-gradient(150deg, #EFEAE2, #D9D3C8)',
  },
  'openfreemap-bright': {
    name: 'Outdoors',
    style: 'https://tiles.openfreemap.org/styles/bright',
    swatch: 'linear-gradient(150deg, #E4EBDD, #C4CFB4)',
  },
  'carto-voyager': {
    name: 'Terrain',
    style: cartoStyle('voyager'),
    swatch: 'linear-gradient(150deg, #EDE3D4, #CBB394)',
  },
  'carto-positron': {
    name: 'Light',
    style: cartoStyle('light_all'),
    swatch: '#FAFBFC',
  },
  'carto-dark': {
    name: 'Dark',
    style: cartoStyle('dark_all'),
    swatch: '#232A33',
  },
  osm: {
    name: 'Transit',
    style: rasterStyle(['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], OSM_ATTRIBUTION),
    swatch: 'linear-gradient(150deg, #DEEEFE, #B9D8F6)',
  },
};

/** The basemap dark mode reaches for when the user has never picked one. */
export const DARK_BASEMAP = 'carto-dark';

/** The picker's "follow the theme" option. An empty string, because selecting it
 *  REMOVES the stored key — absence already means auto, and a stored sentinel
 *  would be a second encoding of the same state. */
export const AUTO_BASEMAP_VALUE = '';

/** The mode's default when the user has expressed no preference. */
function defaultBasemapFor(mode: ThemeMode): string {
  return mode === 'dark' ? DARK_BASEMAP : DEFAULT_BASEMAP;
}

/**
 * The user's explicit pick, or null when they have never made one.
 *
 * Reads defensively — Safari's private mode throws rather than returning null —
 * and drops a key the registry no longer has: a stored key outlives the
 * registry, and one that was renamed would otherwise reach `setStyle` as an
 * undefined style and leave a blank map.
 */
export function storedBasemapKey(): string | null {
  let saved: string | null = null;
  try {
    saved = window.localStorage.getItem(BASEMAP_STORAGE_KEY);
  } catch {
    return null;
  }
  return saved != null && saved in BASEMAPS ? saved : null;
}

/** Drop the explicit pick, returning to "follow the theme". */
export function forgetBasemapKey(): void {
  try {
    window.localStorage.removeItem(BASEMAP_STORAGE_KEY);
  } catch {
    // Private mode / quota. The map still works.
  }
}

/** The basemap to open with: the remembered one if it still exists, else the one
 *  this mode calls for. */
export function initialBasemapKey(mode: ThemeMode): string {
  return storedBasemapKey() ?? defaultBasemapFor(mode);
}

/** Remember a basemap choice. Silent on failure — a blocked write must not break the map. */
export function rememberBasemapKey(key: string): void {
  try {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, key);
  } catch {
    // Private mode / quota. The map still works, the choice just is not persisted.
  }
}

/** The style for a key, falling back to the default rather than returning undefined. */
export function basemapStyle(key: string): string | StyleSpecification {
  return (BASEMAPS[key] ?? BASEMAPS[DEFAULT_BASEMAP]).style;
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

export const satelliteSource = {
  type: 'raster' as const,
  tiles: [
    'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  ],
  tileSize: RASTER_TILE_SIZE,
  maxzoom: SATELLITE_MAX_ZOOM,
  attribution: 'Tiles &copy; Esri, Maxar, Earthstar Geographics',
};
