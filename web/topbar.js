// web/topbar.js — Google-Maps-style top-left search + directions.
//
// Flow:
//   1. Single search bar at top-left. Type → autofill via /api/geocode +
//      filter the existing pin search index. Pick a result → flyTo +
//      open the existing drawer/popup if it's a pin.
//   2. Click the Directions button → second search slot appears.
//   3. When both slots filled → call /api/route → draw polyline on map.
//   4. + Add stop button → 3+ waypoints. Delete X / drag-to-reorder per row.
//
// Deliberately small. No corridor filtering, no feature-state tricks, no
// custom paint. Polyline draws as a single LineString layer; that's it.
//
// Public surface:
//   initTopbar(map, getPinSearchIndex)
//   The module otherwise owns its DOM and state internally.

import { state, distanceKm, formatDistance, flattenHydratedPoi, geomCenter, zoomForBbox } from './core.js';
import { fitAndSelect } from './search.js';
import { campgroundFeaturePassesFilter, campgroundLayerCategory, onCampgroundFilterChange, synthesizeClick } from './layers.js';
import { openCampgroundDrawer } from './drawer/campground.js';
import { openParkDrawer } from './drawer/park.js';
import { openPlanetFitnessDrawer } from './drawer/planet-fitness.js';
import { openSuperchargerDrawer } from './drawer/supercharger.js';
import { hydratePoi } from './drawer/shared.js';
import { geocode } from './api/geocode-api.js';
import { HttpError } from './api/http.js';
import { fetchOnRoutePois, fetchPoiDetail, searchPois } from './api/poi-api.js';
import { requestRoute } from './api/directions-api.js';
import {
  CORRIDOR_DEFAULT_MILES,
  CORRIDOR_MAX_MILES,
  CORRIDOR_MIN_MILES,
  CORRIDOR_SIMPLIFY_TOLERANCE,
  CORRIDOR_STEP_MILES,
  GEOCODE_DEBOUNCE_MS,
  kindColor,
  MAX_STOPS,
  ROUTE_COLOR_VAR,
  routeColor,
  trip,
} from './topbar/state.js';
import { token } from './design-system/tokens.js';
import { clearVisibleShareUrl, decodeRouteState, replaceVisibleUrl, routeShareUrl } from './share-links.js';
import { initAlerts } from './topbar/alerts.js';
import { initAuth } from './topbar/auth.js';

// --- module state ----------------------------------------------------------

let mapRef = null;
let pinSearchIndex = null;
let activeRowIdx = -1;
let dropdownIdx = -1;
let currentResults = [];
let geocodeAbort = null;
let geocodeTimer = null;
// Set true while a card-click is synthesizing a map click — bindPinClicks
// reads this and skips its "fill active row with pin name" side effect.
let suppressPinClick = false;

function isMobileViewport() {
  return typeof window !== 'undefined' &&
    !!window.matchMedia?.('(max-width: 768px)').matches;
}

// On mobile, programmatic focus on a search input pops the soft keyboard,
// which covers the map/drawer. Skip auto-focus there and let the user tap
// the input themselves when they're ready to type.
function shouldAutoFocus() {
  return !isMobileViewport();
}

function activeTopbarInput() {
  const el = document.activeElement;
  const topbar = document.getElementById('topbar');
  return el?.classList?.contains('tb-input') && topbar?.contains(el) ? el : null;
}

function blurTopbarInputOnMobile() {
  if (!isMobileViewport()) return;
  const el = activeTopbarInput();
  if (el) el.blur();
  closeDropdown();
}

function currentLocationStop(lng, lat) {
  return { name: 'Current location', lng, lat, kind: 'PLACE' };
}

function afterStopLocationChanged(stop) {
  rerender();
  if (trip.mode === 'browse') {
    if (stop && mapRef) {
      mapRef.flyTo({ center: [stop.lng, stop.lat], zoom: 13, speed: 1.6 });
    }
    return;
  }
  if (allStopsFilled()) {
    tryFetchRoute();
  } else {
    removeRouteLayer();
    hideStatus();
    notifyCorridorChanged();
  }
}

function rowCanUseCurrentLocation(rowIdx, { onlyIfEmpty, requireDirections }) {
  if (requireDirections && trip.mode !== 'directions') return false;
  const cur = trip.stops[rowIdx];
  return !onlyIfEmpty || cur == null || cur._pending;
}

function fillRowWithCurrentLocation(rowIdx, opts = {}) {
  const options = {
    onlyIfEmpty: false,
    requireDirections: false,
    silent: false,
    ...opts,
  };
  if (!Number.isInteger(rowIdx) || rowIdx < 0) return;
  if (!rowCanUseCurrentLocation(rowIdx, options)) return;

  activeRowIdx = rowIdx;
  closeDropdown();

  const apply = (idx, lng, lat) => {
    if (!rowCanUseCurrentLocation(idx, options)) return;
    const stop = currentLocationStop(lng, lat);
    setStop(idx, stop);
    afterStopLocationChanged(stop);
  };

  if (state.userLocation && Number.isFinite(state.userLocation.lng) && Number.isFinite(state.userLocation.lat)) {
    apply(rowIdx, state.userLocation.lng, state.userLocation.lat);
    return;
  }
  if (typeof navigator === 'undefined' || !navigator.geolocation) {
    if (!options.silent) showStatus('Current location is not available.', { error: true });
    return;
  }

  const token = {};
  setStop(rowIdx, {
    name: 'Locating you…',
    lng: 0,
    lat: 0,
    kind: 'PLACE',
    _pending: true,
    _locatingToken: token,
  });
  afterStopLocationChanged(null);

  navigator.geolocation.getCurrentPosition(
    (pos) => {
      const idx = trip.stops.findIndex(s => s?._locatingToken === token);
      if (idx < 0) return;
      state.userLocation = { lng: pos.coords.longitude, lat: pos.coords.latitude };
      apply(idx, pos.coords.longitude, pos.coords.latitude);
    },
    () => {
      const idx = trip.stops.findIndex(s => s?._locatingToken === token);
      if (idx >= 0) {
        trip.stops[idx] = null;
        rerender();
      }
      if (!options.silent) showStatus('Could not get current location.', { error: true });
    },
    { enableHighAccuracy: false, timeout: 8000, maximumAge: 60_000 },
  );
}

/**
 * Fill row 0 with the user's current location, async. Used by the mobile
 * Drawer-Directions path so a phone tap goes straight to "from current
 * location → POI."
 *
 *   - state.userLocation already set (geolocate puck used earlier this
 *     session): fill immediately, no permission prompt.
 *   - state.userLocation null: ask the browser. iOS prompts on first
 *     ask per session; subsequent asks reuse the grant.
 *   - permission denied / timeout / unsupported: silently leave row 0
 *     empty. The destination is still set; user can type the origin.
 *
 * Aborts if the user already filled or cleared row 0 between when we
 * started and when the geolocation resolves — don't clobber their input.
 */
function tryFillOriginWithCurrentLocation() {
  fillRowWithCurrentLocation(0, {
    onlyIfEmpty: true,
    requireDirections: true,
    silent: true,
  });
}

// --- public --------------------------------------------------------------

export function initTopbar(map, getPinSearchIndex) {
  mapRef = map;
  pinSearchIndex = getPinSearchIndex;

  injectStyles();
  injectDom();
  bindEvents();
  bindPinClicks();
  renderRows();
  initAlerts();
  initAuth();

  // Drawer + popups read these to render a per-POI Directions button. We
  // expose globals (vs. an import) because the drawer module is downstream
  // from popups.js and shouldn't pull topbar.js into its dependency graph.
  window.__rtTripMode = () => trip.mode;
  window.__rtRouteActive = () => trip.mode === 'directions' && !!trip.route && allStopsFilled();
  window.__rtAddTripStop = (stop) => addTripStopFromExternal(stop);
  window.__rtUseCurrentLocationForTripStop = (rowIdx, loc) => {
    if (loc && Number.isFinite(loc.lng) && Number.isFinite(loc.lat)) {
      state.userLocation = { lng: loc.lng, lat: loc.lat };
    }
    fillRowWithCurrentLocation(rowIdx);
  };

  // app.js's map-empty-space click handler calls this to clear the
  // browse-mode pin selection (row 0 + the "A" marker on the map) so
  // map clicks don't leave sticky state behind. No-op in directions
  // mode — the user has a real itinerary, don't blow it away.
  window.__rtClearBrowsePin = () => clearBrowsePin();
  window.__rtOpenPoiById = (id) => openPoiById(id);
  window.__rtRouteShareUrl = () => routeShareUrl(trip.stops, trip.corridorMiles);

  restoreSharedLinkFromUrl();
}

function clearBrowsePin() {
  if (trip.mode !== 'browse') return;
  if (trip.stops.length === 0 || trip.stops[0] == null) return;
  trip.stops = [];
  removeAllMarkers();
  rerender();
}

/**
 * Add a POI as a stop without going through the search flow. Called by the
 * drawer's per-POI Directions button.
 *
 *  - browse mode + mobile: POI as destination. Origin auto-fills from
 *    state.userLocation (or a fresh getCurrentPosition if not yet known) —
 *    on a phone, "Directions to this campground" almost always means "from
 *    where I'm standing." Soft-keyboard would cover the drawer anyway, so
 *    no auto-focus.
 *  - browse mode + desktop: POI as destination, origin empty + focused.
 *    Desktop users typically plan trips from places other than their
 *    current location, so we don't auto-fill.
 *  - directions mode + destination empty: fill the destination.
 *  - directions mode + destination filled: insert as a via just before the
 *    destination so the user-chosen endpoint stays the endpoint.
 */
