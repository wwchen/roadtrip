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
  const detail = parseObject(p.detail) || {};
  const raw = parseObject(p.raw) || parseObject(detail.raw) || {};
  const detailProps = { ...detail };
  delete detailProps.type;
  delete detailProps.raw;
  const address = detailProps.address || p.address;
  const flat = p.category === 'campground'
    ? {
        id: f.id,
        ...p,
        ...detailProps,
        upstream: p.upstream || detailProps.upstream || raw.upstream || canonicalCampgroundUpstream(raw, address),
      }
    : { id: f.id, ...raw, ...p, ...detailProps };
  delete flat.detail;
  delete flat.raw;

  if (p.category === 'campground') {
    promoteCanonicalCampgroundFields(flat, p, raw);
  }

  // Address arrives as a nested object from /api/pois/{id} (the JSONB
  // column). Flatten its parts onto the top of properties for every
  // category that surfaces an address — popups read them directly.
  const addr = flat.address || {};
  const nestedAddr = addr.address && typeof addr.address === 'object' ? addr.address : {};
  flat.full_address = firstText(addr.full, nestedAddr.full, flat.full_address);
  flat.street = firstText(addr.street, addr.street1, addr.address_line, nestedAddr.street, nestedAddr.street1, nestedAddr.address_line);
  flat.city = firstText(addr.city, nestedAddr.city);
  flat.state = firstText(addr.state, addr.state_code, nestedAddr.state, nestedAddr.state_code, p.region);
  flat.country = firstText(p.country, addr.country, addr.country_code, nestedAddr.country, nestedAddr.country_code, flat.country);
  flat.postcode = firstText(addr.postcode, addr.postal_code, addr.zipcode, nestedAddr.postcode, nestedAddr.postal_code, nestedAddr.zipcode);

  // info_url is the BE's canonical "open this in upstream" link
  // (Tesla findus, planetfitness.com gym page, BC Parks page, …).
  // Popups read p.website / p.infoUrl — keep both names alive.
  flat.website = firstText(flat.info_url, p.info_url, p.website, flat.website);
  flat.infoUrl = firstText(flat.info_url, p.info_url);

  if (p.category === 'campground' && p.subcategory) {
    flat.category = p.subcategory;
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
  if (p.category === 'planet_fitness_location' || p.category === 'planet-fitness') {
    flat.opening_hours = raw.opening_hours || '';
  }
  if (p.category === 'tesla_supercharger' || p.category === 'supercharger') {
    const detailPayload = objectValue(raw.detail_payload, raw.detailPayload, flat.detail_payload, flat.detailPayload);
    const indexPayload = objectValue(raw.index_payload, raw.indexPayload, flat.index_payload, flat.indexPayload);
    const availabilityProfile = objectValue(
      raw.availability_profile,
      raw.availabilityProfile,
      flat.availability_profile,
      flat.availabilityProfile,
      detailPayload?.availabilityProfile,
    );
    const amenities = arrayValue(raw.amenities, flat.amenities, detailPayload?.amenities);
    const upstreamDetail = { ...(detailPayload || {}) };
    if (availabilityProfile) upstreamDetail.availabilityProfile = availabilityProfile;
    const timeZone = firstText(raw.time_zone, raw.timeZone, flat.time_zone, flat.timeZone, detailPayload?.timeZone);
    if (timeZone) upstreamDetail.timeZone = timeZone;
    if (amenities) upstreamDetail.amenities = amenities;
    const accessHours = objectValue(upstreamDetail.accessHours) || {};
    const twentyFourSeven = firstPresent(raw.twenty_four_seven, flat.twenty_four_seven, detailPayload?.accessHours?.twentyFourSeven);
    if (twentyFourSeven !== undefined) upstreamDetail.accessHours = { ...accessHours, twentyFourSeven };
    const openToNonTeslas = firstPresent(raw.open_to_non_teslas, flat.open_to_non_teslas, detailPayload?.openToNonTeslas);
    if (openToNonTeslas !== undefined) upstreamDetail.openToNonTeslas = openToNonTeslas;
    const trailerFriendly = firstPresent(raw.trailer_friendly, flat.trailer_friendly, detailPayload?.isTrailerFriendly);
    if (trailerFriendly !== undefined) upstreamDetail.isTrailerFriendly = trailerFriendly;

    flat.locationId = p.source_id || raw.location_slug || flat.location_slug;
    flat.stallCount = raw.stall_count ?? 0;
    flat.powerKilowatt = raw.max_power_kw ?? 0;
    flat.color = raw.color || '#e82127';
    flat.status = firstText(raw.status, raw.site_status, indexPayload?.supercharger_function?.site_status) || 'OPEN';
    flat.pricebooks = raw.pricebooks || [];
    flat.timeZone = timeZone;
    flat.availabilityProfile = availabilityProfile || null;
    flat.detailPayload = detailPayload || {};
    flat.upstream = {
      ...(objectValue(p.upstream, flat.upstream) || {}),
      ...(indexPayload ? { index: indexPayload } : {}),
      detail: upstreamDetail,
    };
  }
  flat.name = p.name || raw.name || flat.name;
  return { ...f, properties: flat };
}

