import { state, fetchJSON, setCount, geomCenter, zoomForBbox, reinstallOverlays } from './core.js';
import {
  BASEMAPS,
  getInitialBasemapKey,
  initBasemapPicker,
  installSatellite,
  bindSatelliteToggle,
} from './basemap.js';
import {
  toGeoJSON,
  installSCLayer,
  installCGLayer,
  installPFLayer,
  installParkLayers,
  installStateLines,
  setCGData,
  setPFData,
  setNPData,
  setSPData,
  synthesizeClick,
} from './layers.js';
import { initSearch, registerSearchItems } from './search.js';

const initialKey = getInitialBasemapKey();

const map = new maplibregl.Map({
  container: 'map',
  style: BASEMAPS[initialKey].style,
  center: [-98.5, 39.5],
  zoom: 3.6,
});
state.map = map;

// QA hooks — referenced by qa/smoke.spec.mjs. Harmless globals (the test is
// the only consumer); kept unconditional so the smoke doesn't need a flag-flip.
globalThis.__rtMap = map;
globalThis.__rtState = state;

map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-left');
// enableHighAccuracy:false: at 50km decision scale we don't need GPS warm-up
// + battery drain. trackUserLocation:false: single fetch, no watchPosition.
const geolocate = new maplibregl.GeolocateControl({
  positionOptions: { enableHighAccuracy: false, timeout: 8000 },
  trackUserLocation: false,
  showUserHeading: false,
  fitBoundsOptions: { maxZoom: 13 },
});
map.addControl(geolocate, 'bottom-right');

// Single source of truth for "where am I" — popups read this for distance,
// search uses it for "sort by nearest". null until first geolocate success.
let userLocationMarker = null;
geolocate.on('geolocate', (e) => {
  state.userLocation = { lng: e.coords.longitude, lat: e.coords.latitude };
  hideGeoBanner();
  showUserLocationMarker(state.userLocation.lng, state.userLocation.lat);
  if (typeof window.rerenderSearchResults === 'function') window.rerenderSearchResults();
});
geolocate.on('error', (e) => {
  state.userLocation = null;
  // PERMISSION_DENIED=1; POSITION_UNAVAILABLE=2; TIMEOUT=3
  const msg = e?.code === 1
    ? 'Location permission denied — turn on in Settings to use "nearest to me"'
    : 'Couldn\'t get your location';
  showGeoBanner(msg);
  if (typeof window.rerenderSearchResults === 'function') window.rerenderSearchResults();
});

// Custom user-location puck: 14px blue dot, 3px white stroke, 28px halo at
// 0.18 opacity. We render it ourselves because trackUserLocation:false means
// MapLibre's built-in puck only flashes briefly during the single fetch.
function showUserLocationMarker(lng, lat) {
  if (!userLocationMarker) {
    const wrap = document.createElement('div');
    wrap.style.cssText = 'position:relative;width:28px;height:28px;pointer-events:none;';
    wrap.innerHTML =
      '<div style="position:absolute;inset:0;border-radius:50%;background:#0A84FF;opacity:0.18;"></div>' +
      '<div style="position:absolute;left:50%;top:50%;width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;background:#0A84FF;border:3px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,0.3);"></div>';
    userLocationMarker = new maplibregl.Marker({ element: wrap, anchor: 'center' });
  }
  userLocationMarker.setLngLat([lng, lat]).addTo(map);
}

let geoBannerEl = null;
let geoBannerTimer = null;
function showGeoBanner(msg) {
  if (!geoBannerEl) {
    geoBannerEl = document.createElement('div');
    geoBannerEl.className = 'geo-banner';
    document.body.appendChild(geoBannerEl);
  }
  geoBannerEl.textContent = msg;
  geoBannerEl.style.display = 'block';
  clearTimeout(geoBannerTimer);
  geoBannerTimer = setTimeout(hideGeoBanner, 5000);
}
function hideGeoBanner() {
  if (geoBannerEl) geoBannerEl.style.display = 'none';
}

