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

/**
 * Render one or more `Call ...` tertiary buttons from a phone field that
 * may be a single number, slash-delimited, or comma-delimited (e.g.
 * "530.336.5521/530.257.2151" → two buttons). US numbers (10 digits) are
 * formatted as (XXX) XXX-XXXX; others are echoed raw. tel: href strips
 * everything except digits and a leading +.
 */
export function callButtonsHTML(phoneRaw, btnClass = 'cg-btn cg-btn-tertiary') {
  if (!phoneRaw) return '';
  const numbers = String(phoneRaw).split(/[\/,;]/).map(s => s.trim()).filter(Boolean);
  return numbers.map(n => {
    const digits = n.replace(/[^\d+]/g, '');
    const display = formatPhone(n);
    const safe = escapeHtml(display);
    return `<a class="${btnClass}" href="tel:${escapeHtml(digits)}">Call ${safe}</a>`;
  }).join('');
}

/** US 10-digit numbers → "(XXX) XXX-XXXX"; everything else passes through. */
export function formatPhone(s) {
  const digits = String(s).replace(/\D/g, '');
  if (digits.length === 10) {
    return `(${digits.slice(0,3)}) ${digits.slice(3,6)}-${digits.slice(6)}`;
  }
  if (digits.length === 11 && digits.startsWith('1')) {
    return `(${digits.slice(1,4)}) ${digits.slice(4,7)}-${digits.slice(7)}`;
  }
  return s;
}

export function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
  })[c]);
}

// Rough centroid of any GeoJSON geometry via bbox midpoint — good enough for flyTo.
// Handles Point/LineString/Polygon/MultiPolygon/MultiLineString/MultiPoint via
// recursive coordinate descent, and GeometryCollection via its `geometries`
// array. PAD-US ships some parks as GeometryCollection (mixed polygon parts).
export function geomCenter(geom) {
  let minX=Infinity, minY=Infinity, maxX=-Infinity, maxY=-Infinity;
  const visit = (c) => {
    if (!Array.isArray(c) || c.length === 0) return;
    if (typeof c[0] === 'number') {
      if (c[0] < minX) minX = c[0];
      if (c[0] > maxX) maxX = c[0];
      if (c[1] < minY) minY = c[1];
      if (c[1] > maxY) maxY = c[1];
    } else for (const x of c) visit(x);
  };
  if (geom?.type === 'GeometryCollection') {
    for (const g of (geom.geometries || [])) visit(g.coordinates);
  } else {
    visit(geom?.coordinates);
  }
  if (!isFinite(minX)) return [0, 0, [[0, 0], [0, 0]]];
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

// Wide-shape flatten — runs after a /api/pois/{id} hydration. Promotes the
// rich nested structure into the flat property names the popups, drawer,
// and campground-card.js read directly. Idempotent.
//
// Lives in core.js (vs app.js) so popups.js can import it without
// creating a popups → app → layers → popups cycle. The slim path through
// the bbox endpoint uses flattenPoi (in app.js), which only does the
// campground category-promote.
export function flattenHydratedPoi(f) {
  const p = f.properties || {};
  const raw = p.raw || {};
  const flat = { id: f.id, ...raw, ...p };
  delete flat.raw;

  // Address arrives as a nested object from /api/pois/{id} (the JSONB
  // column). Flatten its parts onto the top of properties for every
  // category that surfaces an address — popups read them directly.
  const addr = p.address || {};
  flat.street = addr.street || '';
  flat.city = addr.city || '';
  flat.state = addr.state || p.region || '';
  flat.postcode = addr.postcode || '';

  // info_url is the BE's canonical "open this in upstream" link
  // (Tesla findus, planetfitness.com gym page, BC Parks page, …).
  // Popups read p.website / p.infoUrl — keep both names alive.
  flat.website = p.info_url || p.website || '';
  flat.infoUrl = p.info_url || '';

  if (p.category === 'campground' && (p.subcategory || raw.subcategory)) {
    flat.category = p.subcategory || raw.subcategory;
  }
  // Promote provider-ref discriminants to flat keys the drawer + campground
  // card already read (recgov_id for the rec.gov heat-strip path; aspira for
  // the NextGen path). Backend ships provider_ref as one nested object;
  // legacy code shape stays. host comes from raw.upstream.host (the join-by
  // -name ETL stuffs it there) so the drawer can hit the right Aspira tenant.
  const pref = p.provider_ref;
  if (pref && typeof pref === 'object') {
    if (pref.recgov_id && !flat.recgov_id) flat.recgov_id = pref.recgov_id;
    if (pref.transactionLocationId != null && !flat.aspira) {
      flat.aspira = {
        transactionLocationId: pref.transactionLocationId,
        mapId: pref.mapId,
        resourceLocationId: pref.resourceLocationId ?? null,
        host: raw?.upstream?.host || null,
      };
    }
  }
  if (p.category === 'national-park' || p.category === 'state-park') {
    // Park layers + popups read Unit_Nm / Loc_Nm / State_Nm / GIS_Acres /
    // Mang_Name — the field names PAD-US used. The new ETL stores the
    // facts under different keys (acres, official_name, designation,
    // region, source); map them here so the rendering code stays put.
    flat.Unit_Nm = raw.Unit_Nm || p.unit_name || p.name;
    flat.State_Nm = raw.State_Nm || p.region || '';
    flat.Loc_Nm = raw.Loc_Nm || raw.official_name || '';
    flat.GIS_Acres = raw.GIS_Acres ?? raw.acres ?? null;
    flat.Mang_Name = raw.Mang_Name || raw.designation || '';
  }
  if (p.category === 'planet-fitness') {
    flat.opening_hours = raw.opening_hours || '';
  }
  if (p.category === 'supercharger') {
    flat.locationId = p.source_id;
    flat.stallCount = raw.stall_count ?? 0;
    flat.powerKilowatt = raw.max_power_kw ?? 0;
    flat.color = raw.color || '#e82127';
    flat.status = raw.status || 'OPEN';
    flat.pricebooks = raw.pricebooks || [];
  }
  flat.name = p.name || raw.name || flat.name;
  return { ...f, properties: flat };
}
