// Shared state + low-level helpers. Mutable singletons live on `state` so
// imports get live values (modules can reassign `state.foo` and other
// modules read the latest via `state.foo`, instead of needing setters).
export const state = {
  map: null,
  userLocation: null,
  activePopup: null,
  mapReady: false,
  overlayData: { sc: null, states: null, np: null, sp: null, pf: null, cg: null },
  bound: { sc: false, np: false, cg: false, pf: false },
};

// Only one popup at a time. MapLibre's closeOnClick fires on background-map
// clicks, but feature-layer clicks are consumed by the layer handler before
// the popup sees them — without this, tapping dot A then dot B leaves both
// popups open and overlapping.
export function openPopup({ lngLat, html, maxWidth = 'min(360px, calc(100vw - 24px))' }) {
  const { map } = state;
  if (state.activePopup) { state.activePopup.remove(); state.activePopup = null; }
  const popup = new maplibregl.Popup({
    closeButton: true,
    anchor: 'bottom',
    maxWidth,
    offset: 12,
  });
  popup.on('close', () => { if (state.activePopup === popup) state.activePopup = null; });
  popup.setLngLat(lngLat).setHTML(html).addTo(map);
  state.activePopup = popup;
  return popup;
}

export async function fetchJSON(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return r.json();
}

export function setCount(id, n) {
  const el = document.getElementById(id);
  if (el) el.textContent = '(' + n.toLocaleString() + ')';
}

// Haversine in km. Used for distance-from-me in popups + sort-by-nearest.
export function distanceKm(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const toRad = (d) => d * Math.PI / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 +
            Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
            Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

export function formatDistance(km) {
  if (km < 1) return Math.round(km * 1000) + ' m away';
  if (km < 10) return km.toFixed(1) + ' km away';
  return Math.round(km) + ' km away';
}

export function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
  })[c]);
}

// Rough centroid of any GeoJSON geometry via bbox midpoint — good enough for flyTo.
export function geomCenter(geom) {
  let minX=Infinity, minY=Infinity, maxX=-Infinity, maxY=-Infinity;
  const visit = (c) => {
    if (typeof c[0] === 'number') {
      if (c[0] < minX) minX = c[0];
      if (c[0] > maxX) maxX = c[0];
      if (c[1] < minY) minY = c[1];
      if (c[1] > maxY) maxY = c[1];
    } else for (const x of c) visit(x);
  };
  visit(geom.coordinates);
  return [(minX + maxX) / 2, (minY + maxY) / 2, [[minX, minY], [maxX, maxY]]];
}

export function zoomForBbox(bbox) {
  const [[w, s], [e, n]] = bbox;
  const span = Math.max(e - w, n - s);
  if (span > 3) return 7;
  if (span > 1) return 8.5;
  if (span > 0.3) return 10;
  if (span > 0.1) return 11;
  return 12;
}

// Trigger installOverlays for any layer whose data has arrived after style.load fired.
export function reinstallOverlays() {
  if (state.mapReady) state.map.fire('style.load');
}
