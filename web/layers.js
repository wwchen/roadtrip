import { state, geomCenter, escapeHtml } from './core.js';
import { openCampgroundDrawer } from './drawer/campground.js';
import { openParkDrawer } from './drawer/park.js';
import { openPlanetFitnessDrawer } from './drawer/planet-fitness.js';
import { openSuperchargerDrawer } from './drawer/supercharger.js';
import { token } from './design-system/tokens.js';

// Remove a source if present, plus any layers that reference it. Used before
// re-adding on style.load to avoid "source already exists" errors while still
// being idempotent.
export function resetOverlay(sourceIds, layerIds) {
  const { map } = state;
  for (const id of layerIds) if (map.getLayer(id)) map.removeLayer(id);
  for (const id of sourceIds) if (map.getSource(id)) map.removeSource(id);
}

const cursorHandlers = new Map();

export function rebindLayerHandler(type, layerId, handler) {
  const { map } = state;
  map.off(type, layerId, handler);
  map.on(type, layerId, handler);
}

export function bindCursor(layerId) {
  let handlers = cursorHandlers.get(layerId);
  if (!handlers) {
    handlers = {
      enter: () => { state.map.getCanvas().style.cursor = 'pointer'; },
      leave: () => { state.map.getCanvas().style.cursor = ''; },
    };
    cursorHandlers.set(layerId, handlers);
  }
  rebindLayerHandler('mouseenter', layerId, handlers.enter);
  rebindLayerHandler('mouseleave', layerId, handlers.leave);
}

// SC are pre-filtered to OPEN-only at fetch time, so the runtime layer
// filter is just on/off based on the f-open checkbox.
export function updateFilter() {
  const { map } = state;
  const visible = document.getElementById('f-open').checked;
  map.setLayoutProperty('sc-points', 'visibility', visible ? 'visible' : 'none');
  map.setLayoutProperty('sc-points-hit', 'visibility', visible ? 'visible' : 'none');
}

export const UNCATEGORIZED_AGENCY = 'Uncategorized';
const CG_EMPTY_FC = { type: 'FeatureCollection', features: [] };
const CG_AGENCY_LEGEND_ID = 'cg-agency-legend';
const CG_LAYER_IDS = ['cg-points', 'cg-points-hit'];

// Persistent selection: agencies the user explicitly UN-checked. Absence
// from this set == shown, so agencies never seen before default on and a
// pan to a new region shows everything there without re-enabling.
const cgHiddenAgencies = new Set();
const cgFilterListeners = new Set();
let lastCgGeojson = CG_EMPTY_FC;
let cgLegendBound = false;

function openFirstCampgroundFeature(e) {
  const f = e.features?.[0];
  if (f) openCampgroundDrawer(f);
}

function openNationalParkFeature(e) {
  const f = e.features?.[0];
  if (f) openParkDrawer('np', f, e.lngLat);
}

function openStateParkFeature(e) {
  const f = e.features?.[0];
  if (f) openParkDrawer('sp', f, e.lngLat);
}

function openFirstPlanetFitnessFeature(e) {
  const f = e.features?.[0];
  if (f) openPlanetFitnessDrawer(f);
}

function openFirstSuperchargerFeature(e) {
  const f = e.features?.[0];
  if (f) openSuperchargerDrawer(f);
}

function normalizeAgency(value) {
  return typeof value === 'string' ? value.trim() : '';
}

export function featureAgency(featureOrProps) {
  const props = featureOrProps?.properties || featureOrProps || {};
  return normalizeAgency(props.agency) || UNCATEGORIZED_AGENCY;
}

export function agencyCountsInViewport(geojson = lastCgGeojson) {
  const counts = new Map();
  for (const feature of geojson?.features || []) {
    const props = feature.properties || {};
    if (props.category !== 'campground') continue;
    const agency = featureAgency(props);
    counts.set(agency, (counts.get(agency) || 0) + 1);
  }
  return counts;
}

export function agenciesInViewport(geojson = lastCgGeojson) {
  return [...agencyCountsInViewport(geojson).keys()].sort((a, b) => a.localeCompare(b));
}

export function isAgencyHidden(agency) {
  return cgHiddenAgencies.has(agency);
}

export function setAgencyHidden(agency, hidden) {
  if (hidden) cgHiddenAgencies.add(agency);
  else cgHiddenAgencies.delete(agency);
}

