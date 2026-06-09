import { state, distanceKm, formatDistance, escapeHtml, callButtonsHTML } from './core.js';
import { openCampgroundDrawer, openDrawer, upstreamHTML, reviveJsonProp } from './drawer.js';

/** Compose subline from arbitrary parts: "Loop · State · 2.4 km away". */
function buildSubline(parts) {
  return parts.filter(Boolean).join(' · ');
}

/** Distance string from user to lng/lat, or '' when location is off. */
function distanceTo(lng, lat) {
  return state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lat, lng))
    : '';
}

/**
 * Per-POI Directions button. Same visual slot in every drawer; the click is
 * picked up by a delegated listener in drawer.js that routes through
 * window.__rtAddTripStop. Label flips based on current trip mode so the
 * button reads "Add stop" once a route is being built.
 */
export function directionsButtonHTML({ name, lng, lat, kind = 'PLACE' }) {
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return '';
  const mode = (typeof window !== 'undefined' && typeof window.__rtTripMode === 'function')
    ? window.__rtTripMode() : 'browse';
  const label = mode === 'directions' ? 'Add stop' : 'Directions';
  const icon = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-3px;margin-right:6px"><path d="M21 10l-7 7-3-3-9 9"/><path d="M14 10h7v7"/></svg>`;
  return `<button type="button" class="cg-btn cg-btn-secondary rt-poi-directions"
    data-name="${escapeHtml(name || '')}"
    data-lng="${lng}"
    data-lat="${lat}"
    data-kind="${escapeHtml(kind)}">${icon}${label}</button>`;
}

/** Drawer header: name + subline + optional verdict. cg-* classes match drawer.js. */
function drawerHeader(name, sub, verdictHtml = '') {
  return `
    <header class="cg-drawer-head">
      <h2>${escapeHtml(name)}</h2>
      ${sub ? `<div class="cg-sub">${escapeHtml(sub)}</div>` : ''}
      ${verdictHtml ? `<div class="cg-verdict-row">${verdictHtml}</div>` : ''}
    </header>`;
}

function renderPricing(books) {
  // MapLibre serializes nested feature properties to JSON strings when
  // they live on its source; the pricebooks list comes back stringified
  // by the time we read it from a click event. Parse on the way in.
  if (typeof books === 'string') {
    try { books = JSON.parse(books); } catch { books = []; }
  }
  if (!books || !books.length) return '<div class="meta">No pricing on file.</div>';

  const tslaCharging = books.filter(b => b.feeType === 'CHARGING' && b.vehicleMakeType === 'TSLA');
  const congestion = books.filter(b => b.feeType === 'CONGESTION');

  const rows = [];
  rows.push(renderRateGroup('Tesla', tslaCharging));
  if (congestion.length) {
    const c = congestion[0];
    rows.push(`<div class="rate-row"><span class="rate-label">Idle/congestion</span><span class="rate-val">${formatRate(c)}</span></div>`);
  }
  return '<div class="rates">' + rows.join('') + '</div>';
}

// Most currencies render fine with Intl.NumberFormat's default symbol. For
// USD/CAD we strip the locale-prefix ("US$", "CA$", "$") and keep just "$"
// with a currency-code tag trailing the first Tesla row — cleaner for a popup.
function formatRate(b) {
  const cc = b.currencyCode || 'USD';
  const amount = new Intl.NumberFormat('en-US', { style: 'currency', currency: cc, currencyDisplay: 'narrowSymbol' }).format(b.rateBase);
  return `${amount}/${b.uom}`;
}

function renderRateGroup(label, books) {
  if (!books.length) return '';
  const flat = books.find(b => !b.isTou);
  const tou = books.filter(b => b.isTou).sort((a, b) => (a.startTime || '').localeCompare(b.startTime || ''));
  const cc = books[0].currencyCode;
  const ccTag = cc && cc !== 'USD' ? ` <span class="meta" style="font-size:10px">${cc}</span>` : '';
  const lines = [];
  lines.push(`<div class="rate-row rate-header"><span class="rate-label">${label}${ccTag}</span>${flat ? `<span class="rate-val">${formatRate(flat)}</span>` : ''}</div>`);
  for (const b of tou) {
    const win = `${b.startTime}–${b.endTime === '00:00' ? '24:00' : b.endTime}`;
    lines.push(`<div class="rate-row rate-tou"><span class="rate-label">&nbsp;&nbsp;${win}</span><span class="rate-val">${formatRate(b)}</span></div>`);
  }
  return lines.join('');
}

