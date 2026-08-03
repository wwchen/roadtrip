// The map engine and its geometry helper, loaded on demand.
//
// These are the two heaviest things the app ships, and the landing page needs
// neither — so they load when a map is actually wanted rather than on every
// page view. On a phone that is the difference between a first screen that is
// HTML and one that parses a map engine to show a text field.
//
// Versions stay pinned here rather than at the call sites: one place to bump,
// and a call site that reads as "load the map stack" rather than as a URL.

import { loadScript, loadStylesheet } from './vendor-scripts.js';

const MAPLIBRE_VERSION = '4.7.1';
const MAPLIBRE_JS = `https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.js`;
const MAPLIBRE_CSS = `https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.css`;
// turf is loaded whole because topbar.js reads it off the `turf` global for
// corridor buffering and polygon simplification. Narrowing it to the two
// functions actually used is a real saving and a separate change — it alters
// geometry code and wants its own QA pass.
const TURF_JS = 'https://unpkg.com/@turf/turf@7/turf.min.js';

let stackPromise = null;

/**
 * Load MapLibre (JS + CSS) and turf. Idempotent: repeated calls share one
 * promise, so a caller never has to ask whether the stack is already up.
 * @returns {Promise<void>} resolves once `maplibregl` and `turf` are global.
 */
export function loadMapStack() {
  if (!stackPromise) {
    stackPromise = Promise.all([
      loadStylesheet(MAPLIBRE_CSS),
      loadScript(MAPLIBRE_JS),
      loadScript(TURF_JS),
    ]).then(() => undefined);
  }
  return stackPromise;
}

/** True once the map stack has been requested at least once. */
export function isMapStackRequested() {
  return stackPromise !== null;
}
