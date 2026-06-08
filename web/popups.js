import { state, distanceKm, formatDistance, escapeHtml, callButtonsHTML } from './core.js';
import { openCampgroundDrawer, openDrawer } from './drawer.js';

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
  openDrawer(`
    ${drawerHeader(name, sub)}
    ${primaryBtn ? `<div class="cg-actions">${primaryBtn}</div>` : ''}
    ${pills ? `<div class="pills">${pills}</div>` : ''}
  `);
}

export function openPlanetFitnessPopup(f) {
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;
  const addr = [p.street, p.city, p.state, p.postcode].filter(Boolean).join(', ');
  const website = p.website || 'https://www.planetfitness.com/gyms?q=' + encodeURIComponent([p.city, p.state].filter(Boolean).join(' '));
  const sub = buildSubline([addr, distanceTo(lng, lat)]);
  const callBtns = callButtonsHTML(p.phone);
  const hours = p.opening_hours
    ? `<div class="pills"><span class="pill">${escapeHtml(p.opening_hours)}</span></div>`
    : '';
  openDrawer(`
    ${drawerHeader(p.name || 'Planet Fitness', sub)}
    <div class="cg-actions">
      <a class="cg-btn cg-btn-primary" href="${website}" target="_blank" rel="noreferrer">Open in Planet Fitness</a>
      ${callBtns}
    </div>
    ${hours}
  `);
}

export function openSuperchargerPopup(f) {
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;
  const addr = [p.street, p.city, p.state].filter(Boolean).join(', ');
  const sub = buildSubline([addr, distanceTo(lng, lat)]);
  const stalls = [p.v2 && `V2×${p.v2}`, p.v3 && `V3×${p.v3}`, p.v4 && `V4×${p.v4}`].filter(Boolean).join(' · ');
  const plugs = [p.nacs && `NACS×${p.nacs}`, p.tpc && `TPC×${p.tpc}`].filter(Boolean).join(' · ');
  // tesla.com/findus deeplink. The `bounds` param (north,east,south,west)
  // is what makes findus actually zoom in on the site — without it the map
  // opens at a default zoom and the user has to hunt. We mirror the bbox
  // size Tesla's own UI generates: ±0.44° lat × ±1.86° lng around the site.
  // Keep rel="noopener" (security) but drop noreferrer — refererless requests
  // trip Akamai bot detection more often.
  const teslaBounds = p.locationId
    ? `${(lat + 0.44).toFixed(6)},${(lng + 1.86).toFixed(6)},${(lat - 0.44).toFixed(6)},${(lng - 1.86).toFixed(6)}`
    : '';
  const teslaUrl = p.locationId
    ? `https://www.tesla.com/findus?bounds=${encodeURIComponent(teslaBounds)}&location=${encodeURIComponent(p.locationId)}&functionType=supercharger`
    : '';
  const primaryBtn = teslaUrl
    ? `<a class="cg-btn cg-btn-primary" href="${teslaUrl}" target="_blank" rel="noopener">Open in Tesla</a>`
    : `<span class="cg-btn cg-btn-disabled">No Tesla link available</span>`;
  const tags = [
    `<span class="tag" style="background:${p.color};color:#fff">${escapeHtml(p.status || '')}</span>`,
    p.stallCount ? `<span class="tag">${p.stallCount} stalls</span>` : '',
    p.powerKilowatt ? `<span class="tag">${p.powerKilowatt} kW</span>` : '',
  ].filter(Boolean).join(' ');
  // Pricing now arrives inline on the feature properties (RFC 0007 — same
  // tesla-locations capture that gives us name/stalls/kW). No second
  // round-trip; render synchronously.
  const pricingHtml = renderPricing(p.pricebooks);
  openDrawer(`
    ${drawerHeader(p.name || '', sub)}
    ${p.facility ? `<div class="cg-sub">at ${escapeHtml(p.facility)}</div>` : ''}
    <div class="cg-actions">${primaryBtn}</div>
    <div style="margin-top:6px">${tags}</div>
    ${stalls ? `<div class="pills"><span class="pill">${escapeHtml(stalls)}</span></div>` : ''}
    ${plugs ? `<div class="pills"><span class="pill">${escapeHtml(plugs)}</span></div>` : ''}
    ${p.dateOpened ? `<div class="footer">Opened ${escapeHtml(p.dateOpened)}</div>` : ''}
    <div class="pricing" style="margin-top:8px; padding-top:6px; border-top:1px solid #eee;">
      ${pricingHtml}
    </div>
  `);
}
