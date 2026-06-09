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

import { state, distanceKm, formatDistance } from './core.js';
import { fitAndSelect } from './search.js';
import { synthesizeClick } from './layers.js';

// --- constants -------------------------------------------------------------

const ROUTE_COLOR = '#4285F4';   // Google-Maps-blue
const GEOCODE_DEBOUNCE_MS = 220;
const MAX_STOPS = 25;

// Corridor: a buffered polygon around the active route, used to filter
// /api/pois server-side. 30 mi default — wide enough to catch realistic
// detour-worthy stops, narrow enough that the corridor is meaningful.
// User-adjustable via the topbar slider; range 5..100 mi.
// MAX_POLYGON_VERTICES is the backend cap (2000); we simplify aggressively
// to stay well under so even cross-country routes fit in one POST body.
const CORRIDOR_DEFAULT_MILES = 30;
const CORRIDOR_MIN_MILES = 5;
const CORRIDOR_MAX_MILES = 100;
const CORRIDOR_STEP_MILES = 5;
const CORRIDOR_SIMPLIFY_TOLERANCE = 0.02;  // degrees — ~2km at mid-latitudes

const KIND_COLOR = {
  PLACE: '#3a7bd5',
  ADDR:  '#5a6a8a',
  CG:    '#2e7d32',
  SC:    '#e82127',
  NP:    '#2e7d32',
  SP:    '#8d6e63',
  PF:    '#7b4bb5',
};

// --- module state ----------------------------------------------------------

const trip = {
  // 'browse'      — single search bar, no route
  // 'directions'  — N >= 2 slots, route fetched when all filled
  mode: 'browse',
  // Each stop is { name, lng, lat, kind, pinItem? } or null (empty slot)
  stops: [],
  route: null,        // GeoJSON FeatureCollection from /api/route
  corridor: null,     // GeoJSON Polygon from turf.buffer(route, corridorMiles)
  corridorMiles: CORRIDOR_DEFAULT_MILES,
  routeAbort: null,
  generation: 0,
  endpointMarkers: [], // parallel to stops; null for empty slots
};

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

// On mobile, programmatic focus on a search input pops the soft keyboard,
// which covers the drawer the user is currently looking at. Skip the
// auto-focus there and let the user tap the input themselves when they're
// ready to type. Restoring focus across an innerHTML rewrite is fine —
// that's preserving existing focus, not creating new focus.
function shouldAutoFocus() {
  return typeof window === 'undefined' ||
    !window.matchMedia?.('(max-width: 768px)').matches;
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

  // Expose the active route (waypoints + radius) to app.js's bbox refresh
  // so it can include it in the POST /api/pois body. The BE looks the
  // polyline up server-side from RouteCache and buffers there — we do NOT
  // send the buffered polygon back over the wire; that's a kilobytes-of-
  // regurgitation per pan. Returns null when no route is active.
  // The visual corridor (trip.corridor) is still computed client-side
  // via turf.buffer for the on-map fill — different consumer, different shape.
  window.__rtTripRoute = () => {
    if (trip.mode !== 'directions' || !allStopsFilled()) return null;
    return {
      waypoints: trip.stops.map(s => ({ lat: s.lat, lng: s.lng })),
      radius_miles: trip.corridorMiles,
    };
  };

  // app.js calls this on every bbox refresh with the latest campground feature
  // list. We dedupe + sort + render only when a route is active.
  window.__rtSetTripPois = (cgFeatures) => setTripPois(cgFeatures);

  // Drawer + popups read these to render a per-POI Directions button. We
  // expose globals (vs. an import) because the drawer module is downstream
  // from popups.js and shouldn't pull topbar.js into its dependency graph.
  window.__rtTripMode = () => trip.mode;
  window.__rtAddTripStop = (stop) => addTripStopFromExternal(stop);
}

/**
 * Add a POI as a stop without going through the search flow. Called by the
 * drawer's per-POI Directions button.
 *
 *  - browse mode: enter directions mode with the POI as the *destination*.
 *    The origin row stays empty and gets focus so the user can immediately
 *    type "from where?". The drawer's "Directions" CTA reads as "directions
 *    TO this place" — most users want to go to the campground they're
 *    looking at, not start from it.
 *  - directions mode + destination empty: fill the destination.
 *  - directions mode + destination filled: insert as a via just before the
 *    destination so the user-chosen endpoint stays the endpoint.
 */