initBasemapPicker(initialKey);
bindSatelliteToggle();
initSearch();

// Mobile panel drawer toggle
(function initPanelToggle() {
  const btn = document.getElementById('panel-toggle');
  const panel = document.getElementById('panel');
  if (!btn || !panel) return;
  btn.addEventListener('click', () => {
    panel.classList.toggle('open');
    btn.textContent = panel.classList.contains('open') ? '✕' : '☰';
  });
  // Tapping map closes drawer on mobile
  document.getElementById('map').addEventListener('click', () => {
    if (panel.classList.contains('open')) {
      panel.classList.remove('open');
      btn.textContent = '☰';
    }
  });
})();

// Fires on first load AND after map.setStyle() — the latter wipes sources/layers,
// so we reinstall from cached GeoJSON.
// Fires on initial style ready AND after setStyle({diff:false}). We reinstall
// every overlay from cached GeoJSON because setStyle wipes custom sources,
// layers, and layer-scoped event handlers.
map.on('style.load', () => {
  state.mapReady = true;
  installSatellite();
  if (state.overlayData.states) installStateLines(state.overlayData.states);
  if (state.overlayData.sp && state.overlayData.np) installParkLayers(state.overlayData.np, state.overlayData.sp);
  if (state.overlayData.cg) installCGLayer(state.overlayData.cg);
  if (state.overlayData.pf) installPFLayer(state.overlayData.pf);
  if (state.overlayData.sc) installSCLayer(state.overlayData.sc);
});

// Flatten /api/pois feature shape to the flat-property shape every layer +
// popup expects. The Kotlin endpoint nests source-specific fields under `raw`
// (so the schema is one row in Postgres) but the rest of the webapp predates
// that — keep the boundary here and the inside stays simple.
//
// For campgrounds: `raw.category` is federal/state/local/provincial — that's
// the value the legend toggles + circle-color match against, so we promote it
// to the top-level `category`. The API's coarser `category: "campground"` is
// dropped on the way through.
function flattenPoi(f) {
  const p = f.properties || {};
  const raw = p.raw || {};
  const flat = { id: f.id, ...raw, ...p };
  delete flat.raw;
  if (p.category === 'campground' && raw.category) flat.category = raw.category;
  if (p.category === 'national-park' || p.category === 'state-park') {
    // Park layers read Unit_Nm / Loc_Nm / State_Nm / GIS_Acres / Mang_Name
    // straight off properties. Those names live in raw — keep them.
    flat.Unit_Nm = raw.Unit_Nm || p.unit_name || p.name;
    flat.State_Nm = raw.State_Nm || p.region || '';
  }
  flat.name = p.name || raw.name || flat.name;
  return { ...f, properties: flat };
}

const EMPTY_FC = { type: 'FeatureCollection', features: [] };