function addTripStopFromExternal(stop) {
  if (!stop || !Number.isFinite(stop.lng) || !Number.isFinite(stop.lat)) return;
  const s = { name: stop.name || 'Selected place', lng: stop.lng, lat: stop.lat, kind: stop.kind || 'PLACE' };
  if (trip.mode === 'browse') {
    // Reset stops so any leftover row-0 pin click from browse mode doesn't
    // double up. POI is the destination; origin is the user's location
    // on mobile (when available), empty + focused on desktop.
    trip.stops = [null, s];
    trip.mode = 'directions';
    rerender();
    if (shouldAutoFocus()) {
      // Desktop: focus the empty origin so the user can type their start.
      setTimeout(() => {
        const el = document.querySelector('.tb-row[data-i="0"] .tb-input');
        if (el) { activeRowIdx = 0; el.focus(); }
      }, 0);
    } else {
      // Mobile: try to fill origin from current location. tryFillOriginWithCurrentLocation
      // handles the "already-known", "ask the browser", and "denied/timeout" cases —
      // failures fall through to "origin stays empty," which is fine.
      tryFillOriginWithCurrentLocation();
    }
    return;
  }
  // directions mode
  const last = trip.stops.length - 1;
  if (trip.stops[last] == null) {
    // Destination still empty — fill it. The user clicked "Add stop" but
    // we don't have an endpoint yet, so this is the most useful slot to
    // populate (route fires immediately if origin is also filled).
    setStop(last, s);
  } else {
    if (trip.stops.length >= MAX_STOPS) return;
    trip.stops.splice(last, 0, s);
  }
  rerender();
  if (allStopsFilled()) tryFetchRoute();
}

/**
 * Replace the whole trip with an explicit origin → destination pair and plan
 * it. This is the landing page's hand-off.
 *
 * Deliberately not addTripStopFromExternal: that one appends a POI to whatever
 * the user is already holding and carries browse-mode behaviour with it
 * (auto-focusing an empty origin, asking the browser for a location). The
 * landing already knows both ends, so it states the trip rather than growing
 * one.
 *
 * @param {Array<{name?: string, lng: number, lat: number, kind?: string}>} stops
 * @returns {boolean} false when the topbar is not mounted or the pair is
 *   incomplete — the caller keeps its own UI up rather than leaving the user
 *   on a blank map.
 */
export function startTrip(stops) {
  if (!mapRef) return false;
  const normalized = (stops || [])
    .filter(s => s && Number.isFinite(s.lng) && Number.isFinite(s.lat))
    .map(s => ({
      name: s.name || 'Stop',
      lng: s.lng,
      lat: s.lat,
      kind: s.kind || 'PLACE',
    }));
  if (normalized.length < 2) return false;
  trip.mode = 'directions';
  trip.stops = normalized;
  trip.route = null;
  rerender();
  tryFetchRoute();
  return true;
}

// --- DOM scaffolding -----------------------------------------------------

function injectStyles() {
  const css = `
  /* When the top bar is mounted, hide the side-panel search — there's only
     one search surface. The side panel keeps its layer filters + basemap. */
  body.topbar-active #search-wrap { display: none; }
  body.topbar-active #panel h1 { margin-top: 0; }

  #topbar {
    position: absolute;
    top: max(10px, env(safe-area-inset-top));
    left: 10px;
    z-index: 5;
    width: min(420px, calc(100vw - 80px));
    background: var(--rt-surface);
    border: 1px solid var(--rt-border);
    border-radius: 10px;
    box-shadow: 0 6px 20px rgba(0,0,0,0.40);
    -webkit-font-smoothing: antialiased;
    font-family: inherit;
  }

  /* Stops list */
  #tb-stops { display: flex; flex-direction: column; gap: 4px; padding: 6px; }

  .tb-row {
    display: flex; align-items: center; gap: 8px;
    background: var(--rt-fill-subtle);
    border: 1px solid var(--rt-border-strong);
    border-radius: 8px;
    padding: 0 6px 0 10px;
    transition: border-color 100ms ease, background 100ms ease, opacity 120ms ease;
  }
  .tb-row:focus-within {
    border-color: var(--rt-brand);
    background: var(--rt-fill-hover);
  }
  .tb-row.dragging { opacity: 0.4; }
  .tb-row.drop-target { border-color: var(--rt-brand); border-style: dashed; }
  .tb-row[draggable="true"] { cursor: grab; }
  .tb-row[draggable="true"]:active { cursor: grabbing; }
  .tb-row[draggable="true"] .tb-input { cursor: text; }

  .tb-icon {
    flex-shrink: 0;
    width: 12px; height: 12px;
    border-radius: 50%;
    background: var(--rt-brand);
    box-shadow: 0 0 0 2px var(--rt-fill-subtle);
  }
  .tb-icon.via  { background: var(--rt-map-waypoint); border-radius: 2px; }
  .tb-icon.last { background: ${ROUTE_COLOR_VAR}; border-radius: 2px; }

  .tb-input {
    flex: 1; min-width: 0;
    background: transparent; color: var(--rt-text);
    border: 0; outline: none;
    padding: 9px 0;
    font-size: 13px; font-family: inherit;
  }
  .tb-input::placeholder { color: var(--rt-faint); }

  .tb-x {
    flex-shrink: 0;
    width: 24px; height: 24px;
    background: transparent; border: 0; color: var(--rt-faint);
    border-radius: 4px; cursor: pointer;
    display: grid; place-items: center;
  }
  .tb-x:hover { color: var(--rt-error); background: var(--rt-fill-hover); }

  .tb-locate {
    flex-shrink: 0;
    width: 24px; height: 24px;
    background: transparent; border: 0; color: var(--rt-faint);
    border-radius: 4px; cursor: pointer;
    display: grid; place-items: center;
  }
  .tb-locate:hover { color: var(--rt-brand); background: var(--rt-fill-hover); }
  .tb-locate:disabled { opacity: 0.55; cursor: wait; }

  /* Action row */
  #tb-actions { display: flex; align-items: center; gap: 6px; padding: 0 6px 6px; }

  #tb-add {
    flex-shrink: 0;
    background: transparent;
    border: 1px dashed var(--rt-border-strong);
    color: var(--rt-muted);
    padding: 6px 10px; border-radius: 6px;
    font-size: 12px; font-family: inherit;
    cursor: pointer;
    transition: color 100ms, border-color 100ms;
  }
  #tb-add:hover { color: var(--rt-brand); border-color: var(--rt-brand); }
  #tb-add[hidden] { display: none; }

  #tb-route-summary {
    flex: 0 1 auto;
    min-width: 0;
    color: var(--rt-text);
    font-size: 11px;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  #tb-route-summary:empty { display: none; }
  #tb-route-summary .tb-stat-sep {
    color: var(--rt-faint);
    margin: 0 6px;
  }
  .tb-actions-spacer {
    flex: 1 1 auto;
    min-width: 0;
  }

  .tb-icon-btn {
    width: 36px; height: 36px;
    background: var(--rt-fill-subtle);
    border: 1px solid var(--rt-border-strong);
    color: var(--rt-text);
    border-radius: 8px;
    cursor: pointer;
    display: grid; place-items: center;
    transition: background 100ms, border-color 100ms;
  }
  .tb-icon-btn:hover { background: var(--rt-fill-hover); border-color: var(--rt-brand); }
  .tb-icon-btn.primary {
    background: ${ROUTE_COLOR_VAR};
    border-color: ${ROUTE_COLOR_VAR};
    color: var(--rt-on-accent);
  }
  .tb-icon-btn.primary:hover { background: var(--rt-brand-hover); }
  .tb-icon-btn[hidden] { display: none; }

  /* Dropdown */
  #tb-dropdown {
    max-height: 320px; overflow-y: auto;
    border-top: 1px solid var(--rt-border);
    padding: 4px;
    display: none;
  }
  #tb-dropdown.open { display: block; }
  .tb-section {
    font-size: 9px; text-transform: uppercase; letter-spacing: 0.06em;
    color: var(--rt-faint);
    padding: 6px 8px 2px;
  }
  .tb-result {
    display: flex; align-items: center; gap: 8px;
    padding: 7px 8px; cursor: pointer;
    color: var(--rt-text); font-size: 12px;
    border-radius: 6px;
  }
  .tb-result:hover, .tb-result.active { background: var(--rt-fill-hover); }
  .tb-kind {
    flex-shrink: 0;
    font-size: 9px; text-transform: uppercase;
    padding: 2px 6px; border-radius: 3px;
    color: var(--rt-on-accent); font-weight: 600; letter-spacing: 0.04em;
    min-width: 28px; text-align: center;
  }
  .tb-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .tb-sub { color: var(--rt-muted); font-size: 11px; }

  /* Status */
  #tb-status {
    padding: 6px 12px;
    font-size: 11px; color: var(--rt-muted);
    border-top: 1px solid var(--rt-border);
    display: none;
  }
  #tb-status.visible { display: block; }
  #tb-status.error { color: var(--rt-error); }
  #tb-status .tb-stat-num { color: ${ROUTE_COLOR_VAR}; font-weight: 600; }
  #tb-status .tb-stat-sep { color: var(--rt-faint); margin: 0 8px; }

  /* Corridor radius slider — visible inside the campgrounds collapsible. */
  #tb-corridor {
    display: none;
    align-items: center;
    gap: 10px;
    font-size: 11px; color: var(--rt-muted);
  }
  #tb-corridor.visible { display: flex; }
  #tb-corridor label { white-space: nowrap; }
  #tb-corridor .tb-corridor-value {
    color: var(--rt-text);
    font-variant-numeric: tabular-nums;
    min-width: 44px;
    text-align: right;
  }
  #tb-corridor input[type=range] {
    flex: 1;
    accent-color: ${ROUTE_COLOR_VAR};
    cursor: pointer;
    margin: 0;
  }

  /* Campground results — appears when a route is active. */
  #tb-results {
    display: none;
    flex-direction: column;
    border-top: 1px solid var(--rt-border);
    max-height: min(50vh, 480px);
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  #tb-results.visible { display: flex; }
  .tb-results-body {
    display: flex;
    flex-direction: column;
  }
  .tb-results-head {
    position: sticky; top: 0; z-index: 1;
    padding: 8px 12px;
    background: var(--rt-surface);
    border-bottom: 1px solid var(--rt-border);
    display: flex; align-items: center; gap: 6px;
    font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em;
    color: var(--rt-muted); font-weight: 600;
    cursor: pointer;
    user-select: none;
  }
  .tb-results-head:hover { color: var(--rt-text); }
  .tb-results-head .tb-results-count {
    color: var(--rt-faint); font-weight: 400; text-transform: none; letter-spacing: 0;
    font-size: 11px;
    flex: 1;
  }
  .tb-results-chevron {
    flex-shrink: 0;
    width: 14px; height: 14px;
    transition: transform 150ms ease;
  }
  /* Chevron points down when collapsed, up when expanded. */
  #tb-results.collapsed .tb-results-chevron { transform: rotate(180deg); }
  #tb-results.collapsed .tb-results-body { display: none; }
  .tb-results-controls {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 8px 12px;
    border-bottom: 1px solid var(--rt-border);
    background: var(--rt-surface);
  }
  #tb-results-cards {
    display: flex;
    flex-direction: column;
  }
  .tb-card {
    display: flex; gap: 10px;
    padding: 10px 12px;
    border-bottom: 1px solid var(--rt-border);
    cursor: pointer;
    transition: background 100ms ease;
  }
  .tb-card:last-child { border-bottom: 0; }
  .tb-card:hover { background: var(--rt-fill-hover); }
  .tb-card-dot {
    flex-shrink: 0;
    width: 10px; height: 10px; margin-top: 5px;
    border-radius: 50%;
    box-shadow: 0 0 0 1px rgba(255,255,255,0.16);
  }
  .tb-card-body { flex: 1; min-width: 0; }
  .tb-card-head {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
  }
  .tb-card-name {
    flex: 1;
    min-width: 0;
    color: var(--rt-text);
    font-size: 13px; font-weight: 500; line-height: 1.3;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .tb-card-location {
    flex-shrink: 0;
    color: var(--rt-muted);
    font-size: 11px;
    line-height: 1.3;
    font-weight: 500;
    white-space: nowrap;
  }
  .tb-card-sub {
    color: var(--rt-muted);
    font-size: 11px; line-height: 1.4;
    margin-top: 2px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .tb-card-meta {
    display: flex; gap: 8px; align-items: center; flex-wrap: wrap;
    margin-top: 4px;
    font-size: 11px; color: var(--rt-faint);
    font-variant-numeric: tabular-nums;
  }
  .tb-card-dist { color: ${ROUTE_COLOR_VAR}; font-weight: 500; }
  .tb-card-rating { color: var(--rt-rating); font-weight: 500; }
  .tb-card-sites { color: var(--rt-muted); }
  .tb-card-season { color: var(--rt-muted); }
  .tb-card-empty {
    padding: 14px 12px;
    color: var(--rt-muted); font-size: 12px;
    text-align: center;
  }

  @media (max-width: 768px) {
    /* Reserve 56px on the right for the layers-panel hamburger
       (#panel-toggle is 40px + 10px right offset + breathing room).
       Without this, the topbar spans full-width and covers the toggle. */
    #topbar { left: 8px; right: 56px; width: auto; max-width: none; }
    #tb-results { max-height: 40vh; }
    /* On a phone, the card list eats the whole screen if expanded by default
       — start collapsed so the map is visible after computing a route. */
    #tb-results.collapsed { max-height: none; }
    #tb-actions { gap: 8px; }
    #tb-route-summary { font-size: 10px; }
    /* Tighter corridor row inside the collapsible controls. */
    #tb-corridor { padding: 6px 10px; gap: 6px; }
    #tb-corridor label { display: none; }
    .tb-results-controls { padding: 8px 10px; }
  }
  `;
  const tag = document.createElement('style');
  tag.textContent = css;
  document.head.appendChild(tag);
}

