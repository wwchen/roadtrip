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

map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
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

async function load() {
  const status = document.getElementById('status');

  // Superchargers — local feed built from Tesla's official get-locations +
  // get-charger-details (see scripts/fetch_tesla_superchargers.py). Falls back
  // to supercharge.info if the local file is missing/empty (smoke-test stage
  // or first deploy before the bulk fetch has run).
  (async () => {
    try {
      status.textContent = 'Loading Superchargers…';
      let fc = null;
      try {
        const local = await fetchJSON('/data/tesla-superchargers.geojson');
        if (local?.features?.length > 100) fc = local;
      } catch (_) { /* fall through */ }

      if (!fc) {
        // Supercharge.info legacy path. Schema differs (raw site objects, not
        // pre-projected GeoJSON), so we run the existing toGeoJSON projection.
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

  // State boundary lines
  (async () => {
    try {
      state.overlayData.states = await fetchJSON('/data/us-states.geojson');
      reinstallOverlays();
    } catch (err) {
      console.error('State lines load failed:', err);
    }
  })();

  // Park polygons (National + State)
  (async () => {
    try {
      const [np, sp] = await Promise.all([
        fetchJSON('/data/national-parks.geojson'),
        fetchJSON('/data/state-parks.geojson'),
      ]);
      setCount('c-np', np.features.length);
      setCount('c-sp', sp.features.length);
      state.overlayData.np = np;
      state.overlayData.sp = sp;
      for (const f of np.features) {
        const [lng, lat, bbox] = geomCenter(f.geometry);
        registerSearchItems([{
          name: f.properties.Unit_Nm || f.properties.Loc_Nm || 'Park',
          sub: f.properties.State_Nm || '',
          kind: 'NP', color: '#2e7d32',
          lng, lat, zoom: zoomForBbox(bbox),
          onSelect: () => synthesizeClick(['np-pts-hit', 'np-fill'], [lng, lat]),
        }]);
      }
      for (const f of sp.features) {
        const [lng, lat, bbox] = geomCenter(f.geometry);
        registerSearchItems([{
          name: f.properties.Unit_Nm || f.properties.Loc_Nm || 'Park',
          sub: f.properties.State_Nm || '',
          kind: 'SP', color: '#8d6e63',
          lng, lat, zoom: zoomForBbox(bbox),
          onSelect: () => synthesizeClick(['sp-pts-hit', 'sp-fill'], [lng, lat]),
        }]);
      }
      reinstallOverlays();
    } catch (err) {
      console.error('Park load failed:', err);
    }
  })();

  // Planet Fitness
  (async () => {
    try {
      const pf = await fetchJSON('/data/planet-fitness.geojson');
      setCount('c-pf', pf.features.length);
      state.overlayData.pf = pf;
      for (const f of pf.features) {
        const [lng, lat] = f.geometry.coordinates;
        const p = f.properties;
        registerSearchItems([{
          name: p.name || 'Planet Fitness',
          sub: [p.city, p.state].filter(Boolean).join(', '),
          kind: 'PF', color: '#7b4bb5',
          lng, lat, zoom: 14,
          onSelect: () => synthesizeClick(['pf-points-hit', 'pf-points'], [lng, lat]),
        }]);
      }
      reinstallOverlays();
    } catch (err) {
      console.error('PF load failed:', err);
    }
  })();

  // Campgrounds (deferred: ~12k features, 5.9MB raw). Loaded only once the
  // user zooms in past z6 — at continental view, 11k dots are noise anyway,
  // and fetching + parsing on first paint makes initial load feel slow.
  // Triggered by (a) zoom crossing the threshold, (b) search-box typing
  // (campgrounds need to be indexed to appear in results).
  const CG_ZOOM_THRESHOLD = 6;
  let cgLoadStarted = false;
  async function loadCampgrounds() {
    if (cgLoadStarted) return;
    cgLoadStarted = true;
    try {
      const cg = await fetchJSON('/data/campgrounds.geojson');
      const counts = { federal: 0, state: 0, local: 0, provincial: 0, other: 0 };
      cg.features.forEach(f => {
        const cat = f.properties.category;
        counts[cat] = (counts[cat] || 0) + 1;
      });
      for (const [k, v] of Object.entries(counts)) setCount('c-cg-' + k, v);
      const hint = document.getElementById('cg-load-hint');
      if (hint) hint.remove();
      state.overlayData.cg = cg;
      for (const f of cg.features) {
        const [lng, lat] = f.geometry.coordinates;
        const p = f.properties;
        registerSearchItems([{
          name: p.name,
          sub: [p.typeLabel, p.state].filter(Boolean).join(' · '),
          kind: 'CG', color: '#2e7d32',
          cgCategory: p.category,
          lng, lat, zoom: 13,
          onSelect: () => synthesizeClick(['cg-points-hit', 'cg-points'], [lng, lat]),
        }]);
      }
      reinstallOverlays();
    } catch (err) {
      cgLoadStarted = false;  // allow retry on next trigger
      console.error('Campground load failed:', err);
    }
  }
  // Trigger 1: user zooms past the threshold
  const maybeLoadOnZoom = () => {
    if (map.getZoom() >= CG_ZOOM_THRESHOLD) {
      loadCampgrounds();
      map.off('zoomend', maybeLoadOnZoom);
    }
  };
  map.on('zoomend', maybeLoadOnZoom);
  maybeLoadOnZoom();  // if the user starts zoomed-in (saved URL/state)
  // Trigger 2: user types in the search box (campgrounds need to be searchable)
  document.getElementById('search').addEventListener('focus', loadCampgrounds, { once: true });
}

load();