function promoteCanonicalCampgroundFields(flat, p, raw) {
  const management = raw.management && typeof raw.management === 'object' ? raw.management : {};
  const contact = raw.contact && typeof raw.contact === 'object' ? raw.contact : {};
  const location = raw.location && typeof raw.location === 'object' ? raw.location : {};
  const metadata = raw.metadata && typeof raw.metadata === 'object' ? raw.metadata : {};

  flat.description = firstText(
    p.description,
    flat.description,
    raw.description,
  );
  flat.photo_url = firstText(p.photo_url, flat.photo_url, campgroundPhotoUrl(raw.photos));
  flat.agency = firstText(p.agency, flat.agency, management.agency_name, management.agency, management.name);
  flat.phone = firstText(p.phone, flat.phone, contact.primary_phone, contact.phone);
  flat.email = firstText(p.email, contact.email, contact.primary_email);
  flat.reserve_url = firstText(p.reserve_url, flat.reserve_url, raw.reservation_url);
  flat.status = firstText(p.status, flat.status, raw.status);
  flat.status_description = firstText(p.status_description, flat.status_description);
  flat.kind = firstText(p.kind, flat.kind, raw.kind);
  flat.price = p.price ?? raw.price ?? flat.price;
  flat.schedule = p.schedule ?? raw.default_campsite_schedule ?? flat.schedule;
  flat.default_campsite_schedule = flat.schedule;
  flat.amenities = p.amenities ?? raw.amenities ?? flat.amenities;
  flat.cell_coverage = p.cell_coverage ?? raw.cell_service ?? flat.cell_coverage;
  flat.last_verified = firstText(p.last_verified, metadata.last_updated, raw.updated_at);
  flat.max_rv_length = p.max_rv_length ?? raw.max_rv_length ?? flat.max_rv_length;
  flat.max_trailer_length = p.max_trailer_length ?? raw.max_trailer_length ?? flat.max_trailer_length;
  flat.has_pull_through_sites = p.has_pull_through_sites ?? raw.has_pull_through_sites ?? flat.has_pull_through_sites;
  flat.big_rig_friendly = p.big_rig_friendly ?? raw.big_rig_friendly ?? flat.big_rig_friendly;
  flat.elevation = p.elevation ?? location.elevation ?? flat.elevation;
  flat.management = p.management ?? raw.management ?? flat.management;
  flat.contact = p.contact ?? raw.contact ?? flat.contact;
  flat.links = p.links ?? raw.links ?? flat.links;
  flat.alerts = p.alerts ?? raw.alerts ?? flat.alerts;
  flat.connections = p.connections ?? raw.connections ?? flat.connections;
  flat.metadata = p.metadata ?? raw.metadata ?? flat.metadata;
}

function campgroundPhotoUrl(photos) {
  if (!Array.isArray(photos)) return '';
  for (const photo of photos) {
    if (!photo || typeof photo !== 'object') continue;
    const url = firstText(photo.large_url, photo.medium_url, photo.small_url, photo.original_url);
    if (url) return url;
  }
  return '';
}

function canonicalCampgroundUpstream(raw, address) {
  const location = raw.location && typeof raw.location === 'object' ? raw.location : {};
  const upstream = {};
  const directions = firstText(address?.directions, location.directions);
  const status = firstText(raw.status_description, raw.status);
  const price = priceLabel(raw.price);
  if (directions) upstream.FacilityDirections = directions;
  if (status) upstream.Status = status;
  if (price) upstream.Price = price;
  return Object.keys(upstream).length ? upstream : null;
}

function priceLabel(price) {
  if (!price || typeof price !== 'object') return '';
  const min = Number(price.minimum);
  const max = Number(price.maximum);
  const currency = firstText(price.currency_code, price.currency) || 'USD';
  if (!Number.isFinite(min) && !Number.isFinite(max)) return '';
  const format = (n) => `${currency} ${n.toFixed(Number.isInteger(n) ? 0 : 2)}`;
  if (Number.isFinite(min) && Number.isFinite(max) && min !== max) return `${format(min)}-${format(max)}`;
  return format(Number.isFinite(min) ? min : max);
}

function firstText(...values) {
  for (const value of values) {
    if (typeof value !== 'string') continue;
    const trimmed = value.trim();
    if (trimmed) return trimmed;
  }
  return '';
}

function firstPresent(...values) {
  for (const value of values) {
    if (value !== undefined && value !== null) return value;
  }
  return undefined;
}

function objectValue(...values) {
  for (const value of values) {
    if (value && typeof value === 'object' && !Array.isArray(value)) return value;
  }
  return null;
}

function parseObject(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value;
  if (typeof value !== 'string' || value.trim() === '') return null;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function arrayValue(...values) {
  for (const value of values) {
    if (Array.isArray(value)) return value;
  }
  return null;
}