export function campgroundFeaturePassesFilter(featureOrProps) {
  return !cgHiddenAgencies.has(featureAgency(featureOrProps));
}

export function notifyCampgroundFilterChanged() {
  for (const listener of cgFilterListeners) listener();
}

export function onCampgroundFilterChange(listener) {
  cgFilterListeners.add(listener);
  return () => cgFilterListeners.delete(listener);
}

// One checkbox per agency present in the current viewport, sorted, with its
// live count. Rows come purely from the current features — no accumulating
// "known agencies" set — so panning away from a region drops its agencies.
export function renderCampgroundLegend(geojson = lastCgGeojson) {
  const host = document.getElementById(CG_AGENCY_LEGEND_ID);
  if (!host) return;
  const counts = agencyCountsInViewport(geojson);
  const agencies = [...counts.keys()].sort((a, b) => a.localeCompare(b));
  host.innerHTML = agencies.map(agency => `
    <label class="cg-agency-row">
      <input type="checkbox" data-cg-agency="${escapeHtml(agency)}"${isAgencyHidden(agency) ? '' : ' checked'}>
      <span class="legend-dot" style="background:var(--rt-layer-cg)"></span>
      ${escapeHtml(agency)} <span class="count">(${counts.get(agency)})</span>
    </label>
  `).join('');
}

function onLegendChange(e) {
  const target = e.target;
  if (!(target instanceof HTMLInputElement)) return;
  const agency = target.dataset.cgAgency;
  if (!agency) return;
  setAgencyHidden(agency, !target.checked);
  applyCGFilter();
  notifyCampgroundFilterChanged();
}

export function applyCGFilter() {
  const { map } = state;
  if (!map?.getLayer('cg-points') || !map?.getLayer('cg-points-hit')) return;
  // MapLibre can only test present properties; the Uncategorized sentinel
  // represents features with NO agency, so hiding it means excluding
  // agency-absent features, handled with a has-agency guard.
  const hideUncategorized = cgHiddenAgencies.has(UNCATEGORIZED_AGENCY);
  const namedHidden = [...cgHiddenAgencies].filter(a => a !== UNCATEGORIZED_AGENCY);
  const clauses = ['all'];
  if (namedHidden.length > 0) {
    clauses.push(['!', ['in', ['get', 'agency'], ['literal', namedHidden]]]);
  }
  if (hideUncategorized) {
    clauses.push(['has', 'agency']);
  }
  const filter = clauses.length === 1 ? null : clauses;
  for (const id of CG_LAYER_IDS) {
    map.setLayoutProperty(id, 'visibility', 'visible');
    map.setFilter(id, filter);
  }
}

function bindCGLegendControls() {
  if (cgLegendBound) return;
  cgLegendBound = true;
  document.getElementById(CG_AGENCY_LEGEND_ID)?.addEventListener('change', onLegendChange);
}

export function installCGLayer(geojson) {
  const { map } = state;
  resetOverlay(['cg'], ['cg-points', 'cg-points-hit']);
  map.addSource('cg', { type: 'geojson', data: geojson });
  // Single campground pin color: the legend now filters by agency (50+
  // values), which can't be color-coded legibly, so agency is conveyed by
  // the legend row rather than the dot.
  // Radius scales with campsite count (sqrt), with a clickable floor. Per-zoom
  // stops keep dots clickable even at continental zoom.
  const sizeBySites = ['sqrt', ['coalesce', ['get', 'sites'], 15]];
  map.addLayer({
    id: 'cg-points',
    type: 'circle',
    source: 'cg',
    paint: {
      'circle-radius': [
        'interpolate', ['linear'], ['zoom'],
        3,  ['max', 3, ['interpolate', ['linear'], sizeBySites,  1, 3,  5, 3.5, 15, 4,   50, 5,   200, 6.5, 1100, 9]],
        6,  ['max', 4, ['interpolate', ['linear'], sizeBySites,  1, 4,  5, 4.5, 15, 5.5, 50, 7,   200, 10,  1100, 14]],
        10, ['max', 5, ['interpolate', ['linear'], sizeBySites,  1, 5,  5, 6,   15, 8,   50, 11,  200, 16,  1100, 24]],
      ],
      'circle-color': token('--rt-layer-cg'),
      'circle-stroke-color': token('--rt-map-pin-stroke'),
      'circle-stroke-width': 0.8,
      'circle-opacity': 0.85,
    },
  }, map.getLayer('sc-points') ? 'sc-points' : undefined);

  // Transparent hit layer above the visual layer — gives every dot a 36px
  // (radius 18) target on phones regardless of how small the visual circle
  // looks. Click + cursor handlers bind to the hit layer; the topmost layer
  // wins on tap, so visual stays small while target stays generous.
  map.addLayer({
    id: 'cg-points-hit',
    type: 'circle',
    source: 'cg',
    paint: {
      'circle-radius': 18,
      'circle-opacity': 0,
    },
  });

  lastCgGeojson = geojson || CG_EMPTY_FC;
  renderCampgroundLegend(lastCgGeojson);
  applyCGFilter();
  bindCGLegendControls();

  // Layer-scoped map handlers do NOT survive setStyle — always rebind here.
  // Bind to the hit layer (transparent, generous radius); MapLibre dispatches
  // to the topmost matching layer, so the underlying visual layer never sees
  // the click.
  rebindLayerHandler('click', 'cg-points-hit', openFirstCampgroundFeature);
  bindCursor('cg-points-hit');

  if (state.bound.cg) return;
  state.bound.cg = true;
}