// Campground click → drawer (RFC 0003). Drawer renders availability for
// recgov pins and skips it for everything else. Re-exported under the old
// name so app.js doesn't need to change.
export function openCampgroundPopup(f) {
  openCampgroundDrawer(f);
}

// Build a reasonable external link for a park by name.
function externalParkLink(kind, name, stateName) {
  if (kind === 'np') {
    // NPS doesn't expose a deterministic slug, so a search URL is reliable.
    const q = encodeURIComponent(name);
    return [`https://www.nps.gov/findapark/advanced-search.htm?q=${q}`, 'nps.gov'];
  }
  // State Parks — no unified URL; do a Google search scoped to name + state
  const q = encodeURIComponent(`${name} ${stateName} state park`);
  return [`https://www.google.com/search?q=${q}`, 'search'];
}

export function openParkPopup(kind, feature, lngLat) {
  const p = feature.properties;
  reviveJsonProp(p, 'upstream');
  const name = p.Unit_Nm || p.Loc_Nm || 'Park';
  const stateName = p.State_Nm || '';
  const acres = p.GIS_Acres ? Number(p.GIS_Acres).toLocaleString() + ' acres' : '';
  const mgr = p.Mang_Name || '';
  const [url, label] = externalParkLink(kind, name, stateName);
  const sub = buildSubline([
    kind === 'np' ? 'National Park' : 'State Park',
    stateName,
    distanceTo(lngLat.lng, lngLat.lat),
  ]);
  const pills = [
    mgr && mgr !== 'National Park Service' ? `<span class="pill">${escapeHtml(mgr)}</span>` : '',
    acres ? `<span class="pill">${escapeHtml(acres)}</span>` : '',
  ].filter(Boolean).join('');
  const primaryBtn = url
    ? `<a class="cg-btn cg-btn-primary" href="${url}" target="_blank" rel="noreferrer">${escapeHtml(label === 'nps.gov' ? 'Open on nps.gov' : 'Search ' + label)}</a>`
    : '';
  const dirBtn = directionsButtonHTML({ name, lng: lngLat.lng, lat: lngLat.lat, kind: kind === 'np' ? 'NP' : 'SP' });
  openDrawer(`
    ${drawerHeader(name, sub)}
    <div class="cg-actions">${dirBtn}${primaryBtn}</div>
    ${pills ? `<div class="pills">${pills}</div>` : ''}
    ${upstreamHTML(p.upstream)}
  `);
}

export function openPlanetFitnessPopup(f) {
  const p = f.properties;
  reviveJsonProp(p, 'upstream');
  const [lng, lat] = f.geometry.coordinates;
  // Reading order matches the SC drawer: street, then city, then state+zip
  // as a single token. Empty pieces drop out so a missing zip doesn't
  // leave a stray ", ".
  const addrLine = [p.street, p.city, [p.state, p.postcode].filter(Boolean).join(' ')]
    .filter(Boolean).join(', ');
  const sub = buildSubline([addrLine, distanceTo(lng, lat)]);
  // Primary CTA is Google Maps — same coords-query trick as superchargers,
  // works on iOS/Android/web and routes from the user's current location.
  const gmapsLabel = encodeURIComponent('Planet Fitness');
  const gmapsUrl = `https://www.google.com/maps/search/?api=1&query=${gmapsLabel}%20${lat},${lng}`;
  // Secondary: the OSM website tag if upstream had one; otherwise the
  // planetfitness.com search by city/state.
  const pfSearch =
    'https://www.planetfitness.com/gyms?q=' + encodeURIComponent([p.city, p.state].filter(Boolean).join(' '));
  const pfUrl = p.website || pfSearch;
  const callBtns = callButtonsHTML(p.phone);

  // Pills row: 24/7 / hours / wheelchair access. Each is dropped if the
  // upstream OSM tag is absent — sparse data should render sparse, not
  // with empty placeholder pills.
  const pills = [
    p.opening_hours ? `<span class="pill">${escapeHtml(p.opening_hours)}</span>` : '',
  ].filter(Boolean).join(' ');

  const dirBtn = directionsButtonHTML({ name: p.name || 'Planet Fitness', lng, lat, kind: 'PF' });
  openDrawer(`
    ${drawerHeader(p.name || 'Planet Fitness', sub)}
    <div class="cg-actions">
      ${dirBtn}
      <a class="cg-btn cg-btn-primary" href="${gmapsUrl}" target="_blank" rel="noopener">Open in Google Maps</a>
      <a class="cg-btn cg-btn-secondary" href="${pfUrl}" target="_blank" rel="noreferrer">Planet Fitness page</a>
      ${callBtns}
    </div>
    ${pills ? `<div class="pills">${pills}</div>` : ''}
    ${upstreamHTML(p.upstream)}
  `);
}