function injectDom() {
  document.body.classList.add('topbar-active');
  const el = document.createElement('div');
  el.id = 'topbar';
  el.innerHTML = `
    <div id="tb-stops"></div>
    <div id="tb-auth" hidden></div>
    <div id="tb-alerts"></div>
    <div id="tb-actions">
      <button id="tb-add" type="button" hidden>+ Add stop</button>
      <span id="tb-route-summary" aria-live="polite"></span>
      <div class="tb-actions-spacer"></div>
      <button id="tb-directions" class="tb-icon-btn primary" type="button" hidden title="Get directions" aria-label="Get directions">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10l-7 7-3-3-9 9"/><path d="M14 10h7v7"/></svg>
      </button>
      <button id="tb-clear" class="tb-icon-btn" type="button" hidden title="Clear" aria-label="Clear">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div id="tb-dropdown"></div>
    <div id="tb-status"></div>
    <div id="tb-results">
      <div class="tb-results-head" role="button" tabindex="0" aria-expanded="false"></div>
      <div class="tb-results-body">
        <div class="tb-results-controls">
          <div id="tb-corridor">
            <label for="tb-corridor-range">Corridor</label>
            <input
              type="range"
              id="tb-corridor-range"
              min="${CORRIDOR_MIN_MILES}"
              max="${CORRIDOR_MAX_MILES}"
              step="${CORRIDOR_STEP_MILES}"
              value="${CORRIDOR_DEFAULT_MILES}"
              aria-label="Corridor radius in miles"
            >
            <span class="tb-corridor-value" id="tb-corridor-value">${CORRIDOR_DEFAULT_MILES} mi</span>
          </div>
        </div>
        <div id="tb-results-cards"></div>
      </div>
    </div>
  `;
  document.body.appendChild(el);
}

// --- events --------------------------------------------------------------

function bindEvents() {
  document.getElementById('tb-add').addEventListener('click', onAddStop);
  document.getElementById('tb-directions').addEventListener('click', onDirections);
  document.getElementById('tb-clear').addEventListener('click', onClearAll);

  document.getElementById('tb-dropdown').addEventListener('mousedown', (e) => {
    const item = e.target.closest('.tb-result');
    if (!item) return;
    pickResult(currentResults[Number(item.dataset.i)]);
    e.preventDefault();
  });

  // Corridor radius slider. 'input' fires continuously while dragging — we
  // recompute the polygon + update the on-map fill on every tick so the
  // user sees the corridor expand/shrink in real time. The /api/pois +
  // /api/superchargers refetch is debounced 250ms inside app.js, so the
  // server isn't pummeled mid-drag.
  const range = document.getElementById('tb-corridor-range');
  range.addEventListener('input', (e) => {
    trip.corridorMiles = Number(e.target.value);
    document.getElementById('tb-corridor-value').textContent = `${trip.corridorMiles} mi`;
    updateCorridor();
  });

  document.addEventListener('click', (e) => {
    if (!document.getElementById('topbar').contains(e.target)) {
      closeDropdown();
      blurTopbarInputOnMobile();
    }
  });

  mapRef.getCanvas().addEventListener('pointerdown', blurTopbarInputOnMobile, { passive: true });
}

function bindPinClicks() {
  // When the user clicks a pin on the map, fill the active row's input
  // with the pin's name. The existing layer click handlers in layers.js
  // still run (drawer/popup opens as before) — this is purely additive.
  // We listen for ANY click on the map and use queryRenderedFeatures to
  // find the topmost interactive pin under the cursor.
  const layers = ['cg-points-hit', 'sc-points-hit', 'pf-points-hit', 'np-pts-hit', 'sp-pts-hit'];
  mapRef.on('click', (e) => {
    if (suppressPinClick) return;
    const present = layers.filter(id => mapRef.getLayer(id));
    if (!present.length) return;
    const hits = mapRef.queryRenderedFeatures(e.point, { layers: present });
    if (!hits.length) return;
    const f = hits[0];
    const lng = f.geometry?.coordinates?.[0];
    const lat = f.geometry?.coordinates?.[1];
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return;
    const p = f.properties || {};
    const name = p.name || p.Unit_Nm || 'Selected place';
    const kind = pinKindFromLayer(f.layer.id);
    onPinClickedFromMap({ name, lng, lat, kind, pinId: f.id });
  });
}

function pinKindFromLayer(layerId) {
  if (layerId.startsWith('cg')) return 'CG';
  if (layerId.startsWith('sc')) return 'SC';
  if (layerId.startsWith('pf')) return 'PF';
  if (layerId.startsWith('np')) return 'NP';
  if (layerId.startsWith('sp')) return 'SP';
  return 'PLACE';
}

function onPinClickedFromMap(pin) {
  // In browse mode (no trip yet), populate row 0 if empty, otherwise
  // replace it. The existing drawer/popup opens regardless because
  // layers.js click handlers run independently.
  //
  // In directions mode the click is a no-op for waypoints — the user
  // picked a route already, and clicking a campground or supercharger
  // along the corridor should let them open the drawer (to see hours,
  // pricing, navigate) without rewriting their itinerary. Waypoints
  // only change via the search/geocode flow now.
  if (trip.mode !== 'browse') return;
  setStop(0, { name: pin.name, lng: pin.lng, lat: pin.lat, kind: pin.kind });
  rerender();
}

// --- input + dropdown ---------------------------------------------------

function onInput(rowIdx, value) {
  activeRowIdx = rowIdx;
  clearTimeout(geocodeTimer);
  if (geocodeAbort) { geocodeAbort.abort(); geocodeAbort = null; }
  const q = value.trim();
  if (q.length < 2) {
    currentResults = [];
    closeDropdown();
    return;
  }
  geocodeTimer = setTimeout(() => runQuery(q), GEOCODE_DEBOUNCE_MS);
}