export function installStateLines(states) {
  const { map } = state;
  resetOverlay(['states'], ['state-lines']);
  map.addSource('states', { type: 'geojson', data: states });
  map.addLayer({
    id: 'state-lines',
    type: 'line',
    source: 'states',
    paint: {
      'line-color': token('--rt-map-route-alt'),
      'line-width': ['interpolate', ['linear'], ['zoom'], 3, 0.6, 6, 1.0, 10, 1.4],
      'line-opacity': 0.55,
    },
  });
}

// Module-scope so setData helpers can re-derive centroids on bbox updates
// without re-running installParkLayers (which rebuilds layers + handlers).
function toPoints(fc) {
  return {
    type: 'FeatureCollection',
    features: fc.features.map(f => {
      const [lng, lat] = geomCenter(f.geometry);
      return { type: 'Feature', geometry: { type: 'Point', coordinates: [lng, lat] }, properties: f.properties };
    }),
  };
}

export function installParkLayers(np, sp) {
  const { map } = state;
  resetOverlay(['np', 'sp', 'np-pts', 'sp-pts'],
               ['np-fill', 'np-line', 'sp-fill', 'sp-line', 'np-pts', 'sp-pts',
                'np-pts-hit', 'sp-pts-hit']);
  // Anchor = first symbol (label) layer, so park fills sit above roads/water
  // but beneath street/city labels. Works with both raster and vector basemaps.
  const firstLabel = map.getStyle().layers.find(l => l.type === 'symbol');
  const anchor = firstLabel ? firstLabel.id : undefined;

  // State Parks (polygons first so NP overlays them on overlap)
  map.addSource('sp', { type: 'geojson', data: sp });
  map.addLayer({ id: 'sp-fill', type: 'fill', source: 'sp',
    paint: { 'fill-color': token('--rt-map-sp-fill'), 'fill-opacity': 0.28 } }, anchor);
  map.addLayer({ id: 'sp-line', type: 'line', source: 'sp',
    paint: { 'line-color': token('--rt-map-sp-stroke'), 'line-width': 1, 'line-opacity': 0.75 } }, anchor);

  // National Parks — fade fill from 0.32 at z<10 down to 0.12 at z>=10 so it
  // stops competing with campground/Supercharger dots when zoomed in.
  map.addSource('np', { type: 'geojson', data: np });
  map.addLayer({ id: 'np-fill', type: 'fill', source: 'np',
    paint: {
      'fill-color': token('--rt-map-np-fill'),
      'fill-opacity': ['interpolate', ['linear'], ['zoom'], 8, 0.32, 10, 0.12],
    } }, anchor);
  map.addLayer({ id: 'np-line', type: 'line', source: 'np',
    paint: { 'line-color': token('--rt-map-np-stroke'), 'line-width': 1.2, 'line-opacity': 0.85 } }, anchor);

  // Centroid dots — navigation aid at continental zoom, fade out by z10 as polygons take over.
  map.addSource('sp-pts', { type: 'geojson', data: toPoints(sp) });
  map.addLayer({
    id: 'sp-pts', type: 'circle', source: 'sp-pts',
    paint: {
      'circle-radius': [
        'interpolate', ['linear'], ['zoom'],
        3, ['interpolate', ['linear'], ['sqrt', ['coalesce', ['get', 'GIS_Acres'], 100]],   10, 2,   1000, 3.5,  50000, 5],
        6, ['interpolate', ['linear'], ['sqrt', ['coalesce', ['get', 'GIS_Acres'], 100]],   10, 2.5, 1000, 5,    50000, 7],
        9, ['interpolate', ['linear'], ['sqrt', ['coalesce', ['get', 'GIS_Acres'], 100]],   10, 1.5, 1000, 3,    50000, 4],
        10, 0,
      ],
      'circle-color': token('--rt-map-sp-fill'),
      'circle-stroke-color': token('--rt-map-sp-stroke'),
      'circle-stroke-width': 0.8,
      'circle-opacity': 0.85,
    },
  }, anchor);

  map.addSource('np-pts', { type: 'geojson', data: toPoints(np) });
  map.addLayer({
    id: 'np-pts', type: 'circle', source: 'np-pts',
    paint: {
      'circle-radius': [
        'interpolate', ['linear'], ['zoom'],
        3, ['interpolate', ['linear'], ['sqrt', ['coalesce', ['get', 'GIS_Acres'], 10000]],  1000, 3,   50000, 4.5, 1000000, 6.5],
        6, ['interpolate', ['linear'], ['sqrt', ['coalesce', ['get', 'GIS_Acres'], 10000]],  1000, 4,   50000, 6,   1000000, 9],
        9, ['interpolate', ['linear'], ['sqrt', ['coalesce', ['get', 'GIS_Acres'], 10000]],  1000, 2.5, 50000, 4,   1000000, 6],
        10, 0,
      ],
      'circle-color': token('--rt-map-np-fill'),
      'circle-stroke-color': token('--rt-map-np-stroke'),
      'circle-stroke-width': 1,
      'circle-opacity': 0.9,
    },
  }, anchor);

  // Hit layers for centroid taps. Polygons keep their own (large) hit area;
  // these only need to match while the centroid dot is rendered (z<10).
  map.addLayer({
    id: 'sp-pts-hit', type: 'circle', source: 'sp-pts',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 3, 14, 9, 18, 10, 0],
      'circle-opacity': 0,
    },
  });
  map.addLayer({
    id: 'np-pts-hit', type: 'circle', source: 'np-pts',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 3, 14, 9, 18, 10, 0],
      'circle-opacity': 0,
    },
  });

  const applyParkVis = () => {
    const nv = document.getElementById('f-np').checked ? 'visible' : 'none';
    const sv = document.getElementById('f-sp').checked ? 'visible' : 'none';
    for (const id of ['np-fill', 'np-line', 'np-pts', 'np-pts-hit']) map.setLayoutProperty(id, 'visibility', nv);
    for (const id of ['sp-fill', 'sp-line', 'sp-pts', 'sp-pts-hit']) map.setLayoutProperty(id, 'visibility', sv);
  };
  applyParkVis();

  rebindLayerHandler('click', 'np-fill', openNationalParkFeature);
  rebindLayerHandler('click', 'sp-fill', openStateParkFeature);
  rebindLayerHandler('click', 'np-pts-hit', openNationalParkFeature);
  rebindLayerHandler('click', 'sp-pts-hit', openStateParkFeature);
  for (const id of ['np-fill', 'sp-fill', 'np-pts-hit', 'sp-pts-hit']) bindCursor(id);

  if (state.bound.np) return;
  state.bound.np = true;
  document.getElementById('f-np').addEventListener('change', applyParkVis);
  document.getElementById('f-sp').addEventListener('change', applyParkVis);
}