export function openSuperchargerPopup(f) {
  const p = f.properties;
  reviveJsonProp(p, 'upstream');
  const [lng, lat] = f.geometry.coordinates;
  // The Tesla detail capture is verbatim under properties.upstream.detail
  // (set in TeslaIndexEtl.transformRow). Read promoted fields from there
  // so we don't need a backend schema change for each new pill.
  const detail = (p.upstream && p.upstream.detail) || {};

  // Build the address line from the new tesla-locations enrichment.
  // street + city + state + postcode is the natural reading order; drop
  // any blank pieces so a missing postcode doesn't leave a stray comma.
  const addrLine = [p.street, p.city, [p.state, p.postcode].filter(Boolean).join(' ')]
    .filter(Boolean).join(', ');
  // commonSiteName is Tesla's "where in the parking lot" label
  // ("East Victoria Park - Lot 335"), often more useful than the
  // city-level name for navigation. Render under the address.
  const commonSite = detail.commonSiteName && detail.commonSiteName !== p.name
    ? detail.commonSiteName : '';
  const sub = buildSubline([addrLine, distanceTo(lng, lat)]);

  // Primary CTA: Google Maps. Use a coords + label query so the dropped
  // pin is right at the supercharger — Google routes from the user's
  // current location and works whether they're on iOS, Android, or web.
  const gmapsLabel = encodeURIComponent(p.name || 'Supercharger');
  const gmapsUrl = `https://www.google.com/maps/search/?api=1&query=${lat},${lng}&query_place_id=&query=${gmapsLabel}%20${lat},${lng}`;
  const primaryBtn = `<a class="cg-btn cg-btn-primary" href="${gmapsUrl}" target="_blank" rel="noopener">Open in Google Maps</a>`;

  // One spec-pills row covers everything about the site itself:
  // stall count, charger power, hardware mix (V2/V3/V4), connector
  // mix (NACS/TPC), 24/7, Magic Dock, Trailer-friendly. All conditional
  // — sparse sites stay sparse rather than rendering empty placeholders.
  const specPills = [
    p.stallCount ? `${p.stallCount} stalls` : '',
    p.powerKilowatt ? `${p.powerKilowatt} kW` : '',
    p.v2 && `V2×${p.v2}`,
    p.v3 && `V3×${p.v3}`,
    p.v4 && `V4×${p.v4}`,
    p.nacs && `NACS×${p.nacs}`,
    p.tpc && `TPC×${p.tpc}`,
  ].filter(Boolean).map(label => scPill(label)).join('');
  const featurePills = buildSCFeaturePills(detail);

  // Amenities is its own row so the pill set is scannable as "what's
  // here" (cafe, restrooms, restaurant, …) without mixing in the
  // hardware/capability flags above.
  const amenityPills = buildSCAmenityPills(detail);

  // Pricing now arrives inline on the feature properties (RFC 0007 — same
  // tesla-locations capture that gives us name/stalls/kW). No second
  // round-trip; render synchronously.
  const pricingHtml = renderPricing(p.pricebooks);

  // Busy-times sparkline. Tesla ships a 7×24 fractional-occupancy histogram
  // in availabilityProfile.availabilityProfile.{day}.congestionValue.
  // We render today's day-of-week as a 24-bar mini chart with a peak callout.
  const busyHtml = renderBusyHours(detail.availabilityProfile, detail.timeZone);

  const dirBtn = directionsButtonHTML({ name: p.name || 'Supercharger', lng, lat, kind: 'SC' });
  openDrawer(`
    ${drawerHeader(p.name || '', sub)}
    ${commonSite ? `<div class="meta" style="margin-top:-4px">${escapeHtml(commonSite)}</div>` : ''}
    <div class="cg-actions">${dirBtn}${primaryBtn}</div>
    ${(specPills || featurePills) ? `<div class="pills" style="margin-top:6px">${specPills}${featurePills}</div>` : ''}
    ${amenityPills ? `
      <div class="sc-row" style="margin-top:8px;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
        <span class="meta" style="font-weight:600;text-transform:uppercase;font-size:10px;letter-spacing:0.06em">Amenities</span>
        <span class="pills" style="display:inline-flex;flex-wrap:wrap;gap:4px">${amenityPills}</span>
      </div>` : ''}
    ${busyHtml}
    ${p.dateOpened ? `<div class="footer">Opened ${escapeHtml(p.dateOpened)}</div>` : ''}
    <div class="pricing" style="margin-top:8px; padding-top:6px; border-top:1px solid #eee;">
      ${pricingHtml}
    </div>
    ${upstreamHTML(p.upstream)}
  `);
}