async function runQuery(q) {
  const pinResults = pinSearch(q);
  geocodeAbort = new AbortController();
  const signal = geocodeAbort.signal;
  const c = mapRef.getCenter();
  const proximity = state.userLocation
    ? `${state.userLocation.lng},${state.userLocation.lat}`
    : `${c.lng.toFixed(4)},${c.lat.toFixed(4)}`;

  // Backend POI search runs in parallel with geocode. The local pinSearch
  // only knows what's been loaded into the current viewport; the backend
  // path lets the user find a POI nationwide (e.g. "upper pines" while
  // looking at the East Coast).
  const [backendPoiResults, geocodeResults] = await Promise.all([
    searchPois(q, { limit: 8, categories: ['campground'], signal })
      .then(j => (j.results || []).map(it => ({
        kind: kindForCategory(it.category),
        name: it.name,
        sub: it.region || '',
        lng: it.lng, lat: it.lat,
        category: it.category,
        poiId: it.id,
        source: 'backend-poi',
      })))
      .catch(e => { if (e.name !== 'AbortError') console.warn('[topbar] poi search failed', e); return []; }),
    geocode(q, { autocomplete: true, limit: 5, proximity, signal })
      .then(j => (j.results || []).map(it => ({
        kind: it.place_type === 'address' ? 'ADDR' : 'PLACE',
        name: it.place_name,
        lng: it.lng, lat: it.lat,
        source: 'geocode',
      })))
      .catch(e => { if (e.name !== 'AbortError') console.warn('[topbar] geocode failed', e); return []; }),
  ]);

  // Dedupe: a backend POI hit might also be in the local pin index after a
  // viewport visit. Prefer the local pin entry (carries onSelect, faster
  // pick path) when ids overlap.
  const localIds = new Set(pinResults.filter(r => r.pinItem?.id != null).map(r => r.pinItem.id));
  const backendDeduped = backendPoiResults.filter(r => !localIds.has(r.poiId));

  // POI-first: locally indexed > backend-search > geocode. Local pin items
  // already carry onSelect; backend-search is a network round-trip away from
  // a synthesized click. Geocoding stays the long-tail fallback.
  currentResults = [...pinResults, ...backendDeduped, ...geocodeResults].slice(0, 12);
  dropdownIdx = -1;
  renderDropdown();
}

function kindForCategory(category) {
  switch (category) {
    case 'campground': return 'CG';
    case 'national-park': return 'NP';
    case 'state-park': return 'SP';
    case 'planet_fitness_location': return 'PF';
    case 'planet-fitness': return 'PF';
    case 'tesla_supercharger': return 'SC';
    case 'supercharger': return 'SC';
    default: return 'PLACE';
  }
}

function pinSearch(q) {
  const idx = pinSearchIndex?.();
  if (!idx?.length) return [];
  const ql = q.toLowerCase();
  const terms = ql.split(/\s+/);
  const scored = [];
  for (const it of idx) {
    const hay = (it.name + ' ' + (it.sub || '')).toLowerCase();
    if (!terms.every(t => hay.includes(t))) continue;
    const pos = it.name.toLowerCase().indexOf(ql);
    const nameScore = pos < 0 ? 500 : pos;
    let distKm = null;
    if (state.userLocation && Number.isFinite(it.lat) && Number.isFinite(it.lng)) {
      distKm = distanceKm(state.userLocation.lat, state.userLocation.lng, it.lat, it.lng);
    }
    scored.push({ it, nameScore, distKm });
  }
  scored.sort((a, b) => a.nameScore - b.nameScore);
  return scored.slice(0, 7).map(s => ({
    kind: s.it.kind, name: s.it.name, sub: s.it.sub,
    lng: s.it.lng, lat: s.it.lat,
    source: 'pin', pinItem: s.it, distKm: s.distKm,
  }));
}

function renderDropdown() {
  const out = document.getElementById('tb-dropdown');
  if (!currentResults.length) { closeDropdown(); return; }
  let html = '';
  let prevSection = null;
  currentResults.forEach((r, i) => {
    const section =
      r.source === 'geocode' ? 'Places' :
      r.source === 'backend-poi' ? 'POIs' :
      'Map pins';
    if (section !== prevSection) {
      html += `<div class="tb-section">${section}</div>`;
      prevSection = section;
    }
    const color = kindColor(r.kind);
    const sub = r.sub ? ` <span class="tb-sub">${escapeHtml(r.sub)}</span>` : '';
    const dist = r.distKm != null ? ` <span class="tb-sub">${formatDist(r.distKm)}</span>` : '';
    html += `<div class="tb-result" data-i="${i}">
      <span class="tb-kind" style="background:${color}">${r.kind}</span>
      <span class="tb-name">${escapeHtml(r.name)}${sub}${dist}</span>
    </div>`;
  });
  out.innerHTML = html;
  out.classList.add('open');
}

function closeDropdown() {
  document.getElementById('tb-dropdown').classList.remove('open');
}

function refreshDropdownActive() {
  const items = document.querySelectorAll('#tb-dropdown .tb-result');
  items.forEach((el, i) => el.classList.toggle('active', i === dropdownIdx));
  if (items[dropdownIdx]) items[dropdownIdx].scrollIntoView({ block: 'nearest' });
}

function onInputKey(e, rowIdx) {
  if (!currentResults.length) return;
  if (e.key === 'ArrowDown') { dropdownIdx = Math.min(dropdownIdx + 1, currentResults.length - 1); refreshDropdownActive(); e.preventDefault(); }
  else if (e.key === 'ArrowUp') { dropdownIdx = Math.max(dropdownIdx - 1, 0); refreshDropdownActive(); e.preventDefault(); }
  else if (e.key === 'Enter') {
    activeRowIdx = rowIdx;
    if (dropdownIdx >= 0) pickResult(currentResults[dropdownIdx]);
    else if (currentResults.length) pickResult(currentResults[0]);
    e.preventDefault();
  } else if (e.key === 'Escape') {
    closeDropdown();
    e.target.blur();
  }
}

// --- pick + route -------------------------------------------------------

function pickResult(result) {
  if (!result) return;
  closeDropdown();
  blurTopbarInputOnMobile();

  // Every dropdown pick fills the row the user was typing in. The drawer's
  // "Add stop" button is the path that inserts a *new* via (via
  // addTripStopFromExternal); pickResult always overwrites the active row.
  const i = Math.max(activeRowIdx, 0);
  setStop(i, {
    name: result.name,
    lng: result.lng, lat: result.lat,
    kind: result.kind,
    pinItem: result.pinItem || null,
  });

  // In browse mode, fly to the place. POI hits also open the drawer; for
  // a geocoded result there's nothing more to render than the flyTo.
  if (trip.mode === 'browse') {
    if (result.source === 'pin' && result.pinItem) {
      fitAndSelect(result.pinItem);
    } else if (result.source === 'backend-poi') {
      flyAndOpenBackendPoi(result);
    } else {
      const zoom = result.kind === 'ADDR' ? 14 : 10;
      mapRef.flyTo({ center: [result.lng, result.lat], zoom, speed: 1.6 });
    }
  }

  rerender();
  if (trip.mode === 'directions' && allStopsFilled()) tryFetchRoute();
}

const BACKEND_POI_TOGGLES = {
  CG: 'f-cg-federal',
  NP: 'f-np',
  SP: 'f-sp',
  PF: 'f-pf',
  SC: 'f-open',
};

/**
 * Fly to a backend search hit and open its drawer by id. Backend POI hits
 * can be outside the current slim bbox payload, so waiting for a rendered
 * layer feature is inherently racy; the drawers already know how to hydrate
 * slim id-only features through GET /api/pois/{id}.
 */
function flyAndOpenBackendPoi(result) {
  const toggleId = BACKEND_POI_TOGGLES[result.kind];
  if (toggleId) {
    const el = document.getElementById(toggleId);
    if (el && !el.checked) {
      el.checked = true;
      el.dispatchEvent(new Event('change'));
    }
  }
  const center = [result.lng, result.lat];
  // Zoom 13: tight enough that the pin is the only feature near the click
  // point but still shows a bit of context. Higher zooms (14+) sometimes
  // hide adjacent pins via spider-collision.
  mapRef.flyTo({ center, zoom: 13, speed: 1.6 });
  openBackendPoiDrawer(result);

  mapRef.once('moveend', () => {
    // Nudge the bbox refresh in case the auto moveend handler hasn't fired
    // yet (it's debounced inside app.js). Idempotent — coalesces with any
    // already-pending refresh.
    if (typeof window.__rtRefreshBbox === 'function') window.__rtRefreshBbox();
  });
}

function backendPoiFeature(result) {
  return {
    type: 'Feature',
    id: result.poiId,
    geometry: { type: 'Point', coordinates: [result.lng, result.lat] },
    properties: { category: result.category },
  };
}

function openBackendPoiDrawer(result) {
  const f = backendPoiFeature(result);
  const lngLat = { lng: result.lng, lat: result.lat };
  switch (result.kind) {
    case 'CG':
      openCampgroundDrawer(f);
      break;
    case 'NP':
      openParkDrawer('np', f, lngLat);
      break;
    case 'SP':
      openParkDrawer('sp', f, lngLat);
      break;
    case 'PF':
      openPlanetFitnessDrawer(f);
      break;
    case 'SC':
      openSuperchargerDrawer(f);
      break;
  }
}

function enablePoiToggle(category, feature) {
  let id = null;
  if (category === 'campground') {
    const cat = campgroundLayerCategory(feature?.properties?.subcategory || feature?.properties?.category);
    id = `f-cg-${cat === 'other' ? 'federal' : cat}`;
  } else if (category === 'national-park') id = 'f-np';
  else if (category === 'state-park') id = 'f-sp';
  else if (category === 'planet_fitness_location' || category === 'planet-fitness') id = 'f-pf';
  else if (category === 'tesla_supercharger' || category === 'supercharger') id = 'f-open';
  const el = id ? document.getElementById(id) : null;
  if (el && !el.checked) {
    el.checked = true;
    el.dispatchEvent(new Event('change'));
  }
}

