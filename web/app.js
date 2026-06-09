import { state, fetchJSON, setCount, reinstallOverlays } from './core.js';
import {
  BASEMAPS,
  getInitialBasemapKey,
  initBasemapPicker,
  installSatellite,
  bindSatelliteToggle,
} from './basemap.js';
import {
  installSCLayer,
  installCGLayer,
  installPFLayer,
  installParkLayers,
  installStateLines,
  setCGData,
  setPFData,
  setSCData,
  setNPData,
  setSPData,
  synthesizeClick,
} from './layers.js';
import { initSearch, getSearchIndex } from './search.js';
import { closeDrawer } from './drawer/chrome.js';
import { initTopbar } from './topbar.js';

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

map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right');
// enableHighAccuracy:false: at 50km decision scale we don't need GPS warm-up
// + battery drain. trackUserLocation:false: single fetch, no watchPosition.
const geolocate = new maplibregl.GeolocateControl({
  positionOptions: { enableHighAccuracy: false, timeout: 8000 },
  trackUserLocation: false,
  showUserHeading: false,
  fitBoundsOptions: { maxZoom: 13 },
});
map.addControl(geolocate, 'bottom-right');

// Click on empty map → close the drawer. Layer-specific handlers in layers.js
// fire first; this fallback only runs when the click missed every pin layer.
// queryRenderedFeatures filtered by the interactive layers tells us cheaply
// whether anything pickable is under the cursor without subscribing to each.
const INTERACTIVE_LAYERS = [
  'cg-points-hit', 'sc-points', 'pf-points-hit',
  'np-fill', 'sp-fill', 'np-pts-hit', 'sp-pts-hit',
];
map.on('click', (e) => {
  const layers = INTERACTIVE_LAYERS.filter(id => map.getLayer(id));
  const hits = layers.length ? map.queryRenderedFeatures(e.point, { layers }) : [];
  if (hits.length === 0) {
    closeDrawer();
    // Also clear any sticky browse-mode pin selection (row 0 in the topbar
    // + the "A" marker on the map). Pin clicks populate row 0; without this
    // an empty-space click would close the drawer but leave row 0 + marker
    // behind. No-op in directions mode.
    if (typeof window.__rtClearBrowsePin === 'function') window.__rtClearBrowsePin();
  }
});

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
initTopbar(map, getSearchIndex);

// Desktop panel collapse — hide the layers/legend panel and show a small
// pop-out button to bring it back. Mobile uses the existing #panel-toggle
// hamburger flow below.
(function initPanelCollapse() {
  const panel = document.getElementById('panel');
  const collapseBtn = document.getElementById('panel-collapse');
  const showBtn = document.getElementById('panel-show');
  if (!panel || !collapseBtn || !showBtn) return;
  collapseBtn.addEventListener('click', () => {
    panel.classList.add('collapsed');
    showBtn.classList.add('visible');
  });
  showBtn.addEventListener('click', () => {
    panel.classList.remove('collapsed');
    showBtn.classList.remove('visible');
  });
})();

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
// For campgrounds: `subcategory` is federal/state/local/provincial — the
// value the legend toggles + circle-color match against. Promote it to the
// top-level `category` so layers.js filter expressions stay simple.
//
// Slim path (POST /api/pois): properties carries only category +
// subcategory. We rewrite category here for the legend filter; everything
// richer (name, address, provider_ref, raw upstream) is fetched on click
// via GET /api/pois/{id} and re-runs this function in flattenHydrated().
//
// Hydrated path (GET /api/pois/{id}): properties carries the wide row.
// flattenHydrated calls into this same function so the post-hydration
// shape matches what popups read.
function flattenPoi(f) {
  const p = f.properties || {};
  if (p.category === 'campground' && p.subcategory) {
    return { ...f, properties: { ...p, category: p.subcategory } };
  }
  return f;
}


const EMPTY_FC = { type: 'FeatureCollection', features: [] };