function addTripStopFromExternal(stop) {
  if (!stop || !Number.isFinite(stop.lng) || !Number.isFinite(stop.lat)) return;
  const s = { name: stop.name || 'Selected place', lng: stop.lng, lat: stop.lat, kind: stop.kind || 'PLACE' };
  if (trip.mode === 'browse') {
    // Reset stops so any leftover row-0 pin click from browse mode doesn't
    // double up. POI is the destination; origin is empty + focused.
    trip.stops = [null, s];
    trip.mode = 'directions';
    rerender();
    if (shouldAutoFocus()) {
      setTimeout(() => {
        const el = document.querySelector('.tb-row[data-i="0"] .tb-input');
        if (el) { activeRowIdx = 0; el.focus(); }
      }, 0);
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
    background: var(--cg-surface);
    border: 1px solid var(--cg-border);
    border-radius: 10px;
    box-shadow: 0 6px 20px rgba(0,0,0,0.40);
    -webkit-font-smoothing: antialiased;
    font-family: inherit;
  }

  /* Stops list */
  #tb-stops { display: flex; flex-direction: column; gap: 4px; padding: 6px; }

  .tb-row {
    display: flex; align-items: center; gap: 8px;
    background: var(--cg-bg-subtle);
    border: 1px solid var(--cg-border-strong);
    border-radius: 8px;
    padding: 0 6px 0 10px;
    transition: border-color 100ms ease, background 100ms ease, opacity 120ms ease;
  }
  .tb-row:focus-within {
    border-color: var(--cg-accent);
    background: var(--cg-bg-hover);
  }
  .tb-row.dragging { opacity: 0.4; }
  .tb-row.drop-target { border-color: var(--cg-accent); border-style: dashed; }
  .tb-row[draggable="true"] { cursor: grab; }
  .tb-row[draggable="true"]:active { cursor: grabbing; }
  .tb-row[draggable="true"] .tb-input { cursor: text; }

  .tb-icon {
    flex-shrink: 0;
    width: 12px; height: 12px;
    border-radius: 50%;
    background: var(--cg-accent);
    box-shadow: 0 0 0 2px var(--cg-bg-subtle);
  }
  .tb-icon.via  { background: #e0a543; border-radius: 2px; }
  .tb-icon.last { background: ${ROUTE_COLOR}; border-radius: 2px; }

  .tb-input {
    flex: 1; min-width: 0;
    background: transparent; color: var(--cg-text);
    border: 0; outline: none;
    padding: 9px 0;
    font-size: 13px; font-family: inherit;
  }
  .tb-input::placeholder { color: var(--cg-faint); }

  .tb-x {
    flex-shrink: 0;
    width: 24px; height: 24px;
    background: transparent; border: 0; color: var(--cg-faint);
    border-radius: 4px; cursor: pointer;
    display: grid; place-items: center;
  }
  .tb-x:hover { color: var(--cg-error); background: var(--cg-bg-hover); }

  /* Action row */
  #tb-actions { display: flex; align-items: center; gap: 6px; padding: 0 6px 6px; }

  #tb-add {
    background: transparent;
    border: 1px dashed var(--cg-border-strong);
    color: var(--cg-muted);
    padding: 6px 10px; border-radius: 6px;
    font-size: 12px; font-family: inherit;
    cursor: pointer;
    transition: color 100ms, border-color 100ms;
  }
  #tb-add:hover { color: var(--cg-accent); border-color: var(--cg-accent); }
  #tb-add[hidden] { display: none; }

  .tb-icon-btn {
    width: 36px; height: 36px;
    background: var(--cg-bg-subtle);
    border: 1px solid var(--cg-border-strong);
    color: var(--cg-text);
    border-radius: 8px;
    cursor: pointer;
    display: grid; place-items: center;
    transition: background 100ms, border-color 100ms;
  }
  .tb-icon-btn:hover { background: var(--cg-bg-hover); border-color: var(--cg-accent); }
  .tb-icon-btn.primary {
    background: ${ROUTE_COLOR};
    border-color: ${ROUTE_COLOR};
    color: #fff;
  }
  .tb-icon-btn.primary:hover { background: #2b6dd1; }
  .tb-icon-btn[hidden] { display: none; }

  /* Dropdown */
  #tb-dropdown {
    max-height: 320px; overflow-y: auto;
    border-top: 1px solid var(--cg-border);
    padding: 4px;
    display: none;
  }
  #tb-dropdown.open { display: block; }
  .tb-section {
    font-size: 9px; text-transform: uppercase; letter-spacing: 0.06em;
    color: var(--cg-faint);
    padding: 6px 8px 2px;
  }
  .tb-result {
    display: flex; align-items: center; gap: 8px;
    padding: 7px 8px; cursor: pointer;
    color: var(--cg-text); font-size: 12px;
    border-radius: 6px;
  }
  .tb-result:hover, .tb-result.active { background: var(--cg-bg-hover); }
  .tb-kind {
    flex-shrink: 0;
    font-size: 9px; text-transform: uppercase;
    padding: 2px 6px; border-radius: 3px;
    color: #fff; font-weight: 600; letter-spacing: 0.04em;
    min-width: 28px; text-align: center;
  }
  .tb-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .tb-sub { color: var(--cg-muted); font-size: 11px; }

  /* Status */
  #tb-status {
    padding: 6px 12px;
    font-size: 11px; color: var(--cg-muted);
    border-top: 1px solid var(--cg-border);
    display: none;
  }
  #tb-status.visible { display: block; }
  #tb-status.error { color: var(--cg-error); }
  #tb-status .tb-stat-num { color: ${ROUTE_COLOR}; font-weight: 600; }
  #tb-status .tb-stat-sep { color: var(--cg-faint); margin: 0 8px; }

  /* Corridor radius slider — visible only when a route is active. */
  #tb-corridor {
    display: none;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    border-top: 1px solid var(--cg-border);
    font-size: 11px; color: var(--cg-muted);
  }
  #tb-corridor.visible { display: flex; }
  #tb-corridor label { white-space: nowrap; }
  #tb-corridor .tb-corridor-value {
    color: var(--cg-text);
    font-variant-numeric: tabular-nums;
    min-width: 44px;
    text-align: right;
  }
  #tb-corridor input[type=range] {
    flex: 1;
    accent-color: ${ROUTE_COLOR};
    cursor: pointer;
    margin: 0;
  }
  /* Route distance + duration headline. Lives on the corridor row to save a
     vertical slot on mobile. The per-leg breakdown (3+ stops) still goes in
     #tb-status below. */
  #tb-corridor .tb-corridor-summary {
    color: var(--cg-text);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    flex-shrink: 0;
    padding-left: 10px;
    margin-left: 4px;
    border-left: 1px solid var(--cg-border-strong);
  }
  #tb-corridor .tb-corridor-summary:empty {
    display: none;
  }
  #tb-corridor .tb-corridor-summary .tb-stat-sep {
    color: var(--cg-faint);
    margin: 0 6px;
  }

  /* Trip-date inputs — visible only when a route is active. Drives the bulk
     availability check that lights up each card with per-night status. */
  #tb-trip-dates {
    display: none;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-top: 1px solid var(--cg-border);
    font-size: 11px; color: var(--cg-muted);
  }
  #tb-trip-dates.visible { display: flex; }
  #tb-trip-dates .tb-trip-dates-label { white-space: nowrap; flex-shrink: 0; }
  #tb-trip-dates input[type=date] {
    background: var(--cg-bg-subtle);
    color: var(--cg-text);
    border: 1px solid var(--cg-border-strong);
    border-radius: 4px;
    padding: 2px 4px;
    font-size: 11px; font-family: inherit;
    color-scheme: dark;
    min-width: 0;
  }
  #tb-trip-dates input[type=date]:focus {
    outline: none;
    border-color: var(--cg-accent);
  }
  #tb-trip-dates .tb-trip-dates-sep { color: var(--cg-faint); }
  #tb-trip-dates .tb-trip-dates-status {
    margin-left: auto;
    color: var(--cg-faint);
    font-size: 10px;
  }
  #tb-trip-dates .tb-trip-dates-status.error { color: var(--cg-error); }

  /* Per-card availability badge + per-night dot strip. The badge is the
     at-a-glance signal; the strip shows which subset of nights work. */
  .tb-card-avail {
    display: flex; align-items: center; gap: 6px;
    margin-top: 4px;
    font-size: 10px;
  }
  .tb-card-avail-badge {
    display: inline-flex; align-items: center; gap: 3px;
    padding: 1px 6px;
    border-radius: 8px;
    font-weight: 600;
    font-size: 10px;
    line-height: 1.4;
  }
  .tb-card-avail-badge.all       { background: rgba(76,175,80,0.18); color: #82d186; }
  .tb-card-avail-badge.some      { background: rgba(245,166,35,0.18); color: #f5a623; }
  .tb-card-avail-badge.none      { background: rgba(160,160,160,0.18); color: var(--cg-muted); }
  .tb-card-avail-badge.unknown   { background: rgba(160,160,160,0.10); color: var(--cg-faint); }
  .tb-card-avail-badge.err       { background: rgba(245,101,101,0.18); color: var(--cg-error); }
  .tb-card-avail-strip {
    display: inline-flex; gap: 2px;
    flex-wrap: wrap;
  }
  .tb-card-avail-cell {
    width: 8px; height: 8px;
    border-radius: 1px;
    background: rgba(255,255,255,0.06);
  }
  .tb-card-avail-cell.avail { background: #4caf50; }
  .tb-card-avail-cell.miss  { background: rgba(160,160,160,0.45); }

  /* Campground results — appears when a route is active. */
  #tb-results {
    display: none;
    flex-direction: column;
    border-top: 1px solid var(--cg-border);
    max-height: min(50vh, 480px);
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  #tb-results.visible { display: flex; }
  .tb-results-head {
    position: sticky; top: 0; z-index: 1;
    padding: 8px 12px;
    background: var(--cg-surface);
    border-bottom: 1px solid var(--cg-border);
    display: flex; align-items: center; gap: 6px;
    font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em;
    color: var(--cg-muted); font-weight: 600;
    cursor: pointer;
    user-select: none;
  }
  .tb-results-head:hover { color: var(--cg-text); }
  .tb-results-head .tb-results-count {
    color: var(--cg-faint); font-weight: 400; text-transform: none; letter-spacing: 0;
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
  .tb-card {
    display: flex; gap: 10px;
    padding: 10px 12px;
    border-bottom: 1px solid var(--cg-border);
    cursor: pointer;
    transition: background 100ms ease;
  }
  .tb-card:last-child { border-bottom: 0; }
  .tb-card:hover { background: var(--cg-bg-hover); }
  .tb-card-dot {
    flex-shrink: 0;
    width: 10px; height: 10px; margin-top: 5px;
    border-radius: 50%;
    box-shadow: 0 0 0 1px rgba(255,255,255,0.16);
  }
  .tb-card-body { flex: 1; min-width: 0; }
  .tb-card-name {
    color: var(--cg-text);
    font-size: 13px; font-weight: 500; line-height: 1.3;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .tb-card-sub {
    color: var(--cg-muted);
    font-size: 11px; line-height: 1.4;
    margin-top: 2px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .tb-card-meta {
    display: flex; gap: 8px; align-items: center; flex-wrap: wrap;
    margin-top: 4px;
    font-size: 11px; color: var(--cg-faint);
    font-variant-numeric: tabular-nums;
  }
  .tb-card-dist { color: ${ROUTE_COLOR}; font-weight: 500; }
  .tb-card-rating { color: #f5a623; font-weight: 500; }
  .tb-card-sites { color: var(--cg-muted); }
  .tb-card-season { color: var(--cg-muted); }
  .tb-card-empty {
    padding: 14px 12px;
    color: var(--cg-muted); font-size: 12px;
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
    /* Tighter corridor row so distance + duration fit alongside the slider
       without wrapping. */
    #tb-corridor { padding: 6px 10px; gap: 6px; }
    #tb-corridor label { display: none; }
    #tb-corridor .tb-corridor-summary { padding-left: 8px; margin-left: 2px; }
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
    <div id="tb-actions">
      <button id="tb-add" type="button" hidden>+ Add stop</button>
      <div style="flex:1"></div>
      <button id="tb-directions" class="tb-icon-btn primary" type="button" hidden title="Get directions" aria-label="Get directions">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10l-7 7-3-3-9 9"/><path d="M14 10h7v7"/></svg>
      </button>
      <button id="tb-clear" class="tb-icon-btn" type="button" hidden title="Clear" aria-label="Clear">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div id="tb-dropdown"></div>
    <div id="tb-status"></div>
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
      <span class="tb-corridor-summary" id="tb-corridor-summary"></span>
    </div>
    <div id="tb-trip-dates">
      <label class="tb-trip-dates-label">Travel dates</label>
      <input type="date" id="tb-trip-start" aria-label="Start date">
      <span class="tb-trip-dates-sep">→</span>
      <input type="date" id="tb-trip-end" aria-label="End date">
      <span class="tb-trip-dates-status" id="tb-trip-dates-status"></span>
    </div>
    <div id="tb-results"></div>
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
    if (!document.getElementById('topbar').contains(e.target)) closeDropdown();
  });
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
    ? `&proximity=${state.userLocation.lng},${state.userLocation.lat}`
    : `&proximity=${c.lng.toFixed(4)},${c.lat.toFixed(4)}`;

  // Backend POI search runs in parallel with geocode. The local pinSearch
  // only knows what's been loaded into the current viewport; the backend
  // path lets the user find a POI nationwide (e.g. "upper pines" while
  // looking at the East Coast).
  const [backendPoiResults, geocodeResults] = await Promise.all([
    fetch(`/api/pois/search?q=${encodeURIComponent(q)}&limit=8`, { signal })
      .then(r => r.ok ? r.json() : { results: [] })
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
    fetch(`/api/geocode?q=${encodeURIComponent(q)}&autocomplete=1&limit=5${proximity}`, { signal })
      .then(r => r.ok ? r.json() : { results: [] })
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
    case 'planet-fitness': return 'PF';
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
    const color = KIND_COLOR[r.kind] || '#666';
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

const BACKEND_POI_LAYERS = {
  CG: ['cg-points-hit', 'cg-points'],
  NP: ['np-pts-hit', 'np-fill'],
  SP: ['sp-pts-hit', 'sp-fill'],
  PF: ['pf-points-hit', 'pf-points'],
  SC: ['sc-points-hit'],
};

const BACKEND_POI_TOGGLES = {
  CG: 'f-cg-federal',
  NP: 'f-np',
  SP: 'f-sp',
  PF: 'f-pf',
  SC: 'f-open',
};

/**
 * Fly to a backend search hit and open its drawer once the pin has loaded
 * into the visible layer. The bbox-refresh is debounced 250ms after moveend
 * and then waits on /api/pois (~100-500ms), so we can't hang on a single
 * `idle` event — instead we poll queryRenderedFeatures on a short interval
 * after flyTo settles, up to a 4s budget. As soon as the pin is hittable,
 * synthesize the click so the existing layer handlers open the drawer.
 */
function flyAndOpenBackendPoi(result) {
  // Make sure the destination layer is visible — otherwise queryRenderedFeatures
  // returns nothing and the synthesized click is silently dropped.
  const toggleId = BACKEND_POI_TOGGLES[result.kind];
  if (toggleId) {
    const el = document.getElementById(toggleId);
    if (el && !el.checked) {
      el.checked = true;
      el.dispatchEvent(new Event('change'));
    }
  }
  const layers = BACKEND_POI_LAYERS[result.kind] || ['cg-points-hit', 'cg-points'];
  const center = [result.lng, result.lat];
  // Zoom 13: tight enough that the pin is the only feature near the click
  // point but still shows a bit of context. Higher zooms (14+) sometimes
  // hide adjacent pins via spider-collision.
  mapRef.flyTo({ center, zoom: 13, speed: 1.6 });

  mapRef.once('moveend', () => {
    // Nudge the bbox refresh in case the auto moveend handler hasn't fired
    // yet (it's debounced inside app.js). Idempotent — coalesces with any
    // already-pending refresh.
    if (typeof window.__rtRefreshBbox === 'function') window.__rtRefreshBbox();

    const deadline = Date.now() + 4000;
    const poll = () => {
      const present = layers.filter(id => mapRef.getLayer(id));
      if (present.length) {
        const pt = mapRef.project(center);
        const hits = mapRef.queryRenderedFeatures(pt, { layers: present });
        if (hits.length) {
          synthesizeClick(layers, center);
          return;
        }
      }
      if (Date.now() < deadline) setTimeout(poll, 200);
    };
    // 200ms first delay covers the bbox-refresh debounce + first network
    // round-trip; subsequent ticks ride the same cadence.
    setTimeout(poll, 200);
  });
}

function setStop(i, stop) {
  while (trip.stops.length <= i) trip.stops.push(null);
  trip.stops[i] = stop;
}

function allStopsFilled() {
  return trip.stops.length >= 2 && trip.stops.every(s => s != null);
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
  tripResults.availabilityByPoiId.clear();
  if (tripResults.availabilityAbort) tripResults.availabilityAbort.abort();
  setAvailabilityStatus('');
  renderResults();
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

/** Tell app.js the corridor has changed so it re-fetches /api/pois with the
 *  new polygon (or no polygon if the route was cleared). Debounced inside
 *  app.js (250ms), so calling it multiple times in quick succession is fine. */
function notifyCorridorChanged() {
  if (typeof window.__rtRefreshBbox === 'function') {
    window.__rtRefreshBbox();
  }
}

// --- route fetch + render ----------------------------------------------

async function tryFetchRoute() {
  if (!allStopsFilled()) return;
  if (trip.routeAbort) trip.routeAbort.abort();
  trip.routeAbort = new AbortController();
  const myGen = ++trip.generation;
  showStatus('Computing route…');

  const coords = trip.stops.map(s => `${s.lng},${s.lat}`).join(';');
  const url = `/api/route?coords=${encodeURIComponent(coords)}`;
  let r;
  try {
    r = await fetch(url, { signal: trip.routeAbort.signal });
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
  notifyCorridorChanged();
  renderResults();
}

function drawRoute() {
  removeRouteLayer();
  if (!trip.route) return;
  const lineGeo = trip.route.features[0].geometry;

  // Compute the corridor polygon BEFORE adding the route line, so we can add
  // the corridor fill underneath. Failure to buffer (turf missing or weird
  // input) is non-fatal — we just skip the corridor fill and the bbox
  // refresh won't include polygon, behaving as if there's no corridor.
  trip.corridor = computeCorridor(lineGeo);

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
        'fill-color': ROUTE_COLOR,
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
    paint: { 'line-color': ROUTE_COLOR, 'line-width': 5, 'line-opacity': 0.85 },
  });

  document.getElementById('tb-corridor').classList.add('visible');
  document.getElementById('tb-trip-dates').classList.add('visible');
  bindTripDateInputs();
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
  const dates = document.getElementById('tb-trip-dates');
  if (dates) dates.classList.remove('visible');
  const summary = document.getElementById('tb-corridor-summary');
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
  notifyCorridorChanged();
}

/**
 * Buffer the route polyline into a corridor polygon, then simplify so the
 * polygon stays well under the backend's MAX_POLYGON_VERTICES cap (2000).
 * Returns a GeoJSON Polygon (or MultiPolygon if the buffer self-intersects)
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
  // Headline rides on the corridor row to save vertical space on mobile.
  const head = `<strong>${distKm.toFixed(0)} km</strong>` +
    `<span class="tb-stat-sep">·</span>${formatDuration(durHrs)}`;
  const summaryEl = document.getElementById('tb-corridor-summary');
  if (summaryEl) summaryEl.innerHTML = head;
  // Per-leg breakdown is only useful for 3+ stops; it stays in the status
  // slot below. Hide status entirely for the simple 2-stop case.
  if (legs.length > 1) {
    let body = '<div style="font-size:10px; color:var(--cg-faint);">';
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
    if (!stop) {
      if (trip.endpointMarkers[i]) {
        trip.endpointMarkers[i].remove();
        trip.endpointMarkers[i] = null;
      }
      return;
    }
    const role = (i === 0) ? 'origin' : (i === trip.stops.length - 1 ? 'last' : 'via');
    const color = role === 'origin' ? 'var(--cg-accent)' :
      (role === 'last' ? ROUTE_COLOR : '#e0a543');
    const shape = role === 'last' ? 'square' : 'circle';
    const label = role === 'origin' ? 'A' :
      (role === 'last' ? String.fromCharCode(65 + Math.min(trip.stops.length - 1, 25)) : String(i));
    if (trip.endpointMarkers[i]) trip.endpointMarkers[i].remove();
    const wrap = document.createElement('div');
    wrap.style.cssText = `
      width: 26px; height: 26px;
      background: ${color}; color: #fff;
      border: 2.5px solid #fff;
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

  // Save focus state so we can restore after innerHTML rewrite
  const focusedI = activeRowIdx;
  const focusedSel = (focusedI >= 0)
    ? (() => {
        const el = stops.querySelector(`.tb-row[data-i="${focusedI}"] .tb-input`);
        return el ? { start: el.selectionStart, end: el.selectionEnd } : null;
      })()
    : null;

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

    html += `
      <div class="tb-row" data-i="${i}" draggable="${draggable}">
        <span class="tb-icon ${role}"></span>
        <input class="tb-input" type="text" autocomplete="off"
               placeholder="${escapeHtml(placeholder)}"
               aria-label="${escapeHtml(placeholder)}"
               value="${escapeHtml(value)}"
               data-i="${i}">
        ${showX ? `<button class="tb-x" type="button" data-i="${i}" data-filled="${isFilled ? '1' : '0'}" aria-label="${isFilled ? 'Clear' : 'Remove stop'}">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>` : ''}
      </div>`;
  }
  stops.innerHTML = html;

  stops.querySelectorAll('.tb-input').forEach((inp) => {
    const i = Number(inp.dataset.i);
    inp.addEventListener('input', (e) => onInput(i, e.target.value));
    inp.addEventListener('focus', () => { activeRowIdx = i; });
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

const CG_DOT_COLOR = {
  federal: '#2e7d32',
  provincial: '#2e7d32',
  state: '#558b2f',
  local: '#9ccc65',
  other: '#cddc39',
};

const CG_LEGEND_TOGGLES = ['f-cg-federal', 'f-cg-state', 'f-cg-local', 'f-cg-provincial'];

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
  // Trip-date availability state. tripStart/tripEnd are 'YYYY-MM-DD' or null.
  // availabilityByPoiId maps poi.id → { status: number, dates: string[] }, or
  // 'pending' / 'error' for in-flight / failed ids. dateBound is true once
  // the date inputs are wired (they outlive innerHTML rewrites of #tb-results).
  tripStart: null,
  tripEnd: null,
  availabilityByPoiId: new Map(),
  availabilityAbort: null,
  availabilityDebounce: null,
  dateBound: false,
};

// Bulk endpoint cap — keep in sync with MAX_BULK_IDS in CampsiteAvailabilityRoutes.kt.
const BULK_AVAILABILITY_PAGE = 50;
const AVAIL_DEBOUNCE_MS = 500;
// Backend rejects nights > 14. Long road trips just clamp the window to the
// first 14 nights for the badge — visiting Yellowstone on night 6 of a
// 21-day trip is the realistic case, and the bulk badge for night 18 is
// almost always "we don't know" anyway.
const MAX_TRIP_NIGHTS = 14;

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

/** Called by app.js after every bbox refresh. We accumulate dedupe-by-id so
 *  campgrounds discovered in earlier viewports stay in the list, and re-sort
 *  whenever the origin moves (route added/cleared/reordered). */
function setTripPois(cgFeatures) {
  if (trip.mode !== 'directions' || !trip.route || !trip.stops[0]) {
    tripResults.cards = [];
    tripResults.byId.clear();
    tripResults.availabilityByPoiId.clear();
    renderResults();
    return;
  }
  // Refresh route index — the route polyline may have changed since the
  // last call (added/removed/reordered stops).
  indexRoute(trip.route.features[0].geometry);

  const origin = trip.stops[0];
  let added = false;
  for (const f of cgFeatures || []) {
    const id = f.id ?? f.properties?.id;
    if (id == null || tripResults.byId.has(id)) continue;
    added = true;
    const [lng, lat] = f.geometry?.coordinates || [];
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) continue;
    const p = f.properties || {};
    // rating_reviews can be either an array (already parsed by /api/pois)
    // or a JSON string (legacy popups path) — handle both.
    let rating = null;
    if (Array.isArray(p.rating_reviews)) rating = p.rating_reviews;
    else if (typeof p.rating_reviews === 'string') {
      try { rating = JSON.parse(p.rating_reviews); } catch { /* ignore */ }
    }
    const card = {
      id,
      name: p.name || 'Campground',
      sub: [p.typeLabel, p.state || p.country].filter(Boolean).join(' · '),
      category: p.category || 'other',
      sites: Number.isFinite(Number(p.sites)) ? Number(p.sites) : null,
      season: p.season || null,
      reservable: p.reservable,
      rating: Array.isArray(rating) ? rating : null,
      lng, lat,
      feature: f,
    };
    tripResults.byId.set(id, card);
    tripResults.cards.push(card);
  }
  // Recompute distances every refresh — origin or route geometry may have changed.
  for (const c of tripResults.cards) {
    c.distKm = distanceKm(origin.lat, origin.lng, c.lat, c.lng);
    c.routeKm = distanceAlongRouteKm(c.lng, c.lat);
  }
  tripResults.cards.sort((a, b) => a.routeKm - b.routeKm);
  renderResults();
  // New cards landed → refresh availability for them. Debounced so a flurry
  // of bbox refreshes (zoom/pan) collapses into one bulk call.
  if (added && tripResults.tripStart && tripResults.tripEnd) {
    scheduleAvailabilityRefresh();
  }
}

/** Wire up the trip-date inputs once. innerHTML rewrites inside #tb-results
 *  blow away listeners, but #tb-trip-dates is a sibling — wire and forget. */
function bindTripDateInputs() {
  if (tripResults.dateBound) return;
  tripResults.dateBound = true;
  const startEl = document.getElementById('tb-trip-start');
  const endEl = document.getElementById('tb-trip-end');
  // Default the start to tomorrow — most of our cache window is in the
  // future. End stays empty so the user has to think about trip length.
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  startEl.value = tomorrow.toISOString().slice(0, 10);
  startEl.min = new Date().toISOString().slice(0, 10);
  endEl.min = startEl.value;
  // Restore prior values if user has set them this session.
  if (tripResults.tripStart) startEl.value = tripResults.tripStart;
  if (tripResults.tripEnd) endEl.value = tripResults.tripEnd;

  const onChange = () => {
    tripResults.tripStart = startEl.value || null;
    tripResults.tripEnd = endEl.value || null;
    // Keep `end >= start` enforceable in the picker.
    if (tripResults.tripStart) endEl.min = tripResults.tripStart;
    // Clear stale per-card data so cards show "pending" while we refetch.
    tripResults.availabilityByPoiId.clear();
    renderResults();
    if (tripResults.tripStart && tripResults.tripEnd) {
      scheduleAvailabilityRefresh();
    } else {
      setAvailabilityStatus('');
    }
  };
  startEl.addEventListener('change', onChange);
  endEl.addEventListener('change', onChange);
}

function setAvailabilityStatus(text, { error = false } = {}) {
  const el = document.getElementById('tb-trip-dates-status');
  if (!el) return;
  el.textContent = text;
  el.classList.toggle('error', error);
}

/** Nights between check-in (`start`) and check-out (`end`) — i.e., end-start
 *  in days. A Jul 4 → Jul 7 trip is 3 nights (you sleep on the 4th, 5th, 6th
 *  and check out the morning of the 7th). Returns 0 on bad/non-positive input. */
function nightsBetween(startStr, endStr) {
  const s = new Date(startStr + 'T00:00:00Z');
  const e = new Date(endStr + 'T00:00:00Z');
  if (!Number.isFinite(s.getTime()) || !Number.isFinite(e.getTime())) return 0;
  const n = Math.round((e - s) / 86400000);
  return n > 0 ? n : 0;
}

function scheduleAvailabilityRefresh() {
  clearTimeout(tripResults.availabilityDebounce);
  tripResults.availabilityDebounce = setTimeout(refreshAvailability, AVAIL_DEBOUNCE_MS);
}

/**
 * Hit POST /api/campsite/availability/bulk for every visible card id and
 * paint the per-card badges + dot strips. Aborts the previous in-flight
 * request when called again, so a rapid date-flick or pan settles on the
 * latest input.
 */
async function refreshAvailability() {
  const start = tripResults.tripStart;
  const end = tripResults.tripEnd;
  if (!start || !end) return;
  let nights = nightsBetween(start, end);
  if (nights <= 0) {
    setAvailabilityStatus('End date must be on or after start.', { error: true });
    return;
  }
  if (nights > MAX_TRIP_NIGHTS) {
    // Backend rejects > 14; clamp the badge window and warn the user.
    setAvailabilityStatus(`Showing first ${MAX_TRIP_NIGHTS} nights.`);
    nights = MAX_TRIP_NIGHTS;
  }
  const ids = visibleCards()
    .map(c => Number(c.id))
    .filter(Number.isFinite);
  if (!ids.length) {
    setAvailabilityStatus('');
    return;
  }

  if (tripResults.availabilityAbort) tripResults.availabilityAbort.abort();
  tripResults.availabilityAbort = new AbortController();
  const signal = tripResults.availabilityAbort.signal;
  setAvailabilityStatus('Checking availability…');

  // Mark every requested id as pending so the UI swaps from a stale "all"
  // badge to a neutral "—" while we wait.
  for (const id of ids) tripResults.availabilityByPoiId.set(id, 'pending');
  renderResults();

  try {
    // Backend caps each call at 50 ids; chunk so a 200-card corridor still works.
    for (let i = 0; i < ids.length; i += BULK_AVAILABILITY_PAGE) {
      const slice = ids.slice(i, i + BULK_AVAILABILITY_PAGE);
      const r = await fetch('/api/campsite/availability/bulk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids: slice, start, nights }),
        signal,
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const j = await r.json();
      for (const entry of j.results || []) {
        if (entry.status === 200) {
          tripResults.availabilityByPoiId.set(Number(entry.id), {
            status: 200,
            dates: entry.available_dates || [],
          });
        } else {
          tripResults.availabilityByPoiId.set(Number(entry.id), {
            status: entry.status,
            dates: [],
          });
        }
      }
      renderResults();
    }
    setAvailabilityStatus(nights > MAX_TRIP_NIGHTS ? `Showing first ${MAX_TRIP_NIGHTS} nights.` : '');
  } catch (e) {
    if (e.name === 'AbortError') return;
    console.warn('[topbar] availability bulk failed', e);
    // Keep the per-id pending markers as 'error' so cards show a useful badge.
    for (const id of ids) {
      const cur = tripResults.availabilityByPoiId.get(id);
      if (cur === 'pending') tripResults.availabilityByPoiId.set(id, 'error');
    }
    setAvailabilityStatus('Could not check availability.', { error: true });
    renderResults();
  }
}

/** Render-time helper: turn the cached availability entry for a card into
 *  the badge/strip HTML pair. Pure function of (entry, nights). */
function availabilityHtml(entry, start, nights) {
  if (entry == null) return '';
  if (entry === 'pending') {
    return `<div class="tb-card-avail">
      <span class="tb-card-avail-badge unknown">Checking…</span>
    </div>`;
  }
  if (entry === 'error') {
    return `<div class="tb-card-avail">
      <span class="tb-card-avail-badge err">No data</span>
    </div>`;
  }
  if (entry.status === 404) {
    return `<div class="tb-card-avail">
      <span class="tb-card-avail-badge unknown">Not in DB</span>
    </div>`;
  }
  if (entry.status !== 200) {
    return `<div class="tb-card-avail">
      <span class="tb-card-avail-badge err">Upstream ${entry.status}</span>
    </div>`;
  }
  const availableSet = new Set(entry.dates);
  const cells = [];
  const startDate = new Date(start + 'T00:00:00Z');
  const dotsToShow = Math.min(nights, MAX_TRIP_NIGHTS);
  let availCount = 0;
  for (let i = 0; i < dotsToShow; i++) {
    const d = new Date(startDate);
    d.setUTCDate(startDate.getUTCDate() + i);
    const ymd = d.toISOString().slice(0, 10);
    const open = availableSet.has(ymd);
    if (open) availCount++;
    cells.push(`<span class="tb-card-avail-cell ${open ? 'avail' : 'miss'}" title="${ymd}: ${open ? 'open' : 'no'}"></span>`);
  }
  let badgeClass; let badgeText;
  if (availCount === 0) {
    badgeClass = 'none';
    badgeText = 'No nights';
  } else if (availCount === dotsToShow) {
    badgeClass = 'all';
    badgeText = `All ${dotsToShow} nights`;
  } else {
    badgeClass = 'some';
    badgeText = `${availCount} of ${dotsToShow}`;
  }
  return `<div class="tb-card-avail">
    <span class="tb-card-avail-badge ${badgeClass}">${badgeText}</span>
    <span class="tb-card-avail-strip">${cells.join('')}</span>
  </div>`;
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
  const allowed = new Set();
  for (const id of CG_LEGEND_TOGGLES) {
    const el = document.getElementById(id);
    if (el?.checked) allowed.add(id.replace('f-cg-', ''));
  }
  // 'other' rides with federal — same as the map filter in layers.js.
  if (allowed.has('federal')) allowed.add('other');
  return tripResults.cards.filter(c => allowed.has(c.category));
}

function renderResults() {
  const el = document.getElementById('tb-results');
  if (!el) return;
  if (trip.mode !== 'directions' || !trip.route) {
    el.classList.remove('visible');
    el.innerHTML = '';
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
  const head = `<div class="tb-results-head" role="button" tabindex="0" aria-expanded="${expanded}">
    Campgrounds along route${filterNote}${chevron}
  </div>`;

  el.classList.toggle('collapsed', tripResults.collapsed);

  if (!cards.length) {
    const msg = total === 0
      ? 'Pan the map or widen the corridor to find campgrounds.'
      : 'All campgrounds hidden — re-enable a category in the legend.';
    el.innerHTML = head + `<div class="tb-results-body"><div class="tb-card-empty">${msg}</div></div>`;
    el.classList.add('visible');
    bindResultsHead(el);
    return;
  }
  let body = '';
  for (const c of cards) {
    const color = CG_DOT_COLOR[c.category] || CG_DOT_COLOR.other;
    const ratingHtml = c.rating
      ? `<span class="tb-card-rating">★ ${c.rating[0].toFixed(1)}</span>`
      : '';
    const sitesHtml = c.sites
      ? `<span class="tb-card-sites">${c.sites} sites</span>`
      : '';
    const seasonStr = compactSeasonLabel(c.season, c.reservable);
    const seasonHtml = seasonStr ? `<span class="tb-card-season">${escapeHtml(seasonStr)}</span>` : '';
    let availHtml = '';
    if (tripResults.tripStart && tripResults.tripEnd) {
      const nights = Math.min(
        nightsBetween(tripResults.tripStart, tripResults.tripEnd),
        MAX_TRIP_NIGHTS,
      );
      if (nights > 0) {
        const entry = tripResults.availabilityByPoiId.get(Number(c.id));
        availHtml = availabilityHtml(entry, tripResults.tripStart, nights);
      }
    }
    body += `<div class="tb-card" data-id="${escapeHtml(String(c.id))}">
      <span class="tb-card-dot" style="background:${color}"></span>
      <div class="tb-card-body">
        <div class="tb-card-name">${escapeHtml(c.name)}</div>
        ${c.sub ? `<div class="tb-card-sub">${escapeHtml(c.sub)}</div>` : ''}
        <div class="tb-card-meta">
          <span class="tb-card-dist">${formatRouteKm(c.routeKm)}</span>
          ${ratingHtml}${sitesHtml}${seasonHtml}
        </div>
        ${availHtml}
      </div>
    </div>`;
  }
  el.innerHTML = head + `<div class="tb-results-body">${body}</div>`;
  el.classList.add('visible');
  el.scrollTop = scrollY;
  bindResultsHead(el);

  // Bind once: when the user toggles a category in the right panel, re-render
  // the card list so it reflects what's visible on the map.
  if (!tripResults.legendBound) {
    tripResults.legendBound = true;
    for (const id of CG_LEGEND_TOGGLES) {
      document.getElementById(id)?.addEventListener('change', () => renderResults());
    }
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