function openSharedPoiFeature(detail) {
  const category = detail?.properties?.category;
  const feature = flattenHydratedPoi(detail);
  const [lng, lat, bbox] = geomCenter(feature.geometry);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return;

  enablePoiToggle(category, feature);
  const isPark = category === 'national-park' || category === 'state-park';
  mapRef.flyTo({ center: [lng, lat], zoom: isPark ? zoomForBbox(bbox) : 13, speed: 1.6 });
  const lngLat = { lng, lat };
  switch (category) {
    case 'campground':
      openCampgroundDrawer(feature);
      break;
    case 'national-park':
      openParkDrawer('np', feature, lngLat);
      break;
    case 'state-park':
      openParkDrawer('sp', feature, lngLat);
      break;
    case 'planet_fitness_location':
    case 'planet-fitness':
      openPlanetFitnessDrawer(feature);
      break;
    case 'tesla_supercharger':
    case 'supercharger':
      openSuperchargerDrawer(feature);
      break;
  }
}

async function openPoiById(id) {
  if (id == null || id === '') return;
  try {
    openSharedPoiFeature(await fetchPoiDetail(id));
  } catch (e) {
    console.warn('[topbar] shared POI restore failed', e);
    showStatus('Could not open shared POI link.', { error: true });
  }
}

function setStop(i, stop) {
  while (trip.stops.length <= i) trip.stops.push(null);
  trip.stops[i] = stop;
}

function allStopsFilled() {
  // Pending placeholders (e.g. "Locating you…" while geolocation is resolving)
  // count as "not filled" — we don't want tryFetchRoute / corridor refresh
  // firing on (0,0) coords. The real entry will replace it shortly.
  return trip.stops.length >= 2 && trip.stops.every(s => s != null && !s._pending);
}

function onDirections() {
  trip.mode = 'directions';
  while (trip.stops.length < 2) trip.stops.push(null);
  rerender();
  // Focus the first empty input (desktop only; mobile keyboard would cover the drawer).
  if (!shouldAutoFocus()) return;
  const firstEmpty = trip.stops.findIndex(s => s == null);
  setTimeout(() => {
    const el = document.querySelector(`.tb-row[data-i="${firstEmpty}"] .tb-input`);
    if (el) { activeRowIdx = firstEmpty; el.focus(); }
  }, 0);
}

function updateRouteAddressUrl() {
  replaceVisibleUrl(routeShareUrl(trip.stops, trip.corridorMiles));
}

function onAddStop() {
  if (trip.stops.length >= MAX_STOPS) return;
  trip.stops.push(null);
  rerender();
  if (!shouldAutoFocus()) return;
  const i = trip.stops.length - 1;
  setTimeout(() => {
    const el = document.querySelector(`.tb-row[data-i="${i}"] .tb-input`);
    if (el) { activeRowIdx = i; el.focus(); }
  }, 0);
}

function onClearAll() {
  if (trip.routeAbort) trip.routeAbort.abort();
  trip.mode = 'browse';
  trip.stops = [];
  trip.route = null;
  trip.generation++;
  removeRouteLayer();
  removeAllMarkers();
  hideStatus();
  rerender();
  notifyCorridorChanged();
  tripResults.cards = [];
  tripResults.byId.clear();
  renderResults();
  clearVisibleShareUrl();
}

function onRowX(i, wasFilled) {
  // Vias are removable; origin (0) and destination (last) are structural
  // slots that always exist in directions mode, so we clear-but-keep them.
  const isStructural =
    trip.mode !== 'directions' ||
    i === 0 ||
    i === trip.stops.length - 1;

  if (wasFilled && isStructural) {
    // Filled origin/destination → clear text + value, keep the row
    trip.stops[i] = null;
    rerender();
    removeRouteLayer();
    hideStatus();
    notifyCorridorChanged();
    if (shouldAutoFocus()) {
      setTimeout(() => {
        const el = document.querySelector(`.tb-row[data-i="${i}"] .tb-input`);
        if (el) { activeRowIdx = i; el.focus(); }
      }, 0);
    }
    return;
  }

  // Via (filled or empty) → remove the row entirely. Also handles browse-mode
  // single-row clears and the "remove an extra empty stop" case.
  if (trip.mode === 'directions' && trip.stops.length <= 2) {
    // Can't drop below origin + destination — clear text but keep the row.
    if (wasFilled) {
      trip.stops[i] = null;
      rerender();
      removeRouteLayer();
      hideStatus();
      notifyCorridorChanged();
    }
    return;
  }
  trip.stops.splice(i, 1);
  if (trip.stops.length === 0) {
    onClearAll();
    return;
  }
  // Only one stop left → fall back to browse mode. Directions mode is
  // meaningless with a single waypoint; the lone stop becomes the current
  // browse selection (or null/empty if the removed row was the filled one).
  if (trip.stops.length === 1) {
    if (trip.routeAbort) trip.routeAbort.abort();
    trip.mode = 'browse';
    trip.route = null;
    trip.generation++;
    removeRouteLayer();
    hideStatus();
    notifyCorridorChanged();
    rerender();
    return;
  }
  rerender();
  if (allStopsFilled()) tryFetchRoute();
  else { removeRouteLayer(); hideStatus(); notifyCorridorChanged(); }
}

/** Trigger the corridor card-list refresh. Debounced via the AbortController
 *  pattern in refreshOnRoutePois — calling this multiple times in quick
 *  succession (radius slider drag) settles on the latest call. The map
 *  rendering pipeline runs independently through __rtRefreshBbox; both
 *  fire because corridor changes affect both consumers. */
function notifyCorridorChanged() {
  refreshOnRoutePois();
  if (typeof window.__rtRefreshBbox === 'function') {
    window.__rtRefreshBbox();
  }
}

// In-flight on-route fetch. Aborted + replaced on every refresh so a
// rapid radius drag doesn't pile up requests; we settle on the latest.
let onRouteAbort = null;

const ON_ROUTE_DEBOUNCE_MS = 250;
let onRouteDebounce = null;

/** Fetch /api/pois/on-route for the active route and hand the slim
 *  features to setTripPois. No-op when no route is active; fires
 *  through the same setTripPois clear path so the card list empties. */
function refreshOnRoutePois() {
  clearTimeout(onRouteDebounce);
  onRouteDebounce = setTimeout(refreshOnRoutePoisNow, ON_ROUTE_DEBOUNCE_MS);
}

async function refreshOnRoutePoisNow() {
  if (trip.mode !== 'directions' || !allStopsFilled()) {
    setTripPois([]);
    if (typeof window.__rtSetRoutePois === 'function') window.__rtSetRoutePois([]);
    if (typeof window.__rtRefreshBbox === 'function') window.__rtRefreshBbox();
    return;
  }
  if (typeof window.__rtSetRoutePois === 'function' && trip.route) {
    window.__rtSetRoutePois([]);
  }
  if (onRouteAbort) onRouteAbort.abort();
  onRouteAbort = new AbortController();
  const signal = onRouteAbort.signal;

  let fc;
  try {
    fc = await fetchOnRoutePois({
      waypoints: trip.stops.map(s => ({ lat: s.lat, lng: s.lng })),
      radiusMiles: trip.corridorMiles,
      categories: ['campground'],
      signal,
    });
  } catch (e) {
    if (e instanceof HttpError) {
      console.warn('[topbar] /api/pois/on-route failed', e.status);
      return;
    }
    if (e.name !== 'AbortError') console.warn('[topbar] on-route fetch failed', e);
    return;
  }
  if (signal.aborted) return;
  setTripPois(fc.features || []);
  if (typeof window.__rtSetRoutePois === 'function') window.__rtSetRoutePois(fc.features || []);
}

// --- route fetch + render ----------------------------------------------