// --- Tesla detail surfacing helpers -------------------------------------

function buildSCFeaturePills(detail) {
  const pills = [];
  if (detail?.accessHours?.twentyFourSeven) pills.push(scPill('24/7'));
  if (detail?.openToNonTeslas) pills.push(scPill('Magic Dock', 'NACS adapter built-in — works for non-Tesla EVs'));
  if (detail?.isTrailerFriendly) pills.push(scPill('Trailer-friendly', 'Pull-through stalls'));
  return pills.join('');
}

// amenities is sometimes null, sometimes an array of Tesla-flavoured
// SCREAMING_SNAKE strings: AMENITIES_RESTROOMS, AMENITIES_CAFE,
// AMENITIES_TWENTY_FOUR_HOUR, etc. Strip the AMENITIES_ prefix and
// title-case the rest. Capped at 8 so a long list doesn't blow up the
// drawer height.
// Special-case overrides for amenity strings that don't title-case cleanly.
// Add new entries here when Tesla ships a clunky one. TWENTY_FOUR_HOUR is
// dropped entirely because the 24/7 capability pill already covers it.
const AMENITY_OVERRIDES = {
  AMENITIES_WIFI: 'Wi-Fi',
  AMENITIES_RESTROOMS: 'Restrooms',
  AMENITIES_CAFE: 'Café',
  AMENITIES_RESTAURANT: 'Restaurant',
  AMENITIES_SHOPPING: 'Shopping',
  AMENITIES_LODGING: 'Lodging',
  AMENITIES_TWENTY_FOUR_HOUR: null, // skip — duplicates the 24/7 capability pill
};

function buildSCAmenityPills(detail) {
  const am = Array.isArray(detail?.amenities) ? detail.amenities : null;
  if (!am || !am.length) return '';
  const labels = am
    .map(a => prettifyAmenity(a))
    .filter(Boolean);
  return labels.slice(0, 8).map(l => scPill(l)).join('');
}

function prettifyAmenity(raw) {
  const key = String(raw).toUpperCase();
  if (Object.prototype.hasOwnProperty.call(AMENITY_OVERRIDES, key)) {
    // Override returns null when we want to skip the amenity entirely.
    return AMENITY_OVERRIDES[key];
  }
  // Fallback: strip AMENITIES_ prefix, title-case underscore-separated words.
  // "AMENITIES_FOO_BAR" → "Foo Bar"; legacy lowercase "wifi" → "Wifi".
  const stripped = String(raw).replace(/^AMENITIES_/i, '');
  return stripped
    .toLowerCase()
    .split('_')
    .map(w => w ? w[0].toUpperCase() + w.slice(1) : '')
    .join(' ')
    .trim();
}

