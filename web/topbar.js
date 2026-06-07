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

import { state, distanceKm } from './core.js';
import { fitAndSelect } from './search.js';

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

// --- public --------------------------------------------------------------

export function initTopbar(map, getPinSearchIndex) {
  mapRef = map;
  pinSearchIndex = getPinSearchIndex;

  injectStyles();
  injectDom();
  bindEvents();
  bindPinClicks();
  renderRows();

  // Expose the corridor polygon to app.js's bbox refresh so it can include
  // it in the POST /api/pois body. Returns null when no route is active —
  // app.js then sends a normal bbox-only request.
  window.__rtTripCorridor = () => trip.corridor;
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

  @media (max-width: 768px) {
    #topbar { left: 8px; right: 8px; width: auto; max-width: none; }
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
  // replace it. In directions mode, populate the active row (whichever
  // input the user last focused). The existing drawer/popup also opens
  // because layers.js click handlers run independently.
  const i = (trip.mode === 'browse') ? 0 : Math.max(activeRowIdx, 0);
  setStop(i, { name: pin.name, lng: pin.lng, lat: pin.lat, kind: pin.kind });
  rerender();
  if (trip.mode === 'directions' && allStopsFilled()) tryFetchRoute();
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
  let geocodeResults = [];
  try {
    geocodeAbort = new AbortController();
    const c = mapRef.getCenter();
    const proximity = state.userLocation
      ? `&proximity=${state.userLocation.lng},${state.userLocation.lat}`
      : `&proximity=${c.lng.toFixed(4)},${c.lat.toFixed(4)}`;
    const r = await fetch(
      `/api/geocode?q=${encodeURIComponent(q)}&autocomplete=1&limit=5${proximity}`,
      { signal: geocodeAbort.signal });
    if (r.ok) {
      const j = await r.json();
      geocodeResults = (j.results || []).map(it => ({
        kind: it.place_type === 'address' ? 'ADDR' : 'PLACE',
        name: it.place_name,
        lng: it.lng, lat: it.lat,
        source: 'geocode',
      }));
    }
  } catch (e) {
    if (e.name !== 'AbortError') console.warn('[topbar] geocode failed', e);
  }
  currentResults = [...geocodeResults, ...pinResults].slice(0, 12);
  dropdownIdx = -1;
  renderDropdown();
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
    const section = (r.source === 'geocode') ? 'Places' : 'Map pins';
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
  const i = Math.max(activeRowIdx, 0);
  setStop(i, {
    name: result.name,
    lng: result.lng, lat: result.lat,
    kind: result.kind,
    pinItem: result.pinItem || null,
  });

  // In browse mode, fly to the place. If it was a pin search result, also
  // open the existing drawer/popup via fitAndSelect.
  if (trip.mode === 'browse') {
    if (result.source === 'pin' && result.pinItem) {
      fitAndSelect(result.pinItem);
    } else {
      const zoom = result.kind === 'ADDR' ? 14 : 10;
      mapRef.flyTo({ center: [result.lng, result.lat], zoom, speed: 1.6 });
    }
  }

  rerender();
  if (trip.mode === 'directions' && allStopsFilled()) tryFetchRoute();
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
  // Focus the first empty input
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
}

function onRowX(i, wasFilled) {
  if (wasFilled) {
    // Filled row → clear text + value, keep the row
    trip.stops[i] = null;
    rerender();
    removeRouteLayer();
    hideStatus();
    notifyCorridorChanged();
    setTimeout(() => {
      const el = document.querySelector(`.tb-row[data-i="${i}"] .tb-input`);
      if (el) { activeRowIdx = i; el.focus(); }
    }, 0);
  } else {
    // Empty row → remove if allowed (>= 2 stops in directions mode)
    if (trip.mode === 'directions' && trip.stops.length <= 2) return;
    trip.stops.splice(i, 1);
    if (trip.stops.length === 0) {
      onClearAll();
      return;
    }
    rerender();
    if (allStopsFilled()) tryFetchRoute();
    else { removeRouteLayer(); hideStatus(); notifyCorridorChanged(); }
  }
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
  const head = `<strong>${distKm.toFixed(0)} km</strong>` +
    `<span class="tb-stat-sep">·</span>${formatDuration(durHrs)}`;
  let body = '';
  if (legs.length > 1) {
    body = '<div style="margin-top:4px; font-size:10px; color:var(--cg-faint);">';
    legs.forEach((l, i) => {
      const km = (l.distance_m / 1000).toFixed(0);
      const min = Math.round(l.duration_s / 60);
      body += `${escapeHtml(stopLabel(i))} → ${escapeHtml(stopLabel(i + 1))}: ${km} km · ${min} min<br>`;
    });
    body += '</div>';
  }
  showStatus(head + body);
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

  // Buttons visibility
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
function formatDuration(hrs) {
  const h = Math.floor(hrs);
  const m = Math.round((hrs - h) * 60);
  if (h === 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}