async function load() {
  const status = document.getElementById('status');

  // Superchargers stay static — 1.4k features (~120KB) is fine, they don't
  // change per-pan, and the supercharge.info fallback is the only safety net
  // we have if the local feed fails. SC remain searchable globally.
  (async () => {
    try {
      status.textContent = 'Loading Superchargers…';
      let fc = null;
      try {
        const local = await fetchJSON('/data/tesla-superchargers.geojson');
        if (local?.features?.length > 100) fc = local;
      } catch (_) { /* fall through */ }
      if (!fc) {
        const all = await fetchJSON('https://supercharge.info/service/supercharge/allSites');
        const sites = all.filter(s => s?.gps && (s.address?.countryId === 100 || s.address?.countryId === 101));
        fc = toGeoJSON(sites);
      }
      const counts = { open: 0, construction: 0, permit: 0, plan: 0, closed: 0 };
      fc.features.forEach(f => { counts[f.properties.group || 'open']++; });
      for (const [k, v] of Object.entries(counts)) setCount('c-' + k, v);
      state.overlayData.sc = fc;
      for (const f of fc.features) {
        const p = f.properties;
        if (p.status === 'CLOSED_PERM') continue;
        const [lng, lat] = f.geometry.coordinates;
        registerSearchItems([{
          name: p.name || '',
          sub: [p.city, p.state].filter(Boolean).join(', '),
          kind: 'SC', color: '#e82127',
          scGroup: p.group || 'open',
          lng, lat, zoom: 13,
          onSelect: () => synthesizeClick(['sc-points-hit', 'sc-points'], [lng, lat]),
        }]);
      }
      reinstallOverlays();
      status.textContent = fc.features.length.toLocaleString() + ' Superchargers';
    } catch (err) {
      console.error(err);
      status.textContent = 'Supercharger load failed: ' + err.message;
    }
  })();

  // State boundary lines — small, static, no bbox needed.
  (async () => {
    try {
      state.overlayData.states = await fetchJSON('/data/us-states.geojson');
      reinstallOverlays();
    } catch (err) {
      console.error('State lines load failed:', err);
    }
  })();

  // Install empty bbox-driven layers up front so click handlers + style-swap
  // reinstall logic are wired before the first /api/pois response lands.
  // setData updates fill them in on each moveend.
  state.overlayData.np = EMPTY_FC;
  state.overlayData.sp = EMPTY_FC;
  state.overlayData.pf = EMPTY_FC;
  state.overlayData.cg = EMPTY_FC;
  reinstallOverlays();

  // bbox-on-moveend. One round-trip per pan, debounced 250ms so dragging
  // doesn't fire mid-gesture. AbortController kills the in-flight request
  // when the user keeps panning. Counts on the legend reflect the current
  // viewport, not a global total.
  const seenSearchIds = { CG: new Set(), NP: new Set(), SP: new Set(), PF: new Set() };
  const cgCounts = { federal: 0, state: 0, local: 0, provincial: 0, other: 0 };
  const CG_ZOOM_THRESHOLD = 6;
  let cgUnlocked = false;
  let inflight = null;
  let debounceTimer = null;

  async function refreshBbox() {
    const m = state.map;
    if (!m) return;
    const b = m.getBounds();
    // /api/pois rejects full-continent for sanity; trust the server cap and
    // just always send the actual bounds. The 2000-row truncation defends.
    const west = b.getWest(), south = b.getSouth(), east = b.getEast(), north = b.getNorth();
    // Below z6, skip campgrounds — the design preserves the lazy-load gate.
    const wantCG = m.getZoom() >= CG_ZOOM_THRESHOLD || cgUnlocked;
    if (wantCG) cgUnlocked = true;
    const cats = ['national-park', 'state-park', 'planet-fitness'];
    if (wantCG) cats.push('campground');

    if (inflight) inflight.abort();
    inflight = new AbortController();
    const url = `/api/pois?bbox=${west},${south},${east},${north}&category=${cats.join(',')}`;
    let fc;
    try {
      const r = await fetch(url, { signal: inflight.signal });
      if (!r.ok) throw new Error(`/api/pois HTTP ${r.status}`);
      fc = await r.json();
    } catch (err) {
      if (err.name === 'AbortError') return;
      console.error('bbox fetch failed:', err);
      return;
    }
    inflight = null;

    const np = [], sp = [], pf = [], cg = [];
    for (const raw of fc.features) {
      const f = flattenPoi(raw);
      const c = raw.properties?.category;
      if (c === 'national-park') np.push(f);
      else if (c === 'state-park') sp.push(f);
      else if (c === 'planet-fitness') pf.push(f);
      else if (c === 'campground') cg.push(f);
    }
    setNPData({ type: 'FeatureCollection', features: np });
    setSPData({ type: 'FeatureCollection', features: sp });
    setPFData({ type: 'FeatureCollection', features: pf });
    setCGData({ type: 'FeatureCollection', features: cg });
    state.overlayData.np = { type: 'FeatureCollection', features: np };
    state.overlayData.sp = { type: 'FeatureCollection', features: sp };
    state.overlayData.pf = { type: 'FeatureCollection', features: pf };
    state.overlayData.cg = { type: 'FeatureCollection', features: cg };

    setCount('c-np', np.length);
    setCount('c-sp', sp.length);
    setCount('c-pf', pf.length);
    cgCounts.federal = 0; cgCounts.state = 0; cgCounts.local = 0;
    cgCounts.provincial = 0; cgCounts.other = 0;
    cg.forEach(f => {
      const cat = f.properties.category;
      cgCounts[cat] = (cgCounts[cat] || cgCounts.other) + 1;
    });
    for (const [k, v] of Object.entries(cgCounts)) setCount('c-cg-' + k, v);
    if (wantCG) {
      const hint = document.getElementById('cg-load-hint');
      if (hint) hint.remove();
    }

    // Append-only search index: each viewport adds whatever's new without
    // wiping prior entries, so panning back to a previous area still has
    // those POIs searchable. Dedupe by db id.
    const indexAdd = (items) => items.length && registerSearchItems(items);
    const npNew = [], spNew = [], pfNew = [], cgNew = [];
    for (const f of np) {
      if (seenSearchIds.NP.has(f.id)) continue;
      seenSearchIds.NP.add(f.id);
      const [lng, lat, bbox] = geomCenter(f.geometry);
      const p = f.properties;
      npNew.push({
        name: p.Unit_Nm || p.name || 'Park',
        sub: p.State_Nm || '',
        kind: 'NP', color: '#2e7d32',
        lng, lat, zoom: zoomForBbox(bbox),
        onSelect: () => synthesizeClick(['np-pts-hit', 'np-fill'], [lng, lat]),
      });
    }
    for (const f of sp) {
      if (seenSearchIds.SP.has(f.id)) continue;
      seenSearchIds.SP.add(f.id);
      const [lng, lat, bbox] = geomCenter(f.geometry);
      const p = f.properties;
      spNew.push({
        name: p.Unit_Nm || p.name || 'Park',
        sub: p.State_Nm || '',
        kind: 'SP', color: '#8d6e63',
        lng, lat, zoom: zoomForBbox(bbox),
        onSelect: () => synthesizeClick(['sp-pts-hit', 'sp-fill'], [lng, lat]),
      });
    }
    for (const f of pf) {
      if (seenSearchIds.PF.has(f.id)) continue;
      seenSearchIds.PF.add(f.id);
      const [lng, lat] = f.geometry.coordinates;
      const p = f.properties;
      pfNew.push({
        name: p.name || 'Planet Fitness',
        sub: [p.city, p.state].filter(Boolean).join(', '),
        kind: 'PF', color: '#7b4bb5',
        lng, lat, zoom: 14,
        onSelect: () => synthesizeClick(['pf-points-hit', 'pf-points'], [lng, lat]),
      });
    }
    for (const f of cg) {
      if (seenSearchIds.CG.has(f.id)) continue;
      seenSearchIds.CG.add(f.id);
      const [lng, lat] = f.geometry.coordinates;
      const p = f.properties;
      cgNew.push({
        name: p.name,
        sub: [p.typeLabel, p.state].filter(Boolean).join(' · '),
        kind: 'CG', color: '#2e7d32',
        cgCategory: p.category,
        lng, lat, zoom: 13,
        onSelect: () => synthesizeClick(['cg-points-hit', 'cg-points'], [lng, lat]),
      });
    }
    indexAdd(npNew); indexAdd(spNew); indexAdd(pfNew); indexAdd(cgNew);
  }

  function scheduleBboxRefresh() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(refreshBbox, 250);
  }

  map.on('moveend', scheduleBboxRefresh);
  // First load: fire once style is ready so layers are mounted.
  if (state.mapReady) refreshBbox();
  else map.once('style.load', () => refreshBbox());
  // Search-box focus unlocks campgrounds even at low zoom (so search results
  // can include cgs that aren't in the current viewport at z<6).
  document.getElementById('search').addEventListener('focus', () => {
    if (cgUnlocked) return;
    cgUnlocked = true;
    refreshBbox();
  }, { once: true });
}

load();