export function installPFLayer(geojson) {
  const { map } = state;
  resetOverlay(['pf'], ['pf-points', 'pf-points-hit']);
  map.addSource('pf', { type: 'geojson', data: geojson });
  map.addLayer({
    id: 'pf-points',
    type: 'circle',
    source: 'pf',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 3, 3, 6, 5, 10, 7],
      // Planet Fitness brand purple.
      'circle-color': token('--rt-layer-pf-pin'),
      'circle-stroke-color': token('--rt-map-pin-stroke'),
      'circle-stroke-width': 1.5,
      'circle-opacity': 0.95,
    },
  });
  map.addLayer({
    id: 'pf-points-hit',
    type: 'circle',
    source: 'pf',
    paint: { 'circle-radius': 18, 'circle-opacity': 0 },
  });

  const applyPFVis = () => {
    const v = document.getElementById('f-pf').checked ? 'visible' : 'none';
    map.setLayoutProperty('pf-points', 'visibility', v);
    map.setLayoutProperty('pf-points-hit', 'visibility', v);
  };
  applyPFVis();

  rebindLayerHandler('click', 'pf-points-hit', openFirstPlanetFitnessFeature);
  bindCursor('pf-points-hit');

  if (state.bound.pf) return;
  state.bound.pf = true;
  document.getElementById('f-pf').addEventListener('change', applyPFVis);
}

