import { state, geomCenter } from './core.js';
import {
  openCampgroundPopup,
  openParkPopup,
  openPlanetFitnessPopup,
  openSuperchargerPopup,
} from './popups.js';

// Remove a source if present, plus any layers that reference it. Used before
// re-adding on style.load to avoid "source already exists" errors while still
// being idempotent.
export function resetOverlay(sourceIds, layerIds) {
  const { map } = state;
  for (const id of layerIds) if (map.getLayer(id)) map.removeLayer(id);
  for (const id of sourceIds) if (map.getSource(id)) map.removeSource(id);
}

export function bindCursor(layerId) {
  const { map } = state;
  map.on('mouseenter', layerId, () => map.getCanvas().style.cursor = 'pointer');
  map.on('mouseleave', layerId, () => map.getCanvas().style.cursor = '');
}

// SC are pre-filtered to OPEN-only at fetch time, so the runtime layer
// filter is just on/off based on the f-open checkbox.
export function updateFilter() {
  const { map } = state;
  const visible = document.getElementById('f-open').checked;
  map.setLayoutProperty('sc-points', 'visibility', visible ? 'visible' : 'none');
  map.setLayoutProperty('sc-points-hit', 'visibility', visible ? 'visible' : 'none');
}

const CG_COLOR = {
  federal: '#2e7d32',       // dark green — US federal
  provincial: '#2e7d32',    // same green — BC provincial parks (parallel to US federal)
  state: '#558b2f',         // medium green — US state
  local: '#9ccc65',         // light green — US county/municipal
  other: '#cddc39',         // unclassified
};

export function installCGLayer(geojson) {
  const { map } = state;
  resetOverlay(['cg'], ['cg-points', 'cg-points-hit']);
  map.addSource('cg', { type: 'geojson', data: geojson });
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
      'circle-color': ['match', ['get', 'category'],
        'federal',    CG_COLOR.federal,
        'provincial', CG_COLOR.provincial,
        'state',      CG_COLOR.state,
        'local',      CG_COLOR.local,
        CG_COLOR.other,
      ],
      'circle-stroke-color': '#fff',
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

  const applyCGFilter = () => {
    const cats = [];
    for (const id of ['f-cg-federal', 'f-cg-state', 'f-cg-local', 'f-cg-provincial']) {
      if (document.getElementById(id).checked) cats.push(id.replace('f-cg-', ''));
    }
    const visibility = cats.length === 0 ? 'none' : 'visible';
    map.setLayoutProperty('cg-points', 'visibility', visibility);
    map.setLayoutProperty('cg-points-hit', 'visibility', visibility);
    if (cats.length > 0) {
      // 'other' is mostly unclassified federal entries; bundle it with federal.
      const filterCats = cats.includes('federal') ? [...cats, 'other'] : cats;
      const filter = ['in', ['get', 'category'], ['literal', filterCats]];
      map.setFilter('cg-points', filter);
      map.setFilter('cg-points-hit', filter);
    }
  };
  applyCGFilter();

  // Layer-scoped map handlers do NOT survive setStyle — always rebind here.
  // Bind to the hit layer (transparent, generous radius); MapLibre dispatches
  // to the topmost matching layer, so the underlying visual layer never sees
  // the click.
  map.on('click', 'cg-points-hit', (e) => {
    const f = e.features[0];
    openCampgroundPopup(f);
  });
  bindCursor('cg-points-hit');

  if (state.bound.cg) return;
  state.bound.cg = true;
  // DOM listeners live on checkbox elements — bind once, survive forever.
  for (const id of ['f-cg-federal', 'f-cg-state', 'f-cg-local', 'f-cg-provincial']) {
    document.getElementById(id).addEventListener('change', applyCGFilter);
  }
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
      'line-color': '#4a4a4a',
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
    paint: { 'fill-color': '#8d6e63', 'fill-opacity': 0.28 } }, anchor);
  map.addLayer({ id: 'sp-line', type: 'line', source: 'sp',
    paint: { 'line-color': '#5d4037', 'line-width': 1, 'line-opacity': 0.75 } }, anchor);

  // National Parks — fade fill from 0.32 at z<10 down to 0.12 at z>=10 so it
  // stops competing with campground/Supercharger dots when zoomed in.
  map.addSource('np', { type: 'geojson', data: np });
  map.addLayer({ id: 'np-fill', type: 'fill', source: 'np',
    paint: {
      'fill-color': '#2e7d32',
      'fill-opacity': ['interpolate', ['linear'], ['zoom'], 8, 0.32, 10, 0.12],
    } }, anchor);
  map.addLayer({ id: 'np-line', type: 'line', source: 'np',
    paint: { 'line-color': '#1b5e20', 'line-width': 1.2, 'line-opacity': 0.85 } }, anchor);

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
      'circle-color': '#8d6e63',
      'circle-stroke-color': '#5d4037',
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
      'circle-color': '#2e7d32',
      'circle-stroke-color': '#1b5e20',
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

  const parkClick = (kind) => (e) => {
    openParkPopup(kind, e.features[0], e.lngLat);
  };
  map.on('click', 'np-fill', parkClick('np'));
  map.on('click', 'sp-fill', parkClick('sp'));
  map.on('click', 'np-pts-hit',  parkClick('np'));
  map.on('click', 'sp-pts-hit',  parkClick('sp'));
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
      'circle-color': '#7b4bb5',
      'circle-stroke-color': '#fff',
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

  map.on('click', 'pf-points-hit', (e) => {
    const f = e.features[0];
    openPlanetFitnessPopup(f);
  });
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
      'circle-color': ['get', 'color'],
      'circle-stroke-color': '#fff',
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

  map.on('click', 'sc-points-hit', (e) => {
    openSuperchargerPopup(e.features[0]);
  });
  bindCursor('sc-points-hit');

  if (state.bound.sc) return;
  state.bound.sc = true;
  document.getElementById('f-open').addEventListener('change', updateFilter);
}

// Update existing source data without rebuilding layers. Used by the bbox
// loader on moveend — installX layers stay mounted, only the GeoJSON changes.
// No-ops if the layer hasn't been installed yet (initial load races moveend).
export function setCGData(geojson) {
  const src = state.map?.getSource('cg');
  if (src) src.setData(geojson);
}
export function setPFData(geojson) {
  const src = state.map?.getSource('pf');
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