async function tryFetchRoute() {
  if (!allStopsFilled()) return;
  if (trip.routeAbort) trip.routeAbort.abort();
  trip.routeAbort = new AbortController();
  const myGen = ++trip.generation;
  showStatus('Computing route…');

  let r;
  try {
    r = await requestRoute({
      stops: trip.stops,
      radiusMiles: trip.corridorMiles,
      signal: trip.routeAbort.signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') return;
    showStatus('Network error', { error: true });
    return;
  }
  if (myGen !== trip.generation) return;

  if (!r.ok) {
    let msg;
    try {
      const j = await r.json();
      if (j.error === 'duplicate_adjacent') msg = 'Two adjacent stops are the same.';
      else if (j.error === 'too_few_points') msg = 'Need at least 2 stops.';
      else if (j.error === 'too_many_points') msg = 'Too many stops.';
      else if (j.error === 'routing_unavailable') msg = 'Routing temporarily unavailable.';
      else msg = `Routing error (${r.status})`;
    } catch (_) { msg = `Routing error (${r.status})`; }
    showStatus(msg, { error: true });
    return;
  }
  const fc = await r.json();
  if (myGen !== trip.generation) return;
  trip.route = fc;

  drawRoute();
  fitMapToRoute();
  showRouteSummary();
  updateRouteAddressUrl();
  rerender();
  notifyCorridorChanged();
  renderResults();
}

function drawRoute() {
  removeRouteLayer();
  if (!trip.route) return;
  const lineGeo = trip.route.features[0].geometry;
  const serverCorridor = trip.route.features.find(f => f?.properties?.role === 'corridor')?.geometry;

  // Prefer the backend corridor polygon returned by /api/route so the first
  // rendered fill matches the server-side /api/pois/on-route spatial filter.
  // Turf remains the fallback for mocked route responses and slider updates.
  trip.corridor = serverCorridor || computeCorridor(lineGeo);

  if (trip.corridor) {
    mapRef.addSource('trip-corridor', {
      type: 'geojson',
      data: { type: 'Feature', geometry: trip.corridor, properties: {} },
    });
    mapRef.addLayer({
      id: 'trip-corridor-fill',
      source: 'trip-corridor',
      type: 'fill',
      paint: {
        'fill-color': routeColor(),
        'fill-opacity': 0.08,
      },
    }, firstSymbolLayerId());
  }

  mapRef.addSource('trip-route', {
    type: 'geojson',
    data: { type: 'Feature', geometry: lineGeo, properties: {} },
  });
  mapRef.addLayer({
    id: 'trip-route-line',
    source: 'trip-route',
    type: 'line',
    layout: { 'line-join': 'round', 'line-cap': 'round' },
    paint: { 'line-color': routeColor(), 'line-width': 5, 'line-opacity': 0.85 },
  });

  document.getElementById('tb-corridor').classList.add('visible');
}

function removeRouteLayer() {
  if (!mapRef) return;
  if (mapRef.getLayer('trip-route-line')) mapRef.removeLayer('trip-route-line');
  if (mapRef.getSource('trip-route')) mapRef.removeSource('trip-route');
  if (mapRef.getLayer('trip-corridor-fill')) mapRef.removeLayer('trip-corridor-fill');
  if (mapRef.getSource('trip-corridor')) mapRef.removeSource('trip-corridor');
  trip.corridor = null;
  const slider = document.getElementById('tb-corridor');
  if (slider) slider.classList.remove('visible');
  const summary = document.getElementById('tb-route-summary');
  if (summary) summary.innerHTML = '';
}

/** Recompute the corridor polygon from the existing route + current radius,
 *  push it to the on-map source, and tell app.js to refetch. Cheap to call
 *  on every slider tick — turf.buffer + turf.simplify run in <10ms for the
 *  routes we ship; the network refetch is debounced inside app.js. */
function updateCorridor() {
  if (!trip.route) return;
  const lineGeo = trip.route.features[0].geometry;
  trip.corridor = computeCorridor(lineGeo);
  const src = mapRef?.getSource('trip-corridor');
  if (trip.corridor && src) {
    src.setData({ type: 'Feature', geometry: trip.corridor, properties: {} });
  }
  updateRouteAddressUrl();
  notifyCorridorChanged();
}

/**
 * Buffer the route polyline into a corridor polygon, then simplify so the
 * polygon stays light enough to redraw while the user drags the slider.
 * Returns a GeoJSON Polygon or MultiPolygon geometry
 * geometry, or null if turf is unavailable / the input is degenerate.
 */
function computeCorridor(lineGeo) {
  if (!window.turf || !lineGeo?.coordinates?.length) return null;
  try {
    const buffered = window.turf.buffer(
      { type: 'Feature', geometry: lineGeo, properties: {} },
      trip.corridorMiles,
      { units: 'miles' },
    );
    if (!buffered?.geometry) return null;
    // Simplify keeps the body small. tolerance is in degrees; ~0.02 ≈ 2km
    // at mid-latitudes, which is invisible at typical zoom levels.
    const simplified = window.turf.simplify(buffered, {
      tolerance: CORRIDOR_SIMPLIFY_TOLERANCE,
      highQuality: false,
    });
    return simplified?.geometry || buffered.geometry;
  } catch (e) {
    console.warn('[topbar] computeCorridor failed', e);
    return null;
  }
}

/**
 * Find the first symbol layer in the current style so we can insert the
 * corridor fill underneath it (above pin layers but below labels). MapLibre
 * convention; matches how the existing layers do it.
 */
function firstSymbolLayerId() {
  const layers = mapRef.getStyle()?.layers || [];
  for (const l of layers) {
    if (l.type === 'symbol') return l.id;
  }
  return undefined;
}

function fitMapToRoute() {
  if (!trip.route) return;
  const coords = trip.route.features[0].geometry.coordinates;
  const bounds = coords.reduce(
    (b, c) => b.extend(c),
    new maplibregl.LngLatBounds(coords[0], coords[0]),
  );
  mapRef.fitBounds(bounds, { padding: 100, duration: 700 });
}

function showRouteSummary() {
  const props = trip.route?.features?.[0]?.properties;
  if (!props) return;
  const distKm = (props.distance_m ?? 0) / 1000;
  const durHrs = (props.duration_s ?? 0) / 3600;
  const legs = props.legs || [];
  const head = `<strong>${distKm.toFixed(0)} km</strong>` +
    `<span class="tb-stat-sep">·</span>${formatDuration(durHrs)}`;
  const summaryEl = document.getElementById('tb-route-summary');
  if (summaryEl) summaryEl.innerHTML = head;
  // Per-leg breakdown is only useful for 3+ stops; it stays in the status
  // slot below. Hide status entirely for the simple 2-stop case.
  if (legs.length > 1) {
    let body = '<div style="font-size:10px; color:var(--rt-faint);">';
    legs.forEach((l, i) => {
      const km = (l.distance_m / 1000).toFixed(0);
      const min = Math.round(l.duration_s / 60);
      body += `${escapeHtml(stopLabel(i))} → ${escapeHtml(stopLabel(i + 1))}: ${km} km · ${min} min<br>`;
    });
    body += '</div>';
    showStatus(body);
  } else {
    hideStatus();
  }
}

function stopLabel(i) {
  const s = trip.stops[i];
  if (!s) return `Stop ${i + 1}`;
  const first = s.name.split(/[\s,]+/)[0];
  return first.length > 18 ? first.slice(0, 16) + '…' : first;
}

// --- markers ------------------------------------------------------------

function syncMarkers() {
  while (trip.endpointMarkers.length > trip.stops.length) {
    const m = trip.endpointMarkers.pop();
    if (m) m.remove();
  }
  trip.stops.forEach((stop, i) => {
    if (!stop || stop._pending) {
      // Pending placeholder (e.g. "Locating you…") — don't draw a marker
      // at (0,0); the real coords arrive when geolocation resolves and a
      // subsequent rerender will place the marker correctly.
      if (trip.endpointMarkers[i]) {
        trip.endpointMarkers[i].remove();
        trip.endpointMarkers[i] = null;
      }
      return;
    }
    const role = (i === 0) ? 'origin' : (i === trip.stops.length - 1 ? 'last' : 'via');
    const color = role === 'origin' ? 'var(--rt-brand)' :
      (role === 'last' ? ROUTE_COLOR_VAR : 'var(--rt-map-waypoint)');
    const shape = role === 'last' ? 'square' : 'circle';
    const label = role === 'origin' ? 'A' :
      (role === 'last' ? String.fromCharCode(65 + Math.min(trip.stops.length - 1, 25)) : String(i));
    if (trip.endpointMarkers[i]) trip.endpointMarkers[i].remove();
    const wrap = document.createElement('div');
    wrap.style.cssText = `
      width: 26px; height: 26px;
      background: ${color}; color: var(--rt-on-accent);
      border: 2.5px solid var(--rt-map-pin-stroke);
      border-radius: ${shape === 'circle' ? '50%' : '4px'};
      box-shadow: 0 2px 6px rgba(0,0,0,0.5);
      display: grid; place-items: center;
      font-weight: 700; font-size: 12px; font-family: -apple-system, sans-serif;
    `;
    wrap.textContent = label;
    trip.endpointMarkers[i] = new maplibregl.Marker({ element: wrap, anchor: 'center' })
      .setLngLat([stop.lng, stop.lat])
      .addTo(mapRef);
  });
}

function removeAllMarkers() {
  trip.endpointMarkers.forEach(m => { if (m) m.remove(); });
  trip.endpointMarkers = [];
}

// --- rendering ---------------------------------------------------------

function rerender() { renderRows(); syncMarkers(); }

function renderRows() {
  const stops = document.getElementById('tb-stops');
  const isDirections = trip.mode === 'directions';
  // Always render at least 1 row (browse mode) or trip.stops.length rows.
  const rows = Math.max(trip.stops.length, 1);

  // Preserve focus only when an input is actually focused. `activeRowIdx`
  // can remain set after blur; using it here would steal focus back during
  // ordinary map/drawer rerenders and reopen the mobile keyboard.
  const focusedEl = activeTopbarInput();
  const focusedI = focusedEl ? Number(focusedEl.dataset.i) : -1;
  const focusedSel = focusedEl ? { start: focusedEl.selectionStart, end: focusedEl.selectionEnd } : null;

  let html = '';
  for (let i = 0; i < rows; i++) {
    const stop = trip.stops[i];
    const isFirst = i === 0;
    const isLast = i === rows - 1;
    const role = isDirections ? (isFirst ? '' : (isLast ? 'last' : 'via')) : '';
    const placeholder = !isDirections
      ? 'Search a place or pin…'
      : (isFirst ? 'Origin' : (isLast ? 'Destination' : `Stop ${i}`));
    const value = stop ? stop.name : '';
    const isFilled = !!stop;
    const canRemove = !isDirections || rows >= 3;
    const showX = isFilled || canRemove;
    const draggable = (isDirections && rows >= 2) ? 'true' : 'false';
    const locating = !!stop?._pending;

    html += `
      <div class="tb-row" data-i="${i}" draggable="${draggable}">
        <span class="tb-icon ${role}"></span>
        <input class="tb-input" type="text" autocomplete="off"
               placeholder="${escapeHtml(placeholder)}"
               aria-label="${escapeHtml(placeholder)}"
               value="${escapeHtml(value)}"
               data-i="${i}">
        <button class="tb-locate" type="button" data-i="${i}" ${locating ? 'disabled' : ''}
                title="Use current location" aria-label="Use current location">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 2v3"/><path d="M12 19v3"/><path d="M2 12h3"/><path d="M19 12h3"/><path d="M18.4 5.6l-2.1 2.1"/><path d="M7.7 16.3l-2.1 2.1"/><path d="M5.6 5.6l2.1 2.1"/><path d="M16.3 16.3l2.1 2.1"/></svg>
        </button>
        ${showX ? `<button class="tb-x" type="button" data-i="${i}" data-filled="${isFilled ? '1' : '0'}" aria-label="${isFilled ? 'Clear' : 'Remove stop'}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>` : ''}
      </div>`;
  }
  stops.innerHTML = html;

  stops.querySelectorAll('.tb-input').forEach((inp) => {
    const i = Number(inp.dataset.i);
    inp.addEventListener('input', (e) => onInput(i, e.target.value));
    inp.addEventListener('focus', () => {
      activeRowIdx = i;
      // User tapped a "Locating you…" placeholder — they want to type
      // their own origin. Drop the placeholder so the in-flight
      // geolocation callback can't clobber what they're typing.
      const cur = trip.stops[i];
      if (cur?._pending) {
        trip.stops[i] = null;
        inp.value = '';
        inp.placeholder = i === 0 ? 'Origin' : (i === trip.stops.length - 1 ? 'Destination' : `Stop ${i}`);
      }
    });
    inp.addEventListener('keydown', (e) => onInputKey(e, i));
  });
  stops.querySelectorAll('.tb-x').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      const i = Number(btn.dataset.i);
      const wasFilled = btn.dataset.filled === '1';
      onRowX(i, wasFilled);
      e.preventDefault();
      e.stopPropagation();
    });
  });
  stops.querySelectorAll('.tb-locate').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      fillRowWithCurrentLocation(Number(btn.dataset.i));
      e.preventDefault();
      e.stopPropagation();
    });
  });
  stops.querySelectorAll('.tb-row[draggable="true"]').forEach(bindRowDrag);

  // Restore focus
  if (focusedI >= 0 && focusedSel) {
    const el = stops.querySelector(`.tb-row[data-i="${focusedI}"] .tb-input`);
    if (el) {
      el.focus();
      try { el.setSelectionRange(focusedSel.start, focusedSel.end); } catch (_) {}
    }
  }

  // Buttons visibility. The search-bar Directions button is the entry
  // point in browse mode whenever row 0 is filled (works for geocoded
  // picks where there's no drawer Directions button to click). In
  // directions mode it stays hidden — auto-fetch covers that flow.
  document.getElementById('tb-directions').hidden = isDirections || !trip.stops[0];
  document.getElementById('tb-clear').hidden = !trip.stops[0];
  document.getElementById('tb-add').hidden = !isDirections || trip.stops.length >= MAX_STOPS;
}

function clampCorridorMiles(miles) {
  if (!Number.isFinite(miles)) return CORRIDOR_DEFAULT_MILES;
  return Math.max(CORRIDOR_MIN_MILES, Math.min(CORRIDOR_MAX_MILES, Math.round(miles / CORRIDOR_STEP_MILES) * CORRIDOR_STEP_MILES));
}

function syncCorridorInput() {
  const range = document.getElementById('tb-corridor-range');
  const value = document.getElementById('tb-corridor-value');
  if (range) range.value = String(trip.corridorMiles);
  if (value) value.textContent = `${trip.corridorMiles} mi`;
}

function restoreSharedLinkFromUrl() {
  if (typeof window === 'undefined') return;
  const params = new URLSearchParams(window.location.search);
  let restored = false;
  const run = () => {
    const routeParam = params.get('route');
    if (routeParam) {
      const shared = decodeRouteState(routeParam);
      if (!shared) {
        showStatus('Shared route link is invalid.', { error: true });
        return;
      }
      trip.mode = 'directions';
      trip.stops = shared.stops;
      trip.route = null;
      trip.corridorMiles = clampCorridorMiles(shared.corridorMiles ?? CORRIDOR_DEFAULT_MILES);
      syncCorridorInput();
      rerender();
      tryFetchRoute();
      return;
    }

    const poiId = params.get('poi');
    if (poiId) openPoiById(poiId);
  };
  const runOnce = () => {
    if (restored) return;
    restored = true;
    run();
  };
  restoreAfterMapReady(runOnce);
}

function restoreAfterMapReady(callback) {
  if (isMapReadyForSharedLink()) {
    deferSharedLinkRestore(callback);
    return;
  }

  mapRef.once('style.load', callback);
  mapRef.once('load', callback);
  deferSharedLinkRestore(() => {
    if (isMapReadyForSharedLink()) callback();
  });
}

function isMapReadyForSharedLink() {
  return state.mapReady ||
    mapRef?.loaded?.() === true ||
    mapRef?.isStyleLoaded?.() === true;
}

function deferSharedLinkRestore(callback) {
  Promise.resolve().then(callback);
}

// --- row drag (HTML5 DnD reorder) --------------------------------------

function bindRowDrag(row) {
  row.addEventListener('dragstart', (e) => {
    const i = Number(row.dataset.i);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(i));
    row.classList.add('dragging');
  });
  row.addEventListener('dragend', () => {
    row.classList.remove('dragging');
    document.querySelectorAll('.tb-row').forEach(r => r.classList.remove('drop-target'));
  });
  row.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    row.classList.add('drop-target');
  });
  row.addEventListener('dragleave', () => row.classList.remove('drop-target'));
  row.addEventListener('drop', (e) => {
    e.preventDefault();
    row.classList.remove('drop-target');
    const fromIdx = Number(e.dataTransfer.getData('text/plain'));
    const toIdx = Number(row.dataset.i);
    if (fromIdx === toIdx || isNaN(fromIdx)) return;
    if (fromIdx >= trip.stops.length) return;
    const [moved] = trip.stops.splice(fromIdx, 1);
    if (toIdx >= trip.stops.length) trip.stops.push(moved);
    else trip.stops.splice(toIdx, 0, moved);
    rerender();
    if (allStopsFilled()) tryFetchRoute();
  });
}

// --- status -------------------------------------------------------------

function showStatus(html, { error = false } = {}) {
  const el = document.getElementById('tb-status');
  el.innerHTML = html;
  el.classList.add('visible');
  el.classList.toggle('error', error);
}
function hideStatus() {
  const el = document.getElementById('tb-status');
  el.classList.remove('visible');
  el.innerHTML = '';
}

// --- trip results (campground cards) ----------------------------------

const CG_DOT_TOKEN = {
  federal: '--rt-layer-cg-federal',
  provincial: '--rt-layer-cg-provincial',
  state: '--rt-layer-cg-state',
  local: '--rt-layer-cg-local',
  other: '--rt-layer-cg-unclassified',
};

const tripResults = {
  cards: [],     // [{ id, name, sub, lng, lat, category, routeKm, distKm, rating, sites, season, feature }]
  byId: new Map(),
  // Cumulative-distance index for the active route's polyline. Lets us
  // O(N) project a pin onto the route once and read its along-route
  // distance in km.
  routeCoords: null,   // [[lng, lat], ...]
  routeCum: null,      // [0, d01, d01+d12, ...] in km
  legendBound: false,
  // Collapse state. Starts collapsed on mobile so the map isn't covered
  // by a long card list, expanded on desktop where there's room.
  collapsed: typeof window !== 'undefined' && window.matchMedia?.('(max-width: 768px)').matches,
};

/** Build the cumulative-km index for the active route polyline. */
function indexRoute(lineGeo) {
  if (!lineGeo?.coordinates?.length) {
    tripResults.routeCoords = null;
    tripResults.routeCum = null;
    return;
  }
  const coords = lineGeo.coordinates;
  const cum = new Float64Array(coords.length);
  cum[0] = 0;
  for (let i = 1; i < coords.length; i++) {
    const [a1, b1] = coords[i - 1];
    const [a2, b2] = coords[i];
    cum[i] = cum[i - 1] + distanceKm(b1, a1, b2, a2);
  }
  tripResults.routeCoords = coords;
  tripResults.routeCum = cum;
}

/** Project (lng,lat) onto the indexed route, return distance-along-route in km.
 *  Linear scan over segments — O(N). Approximates by treating each segment
 *  as flat in degree space, which is fine for the segment-projection step
 *  even for cross-country routes. */
function distanceAlongRouteKm(lng, lat) {
  const coords = tripResults.routeCoords;
  const cum = tripResults.routeCum;
  if (!coords || !cum) return 0;
  let bestSeg = 0, bestT = 0, bestD2 = Infinity;
  for (let i = 0; i < coords.length - 1; i++) {
    const [ax, ay] = coords[i];
    const [bx, by] = coords[i + 1];
    const dx = bx - ax, dy = by - ay;
    const len2 = dx * dx + dy * dy;
    let t = len2 ? ((lng - ax) * dx + (lat - ay) * dy) / len2 : 0;
    if (t < 0) t = 0; else if (t > 1) t = 1;
    const px = ax + t * dx, py = ay + t * dy;
    const ex = lng - px, ey = lat - py;
    const d2 = ex * ex + ey * ey;
    if (d2 < bestD2) { bestD2 = d2; bestSeg = i; bestT = t; }
  }
  // Once we know the closest segment, get accurate along-route km via cum.
  const segLen = cum[bestSeg + 1] - cum[bestSeg];
  return cum[bestSeg] + bestT * segLen;
}

/** Replace the corridor card list wholesale from a fresh /api/pois/on-route
 *  response. Slim features carry only id, lng/lat, category, and subcategory;
 *  the richer fields the cards display (name, sites, season, rating) arrive
 *  asynchronously via per-card hydratePoi. */
function setTripPois(cgFeatures) {
  if (trip.mode !== 'directions' || !trip.route || !trip.stops[0]) {
    tripResults.cards = [];
    tripResults.byId.clear();
    renderResults();
    return;
  }
  // Refresh route index — the route polyline may have changed since the
  // last call (added/removed/reordered stops). Used to sort cards in the
  // order the driver encounters them.
  indexRoute(trip.route.features[0].geometry);

  const origin = trip.stops[0];
  tripResults.cards = [];
  tripResults.byId.clear();

  for (const f of cgFeatures || []) {
    const id = f.id ?? f.properties?.id;
    if (id == null) continue;
    const [lng, lat] = f.geometry?.coordinates || [];
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) continue;
    const p = f.properties || {};
    const subcat = campgroundLayerCategory(p.subcategory || p.category);
    const card = {
      id,
      name: 'Campground',
      sub: '',
      location: '',
      category: subcat,
      sites: null,
      season: null,
      reservable: undefined,
      rating: null,
      agency: p.agency || '',
      lng, lat,
      routeKm: distanceAlongRouteKm(lng, lat),
      distKm: distanceKm(origin.lat, origin.lng, lat, lng),
      feature: f,
      hydrated: false,
    };
    tripResults.byId.set(id, card);
    tripResults.cards.push(card);
  }
  tripResults.cards.sort((a, b) => a.routeKm - b.routeKm);
  renderResults();

  // Per-card lazy hydration. Promise.allSettled — each card swap-in is
  // independent; one slow request shouldn't hold the rest. The browser
  // HTTP cache (Cache-Control on /api/pois/{id}) absorbs cross-pan
  // repeats so dragging the radius slider rehydrates from disk cache.
  if (tripResults.cards.length > 0) {
    hydrateTripCards(tripResults.cards.slice());
  }
}

/** Hydrate each card via /api/pois/{id}. Runs in parallel; renderResults
 *  fires once at the end so the card list re-paints with the names. */
async function hydrateTripCards(cards) {
  const promises = cards.map(async (card) => {
    try {
      const detailFeature = await hydratePoi(card.feature);
      const flat = flattenHydratedPoi(detailFeature);
      const p = flat.properties || {};
      let rating = null;
      if (Array.isArray(p.rating_reviews)) rating = p.rating_reviews;
      else if (typeof p.rating_reviews === 'string') {
        try { rating = JSON.parse(p.rating_reviews); } catch { /* ignore */ }
      }
      // The card may have been replaced (next refresh wholesale-replaced
      // tripResults.cards) — only mutate if our id is still in the list.
      const live = tripResults.byId.get(card.id);
      if (!live) return;
      live.name = p.name || 'Campground';
      live.sub = p.typeLabel || '';
      live.location = p.state || p.country || '';
      live.agency = p.agency || live.agency || '';
      live.sites = Number.isFinite(Number(p.sites)) ? Number(p.sites) : null;
      live.season = p.season || null;
      live.reservable = p.reservable;
      live.rating = Array.isArray(rating) ? rating : null;
      live.feature = flat;
      live.hydrated = true;
    } catch (_) {
      // Leave the placeholder card. Next refresh re-tries; the browser
      // cache means a transient blip doesn't keep us in placeholder land.
    }
  });
  await Promise.allSettled(promises);
  renderResults();
}

/** Compact season label: "Open through Oct 25" / "Closed until May 5" /
 *  "Year-round" / first-come hint, derived from the same season string the
 *  drawer parses. Returns '' when nothing useful to assert. Lightweight
 *  re-implementation to keep cards independent from drawer/popup imports. */
function compactSeasonLabel(seasonStr, reservable) {
  if (!seasonStr) {
    return reservable === false ? 'First-come' : '';
  }
  if (/year[\s-]*round/i.test(seasonStr)) return 'Year-round';
  // Strip parenthetical qualifiers ("year-round (boat access)") and
  // truncate so we never blow the card width.
  const cleaned = seasonStr.replace(/\s*\([^)]*\)/g, '').trim();
  return cleaned.length > 28 ? cleaned.slice(0, 26) + '…' : cleaned;
}

/** Wire the collapse toggle on the results header. innerHTML rewrites blow
 *  away listeners every render, so re-bind every time. */
function bindResultsHead(el) {
  const head = el.querySelector('.tb-results-head');
  if (!head) return;
  if (head.dataset.bound === '1') {
    head.setAttribute('aria-expanded', String(!tripResults.collapsed));
    return;
  }
  head.dataset.bound = '1';
  const toggle = () => {
    tripResults.collapsed = !tripResults.collapsed;
    el.classList.toggle('collapsed', tripResults.collapsed);
    head.setAttribute('aria-expanded', String(!tripResults.collapsed));
  };
  head.addEventListener('click', toggle);
  head.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  });
}

function visibleCards() {
  return tripResults.cards.filter(c => campgroundFeaturePassesFilter(c));
}

function renderResults() {
  const el = document.getElementById('tb-results');
  const headEl = el?.querySelector('.tb-results-head');
  const cardsEl = document.getElementById('tb-results-cards');
  if (!el) return;
  if (trip.mode !== 'directions' || !trip.route) {
    el.classList.remove('visible');
    if (headEl) headEl.innerHTML = '';
    if (cardsEl) cardsEl.innerHTML = '';
    return;
  }
  // Save scroll position so a bbox-refresh re-render doesn't yank the user
  // back to the top while they're scanning the list.
  const scrollY = el.scrollTop;

  const cards = visibleCards();
  const total = tripResults.cards.length;
  const filteredOut = total - cards.length;
  const filterNote = filteredOut > 0
    ? ` <span class="tb-results-count">· ${cards.length} of ${total}</span>`
    : ` <span class="tb-results-count">· ${total}</span>`;
  const chevron = `<svg class="tb-results-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 15 12 9 18 15"/></svg>`;
  const expanded = !tripResults.collapsed;

  el.classList.toggle('collapsed', tripResults.collapsed);
  if (headEl) {
    headEl.setAttribute('aria-expanded', String(expanded));
    headEl.innerHTML = `Campgrounds along route${filterNote}${chevron}`;
  }

  if (!cards.length) {
    const msg = total === 0
      ? 'Pan the map or widen the corridor to find campgrounds.'
      : 'All campgrounds hidden — re-enable a category in the legend.';
    if (cardsEl) cardsEl.innerHTML = `<div class="tb-card-empty">${msg}</div>`;
    el.classList.add('visible');
    bindResultsHead(el);
    return;
  }
  let body = '';
  for (const c of cards) {
    const color = token(CG_DOT_TOKEN[c.category] || CG_DOT_TOKEN.other);
    const ratingHtml = c.rating
      ? `<span class="tb-card-rating">★ ${c.rating[0].toFixed(1)}</span>`
      : '';
    const sitesHtml = c.sites
      ? `<span class="tb-card-sites">${c.sites} sites</span>`
      : '';
    const seasonStr = compactSeasonLabel(c.season, c.reservable);
    const seasonHtml = seasonStr ? `<span class="tb-card-season">${escapeHtml(seasonStr)}</span>` : '';
    body += `<div class="tb-card" data-id="${escapeHtml(String(c.id))}">
      <span class="tb-card-dot" style="background:${color}"></span>
      <div class="tb-card-body">
        <div class="tb-card-head">
          <div class="tb-card-name">${escapeHtml(c.name)}</div>
          ${c.location ? `<div class="tb-card-location">${escapeHtml(c.location)}</div>` : ''}
        </div>
        ${c.sub ? `<div class="tb-card-sub">${escapeHtml(c.sub)}</div>` : ''}
        <div class="tb-card-meta">
          <span class="tb-card-dist">${formatRouteKm(c.routeKm)}</span>
          ${ratingHtml}${sitesHtml}${seasonHtml}
        </div>
      </div>
    </div>`;
  }
  if (cardsEl) cardsEl.innerHTML = body;
  el.classList.add('visible');
  el.scrollTop = scrollY;
  bindResultsHead(el);

  // Bind once: when the user toggles a category in the right panel, re-render
  // the card list so it reflects what's visible on the map.
  if (!tripResults.legendBound) {
    tripResults.legendBound = true;
    onCampgroundFilterChange(() => renderResults());
  }
  el.querySelectorAll('.tb-card').forEach(node => {
    node.addEventListener('click', () => {
      const id = node.dataset.id;
      const card = tripResults.byId.get(id) || tripResults.byId.get(Number(id));
      if (!card) return;
      // Make sure the federal/state/local toggle for this campground is on,
      // then fly to the pin and synthesize a click so the existing drawer
      // path takes over (handles availability fetch + pin reselect logic).
      const cat = card.category === 'other' ? 'federal' : card.category;
      const toggle = document.getElementById(`f-cg-${cat}`);
      if (toggle && !toggle.checked) {
        toggle.checked = true;
        toggle.dispatchEvent(new Event('change'));
      }
      // suppressPinClick prevents bindPinClicks() from overwriting the
      // destination input with this campground's name when synthesizeClick
      // dispatches the synthetic map-click event.
      suppressPinClick = true;
      mapRef.flyTo({ center: [card.lng, card.lat], zoom: 13, speed: 1.6 });
      mapRef.once('moveend', () => {
        synthesizeClick(['cg-points-hit', 'cg-points'], [card.lng, card.lat]);
        // Synthesized click runs synchronously inside synthesizeClick —
        // release the flag right after so genuine user clicks aren't blocked.
        suppressPinClick = false;
      });
    });
  });
}

// --- helpers -----------------------------------------------------------

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => (
    { '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]
  ));
}
function formatDist(km) {
  if (km < 1) return `${Math.round(km * 1000)} m`;
  if (km < 100) return `${km.toFixed(1)} km`;
  return `${km.toFixed(0)} km`;
}
/** Distance-along-route — "X km in" reads better than "X km away". */
function formatRouteKm(km) {
  if (km < 1) return `${Math.round(km * 1000)} m in`;
  if (km < 10) return `${km.toFixed(1)} km in`;
  return `${Math.round(km)} km in`;
}
function formatDuration(hrs) {
  const h = Math.floor(hrs);
  const m = Math.round((hrs - h) * 60);
  if (h === 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}