export function installSCLayer(geojson) {
  const { map } = state;
  resetOverlay(['sc'], ['sc-points', 'sc-points-hit']);
  map.addSource('sc', { type: 'geojson', data: geojson });
  map.addLayer({
    id: 'sc-points',
    type: 'circle',
    source: 'sc',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 3, 3, 6, 5, 10, 7],
      // Tesla brand red. Was data-driven via ['get', 'color'] from a per-row
      // property, but the slim /api/pois response (PR #123) doesn't ship
      // that any more — paint resolved to null and pins came back black.
      'circle-color': token('--rt-layer-supercharger-pin'),
      'circle-stroke-color': token('--rt-map-pin-stroke'),
      'circle-stroke-width': 1,
      'circle-opacity': 0.9,
    },
  });
  map.addLayer({
    id: 'sc-points-hit',
    type: 'circle',
    source: 'sc',
    paint: { 'circle-radius': 18, 'circle-opacity': 0 },
  });
  updateFilter();

  rebindLayerHandler('click', 'sc-points-hit', openFirstSuperchargerFeature);
  bindCursor('sc-points-hit');

  if (state.bound.sc) return;
  state.bound.sc = true;
  document.getElementById('f-open').addEventListener('change', updateFilter);
}

// Update existing source data without rebuilding layers. Used by the bbox
// loader on moveend — installX layers stay mounted, only the GeoJSON changes.
// No-ops if the layer hasn't been installed yet (initial load races moveend).
export function setCGData(geojson) {
  lastCgGeojson = geojson || CG_EMPTY_FC;
  renderCampgroundLegend(lastCgGeojson);
  const src = state.map?.getSource('cg');
  if (src) src.setData(lastCgGeojson);
  applyCGFilter();
}
export function setPFData(geojson) {
  const src = state.map?.getSource('pf');
  if (src) src.setData(geojson);
}
export function setSCData(geojson) {
  const src = state.map?.getSource('sc');
  if (src) src.setData(geojson);
}
export function setNPData(geojson) {
  const m = state.map;
  if (!m) return;
  const npSrc = m.getSource('np');
  const npPtsSrc = m.getSource('np-pts');
  if (npSrc) npSrc.setData(geojson);
  if (npPtsSrc) npPtsSrc.setData(toPoints(geojson));
}
export function setSPData(geojson) {
  const m = state.map;
  if (!m) return;
  const spSrc = m.getSource('sp');
  const spPtsSrc = m.getSource('sp-pts');
  if (spSrc) spSrc.setData(geojson);
  if (spPtsSrc) spPtsSrc.setData(toPoints(geojson));
}

// Synthesize a click on the first visible layer at the given coordinate —
// used by the search results to open the destination popup after flyTo settles.
// queryRenderedFeatures only sees what's currently visible, so callers must
// first enable the relevant toggle via togglesForItem().
export function synthesizeClick(layerIds, lngLat) {
  const { map } = state;
  const ids = Array.isArray(layerIds) ? layerIds : [layerIds];
  const pt = map.project(lngLat);
  for (const id of ids) {
    if (!map.getLayer(id)) continue;
    const feats = map.queryRenderedFeatures(pt, { layers: [id] });
    if (feats.length) {
      map.fire('click', { lngLat: { lng: lngLat[0], lat: lngLat[1] }, point: pt, features: feats, originalEvent: {} });
      return;
    }
  }
}