async function load() {
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
  state.overlayData.sc = EMPTY_FC;
  reinstallOverlays();

  // bbox-on-moveend. One round-trip per pan, debounced 250ms so dragging
  // doesn't fire mid-gesture. AbortController kills the in-flight request
  // when the user keeps panning. Counts on the legend reflect the current
  // viewport, not a global total.
  const cgCounts = { federal: 0, state: 0, local: 0, provincial: 0, other: 0 };
  const CG_ZOOM_THRESHOLD = 6;
  let cgUnlocked = false;
  let inflight = null;
  let debounceTimer = null;

  // Viewport FeatureCollection cache. Each entry holds the bbox + categories
  // that produced a non-truncated FC; a subsequent pan into a sub-bbox with
  // the same category set skips the /api/pois round-trip and re-renders
  // from memory. Skipped when a corridor route is active (the predicate
  // changes) and when the prior response was truncated (features outside
  // the per-cat budget weren't returned, so a contained sub-view could be
  // missing pins). Ring buffer of 8 keeps the working set across normal
  // pan/zoom-out behavior without unbounded growth.
  const VIEWPORT_CACHE_TTL_MS = 5 * 60 * 1000;
  const VIEWPORT_CACHE_MAX = 8;
  const viewportCache = [];
  function bboxContains(outer, inner) {
    return outer[0] <= inner[0] && outer[1] <= inner[1] &&
           outer[2] >= inner[2] && outer[3] >= inner[3];
  }
  function viewportCacheLookup(bbox, catsKey) {
    const now = Date.now();
    // Evict stale entries first so the search loop sees a clean slate.
    for (let i = viewportCache.length - 1; i >= 0; i--) {
      if (now - viewportCache[i].t > VIEWPORT_CACHE_TTL_MS) viewportCache.splice(i, 1);
    }
    for (let i = viewportCache.length - 1; i >= 0; i--) {
      const e = viewportCache[i];
      if (e.catsKey === catsKey && bboxContains(e.bbox, bbox)) return e.fc;
    }
    return null;
  }
  function viewportCachePut(bbox, catsKey, fc) {
    viewportCache.push({ bbox, catsKey, fc, t: Date.now() });
    while (viewportCache.length > VIEWPORT_CACHE_MAX) viewportCache.shift();
  }

  async function refreshBbox() {
    const m = state.map;
    if (!m) return;
    const b = m.getBounds();
    const west = b.getWest(), south = b.getSouth(), east = b.getEast(), north = b.getNorth();
    const zoom = Math.floor(m.getZoom());
    // When a trip route is active, topbar exposes its waypoints + radius
    // via window.__rtTripRoute. The BE looks the polyline up from
    // RouteCache (seeded by /api/route) and buffers server-side.
    const route = (typeof window.__rtTripRoute === 'function')
      ? window.__rtTripRoute()
      : null;

    // CG zoom gating still tracked client-side for cgUnlocked + counts UI;
    // the server enforces its own gate at zoom < 6 anyway. Bypass the
    // gate when a route is active — campgrounds are exactly the kind of
    // POI the user wants to see along the corridor regardless of zoom.
    const wantCG = !!route || m.getZoom() >= CG_ZOOM_THRESHOLD || cgUnlocked;
    if (wantCG) cgUnlocked = true;
    // Defaults: just the point-geom layers. Park polygons (NP/SP) are
    // expensive to ship and clutter at low zoom — leave them out for now;
    // a follow-up will reintroduce them via a separate tile/render path.
    const cats = ['planet-fitness', 'supercharger'];
    if (wantCG) cats.push('campground');

    const currentBbox = [west, south, east, north];
    const catsKey = cats.slice().sort().join(',');

    // Cache short-circuit: only when no corridor (the route polyline is part
    // of the predicate, not just the bbox). The cached FC may carry features
    // outside the new viewport — that's fine, MapLibre filters by geometry
    // intersection at render time and the layer only paints what's visible.
    let fc = null;
    let fromCache = false;
    if (!route) {
      const cached = viewportCacheLookup(currentBbox, catsKey);
      if (cached) { fc = cached; fromCache = true; }
    }

    if (!fc) {
      if (inflight) inflight.abort();
      inflight = new AbortController();
      const poisBody = { bbox: currentBbox, zoom, categories: cats };
      if (route) poisBody.route = route;

      try {
        const poisRes = await fetch('/api/pois', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(poisBody),
          signal: inflight.signal,
        });
        if (!poisRes.ok) throw new Error(`/api/pois HTTP ${poisRes.status}`);
        fc = await poisRes.json();
      } catch (err) {
        if (err.name === 'AbortError') return;
        console.error('bbox fetch failed:', err);
        return;
      }
      inflight = null;
      // Only cache full responses. truncated:true means features beyond the
      // POI_LIMIT budget were dropped server-side, so a contained sub-view
      // would render fewer pins than a real fetch would return.
      if (!route && !fc.truncated) viewportCachePut(currentBbox, catsKey, fc);
    }

    const np = [], sp = [], pf = [], cg = [], sc = [];
    for (const raw of fc.features) {
      const f = flattenPoi(raw);
      const c = raw.properties?.category;
      if (c === 'national-park') np.push(f);
      else if (c === 'state-park') sp.push(f);
      else if (c === 'planet-fitness') pf.push(f);
      else if (c === 'campground') cg.push(f);
      else if (c === 'supercharger') sc.push(f);
    }
    setNPData({ type: 'FeatureCollection', features: np });
    setSPData({ type: 'FeatureCollection', features: sp });
    setPFData({ type: 'FeatureCollection', features: pf });
    setCGData({ type: 'FeatureCollection', features: cg });
    setSCData({ type: 'FeatureCollection', features: sc });

    // Trip-corridor card list reads from this hook on each refresh.
    if (typeof window.__rtSetTripPois === 'function') window.__rtSetTripPois(cg);
    state.overlayData.np = { type: 'FeatureCollection', features: np };
    state.overlayData.sp = { type: 'FeatureCollection', features: sp };
    state.overlayData.pf = { type: 'FeatureCollection', features: pf };
    state.overlayData.cg = { type: 'FeatureCollection', features: cg };
    state.overlayData.sc = { type: 'FeatureCollection', features: sc };

    setCount('c-np', np.length);
    setCount('c-sp', sp.length);
    setCount('c-pf', pf.length);
    setCount('c-open', sc.length);
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

    // Local pin search index: removed. The slim /api/pois response no
    // longer ships names, so a client-side text index would be empty.
    // Cross-viewport text search runs through the backend's
    // /api/pois/search endpoint via topbar.js — no duplicate index needed.
  }

  function scheduleBboxRefresh() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(refreshBbox, 250);
  }

  // Topbar calls this when the trip corridor changes (route added/removed/
  // reordered, or radius changed) so POIs re-filter without waiting for a pan.
  // Same debounce as moveend; identical refresh path.
  window.__rtRefreshBbox = scheduleBboxRefresh;

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