function scPill(label, title = '') {
  const t = title ? ` title="${escapeHtml(title)}"` : '';
  return `<span class="pill"${t}>${escapeHtml(label)}</span>`;
}

// 24h busy-times sparkline from Tesla's availabilityProfile. Uses today's
// day-of-week (in the site's local timezone when known, otherwise the
// browser's). Each bar is a fraction of the day's peak so the visual is
// useful even at quiet sites; callout shows the peak hour.
function renderBusyHours(ap, siteTz) {
  if (!ap || typeof ap !== 'object') return '';
  // Tesla nests once: availabilityProfile.availabilityProfile.{day}.
  const days = ap.availabilityProfile || {};
  const dayKeys = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  // Local-day-of-week at the site, falling back to browser TZ.
  let dow;
  try {
    const fmt = new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: siteTz || undefined });
    dow = fmt.format(new Date()).toLowerCase();
  } catch (_) {
    dow = dayKeys[new Date().getDay()];
  }
  const today = days[dow]?.congestionValue;
  if (!Array.isArray(today) || today.length !== 24) return '';

  const peak = Math.max(...today);
  if (peak <= 0) return '';
  const peakHour = today.indexOf(peak);
  const peakLabel = formatHourLabel(peakHour);

  // Current hour at the site's local timezone (browser TZ as fallback) so
  // the highlight tracks "now" wherever the user is reading from.
  let nowHour;
  try {
    const fmt = new Intl.DateTimeFormat('en-US', { hour: 'numeric', hour12: false, timeZone: siteTz || undefined });
    nowHour = parseInt(fmt.format(new Date()), 10);
  } catch (_) {
    nowHour = new Date().getHours();
  }

  // Visual scale: every bar is height = 4–28px proportional to value/peak.
  // A bar even at value=0 shows 4px so the floor is visible. The current
  // hour gets a red outline so the user can read "right now" against the
  // day's profile at a glance.
  const bars = today.map((v, h) => {
    const ratio = v / peak;
    const height = 4 + Math.round(ratio * 24);
    const color = ratio >= 0.85 ? 'var(--cg-warn, #c0392b)'
      : ratio >= 0.5 ? 'var(--cg-accent, #2e7d32)'
      : 'var(--cg-muted, #888)';
    const tip = `${formatHourLabel(h)} · ${Math.round(v * 100)}% busy`;
    const outline = h === nowHour
      ? 'outline:2px solid var(--cg-warn, #c0392b);outline-offset:1px;'
      : '';
    return `<span class="sc-bar" title="${escapeHtml(tip)}" style="height:${height}px;background:${color};${outline}"></span>`;
  }).join('');

  // X-axis ticks at 6/12/18 for orientation without cluttering. Inline
  // styles keep this self-contained — no edit to index.html.
  return `
    <div class="sc-busy" style="margin-top:10px">
      <div style="display:flex;justify-content:space-between;align-items:baseline;margin-bottom:4px">
        <span class="meta" style="font-weight:600">Today's busy hours</span>
        <span class="meta">peak ${escapeHtml(peakLabel)}</span>
      </div>
      <div class="sc-bars" style="display:flex;align-items:flex-end;gap:1.5px;height:28px">
        ${bars}
      </div>
      <div class="meta" style="display:flex;justify-content:space-between;font-size:10px;margin-top:2px">
        <span>12a</span><span>6a</span><span>12p</span><span>6p</span><span>12a</span>
      </div>
    </div>
    <style>
      .sc-bar { flex: 1 1 0; border-radius: 1px; min-width: 0; }
    </style>`;
}

function formatHourLabel(h) {
  if (h === 0) return '12a';
  if (h < 12) return `${h}a`;
  if (h === 12) return '12p';
  return `${h - 12}p`;
}
