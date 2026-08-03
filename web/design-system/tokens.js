/* ============================================================
   Token bridge for JS consumers.

   MapLibre paint properties, canvas charts and inline style strings
   cannot resolve `var(--rt-*)` — they need a concrete color at call
   time. This module is the only sanctioned way to get one.

   `tokens.css` stays the single source of truth: every value here is
   read from the live computed style of the document root, so a theme
   that redefines a token is picked up by the map and the charts too,
   with no duplicated palette to keep in sync.

   Usage:
     import { token, LAYER_COLOR, SERIES } from '/web/design-system/tokens.js';
     paint: { 'circle-color': token('--rt-layer-np') }

   Values are memoized after first read (getComputedStyle is not free,
   and layer specs are built in tight loops). Call `resetTokenCache()`
   after switching themes at runtime.
   ============================================================ */

/** Fallbacks, used only when the stylesheet has not loaded yet (early
 *  boot, unit tests in jsdom without CSS). Keep in sync with tokens.css;
 *  `scripts/check-color-tokens.mjs` verifies every key resolves there. */
const FALLBACKS = {
  '--rt-text': '#e8eaed',
  '--rt-muted': '#9aa0a8',
  '--rt-faint': '#626770',
  '--rt-on-accent': '#ffffff',
  '--rt-brand': '#3b82f6',
  '--rt-brand-hover': '#2b6dd1',
  '--rt-avail': '#4cb96a',
  '--rt-first-come': '#f1a04a',
  '--rt-paused': '#8a8f96',
  '--rt-error': '#f56565',
  '--rt-rating': '#f5a623',
  '--rt-layer-supercharger': '#e82127',
  '--rt-layer-supercharger-pin': '#e31937',
  '--rt-layer-cg': '#2e7d32',
  '--rt-layer-cg-federal': '#2e7d32',
  '--rt-layer-cg-state': '#558b2f',
  '--rt-layer-cg-provincial': '#2e7d32',
  '--rt-layer-cg-local': '#9ccc65',
  '--rt-layer-cg-other': '#2e7d32',
  '--rt-layer-pf': '#7b4bb5',
  '--rt-layer-pf-pin': '#7f3d97',
  '--rt-layer-np': '#2e7d32',
  '--rt-layer-sp': '#8d6e63',
  '--rt-map-np-fill': '#2e7d32',
  '--rt-map-np-stroke': '#1b5e20',
  '--rt-map-sp-fill': '#8d6e63',
  '--rt-map-sp-stroke': '#5d4037',
  '--rt-map-pin-stroke': '#ffffff',
  '--rt-map-route': '#3b82f6',
  '--rt-map-route-alt': '#4a4a4a',
  '--rt-map-waypoint': '#e0a543',
  '--rt-map-locate': '#0a84ff',
  '--rt-kind-place': '#3a7bd5',
  '--rt-kind-address': '#5a6a8a',
  '--rt-kind-default': '#666666',
  '--rt-series-1': '#4dc9f6',
  '--rt-series-2': '#f67019',
  '--rt-series-3': '#f53794',
  '--rt-series-4': '#537bc4',
  '--rt-series-5': '#acc236',
  '--rt-series-6': '#166a8f',
  '--rt-series-7': '#00a950',
  '--rt-series-8': '#58595b',
  '--rt-series-9': '#8549ba',
};

const cache = new Map();

/**
 * Resolve a design token to a concrete color string.
 * @param {string} name  Custom property name, e.g. '--rt-layer-np'.
 * @returns {string}
 */
export function token(name) {
  if (cache.has(name)) return cache.get(name);
  let value = '';
  if (typeof document !== 'undefined' && document.documentElement) {
    value = getComputedStyle(document.documentElement)
      .getPropertyValue(name)
      .trim();
  }
  if (!value) value = FALLBACKS[name] || '';
  if (!value) {
    console.warn(`[tokens] unknown design token ${name}`);
    value = FALLBACKS['--rt-muted'];
  }
  cache.set(name, value);
  return value;
}

/** Drop memoized values. Call after swapping themes at runtime. */
export function resetTokenCache() {
  cache.clear();
}

/** Search-result kind → pin color. */
export const KIND_TOKEN = {
  PLACE: '--rt-kind-place',
  ADDR: '--rt-kind-address',
  CG: '--rt-layer-cg',
  SC: '--rt-layer-supercharger',
  NP: '--rt-layer-np',
  SP: '--rt-layer-sp',
  PF: '--rt-layer-pf',
};

/** Number of categorical chart series before the ramp wraps. */
export const SERIES_COUNT = 9;

/** Categorical chart color for series `i` (wraps past SERIES_COUNT). */
export function seriesColor(i) {
  return token(`--rt-series-${(i % SERIES_COUNT) + 1}`);
}
