import { state, openPopup, distanceKm, formatDistance, escapeHtml } from './core.js';
import {
  parseAmenities,
  parseCellCoverage,
  parseRatingReviews,
  amenitiesPillsHTML,
  cellCoveragePillsHTML,
  ratingHTML,
  sitesTagHTML,
  lastVerifiedFooterHTML,
} from './campground-card.js';
import { openCampgroundDrawer } from './drawer.js';

/** True when ?drawer=1 is in the URL. RFC 0003 ships behind this flag. */
function drawerFlagOn() {
  try { return new URLSearchParams(location.search).get('drawer') === '1'; } catch { return false; }
}

async function loadPricing(slug, elId) {
  const el = document.getElementById(elId);
  if (!el) return;
  try {
    const r = await fetch('/api/pricing/' + encodeURIComponent(slug));
    const j = await r.json();
    if (!r.ok) {
      // 404 means the refresh worker hasn't crawled this site yet. Pricing
      // is no longer fetched live — see scripts/fetch_tesla_superchargers.py.
      const msg = r.status === 404
        ? 'Pricing not yet cached.'
        : `Pricing unavailable (HTTP ${r.status}).`;
      el.innerHTML = `<div class="meta">${msg}</div>`;
      return;
    }
    el.innerHTML = renderPricing(j);
  } catch (err) {
    el.innerHTML = `<div class="meta">Pricing fetch failed: ${err.message}</div>`;
  }
}

function renderPricing(resp) {
  const d = resp?.data?.data;
  if (!d) return '<div class="meta">No data.</div>';
  const books = d.effectivePricebooks || [];
  if (!books.length) return '<div class="meta">No pricing on file.</div>';

  const tslaCharging = books.filter(b => b.feeType === 'CHARGING' && b.vehicleMakeType === 'TSLA');
  const ntslaCharging = books.filter(b => b.feeType === 'CHARGING' && b.vehicleMakeType === 'NTSLA');
  const congestion = books.filter(b => b.feeType === 'CONGESTION');

  const rows = [];
  rows.push(renderRateGroup('Tesla', tslaCharging));
  if (ntslaCharging.length) rows.push(renderRateGroup('Non-Tesla', ntslaCharging));
  if (congestion.length) {
    const c = congestion[0];
    rows.push(`<div class="rate-row"><span class="rate-label">Idle/congestion</span><span class="rate-val">${formatRate(c)}</span></div>`);
  }
  const cache = resp._cache;
  const cacheNote = cache ? `<div class="meta" style="margin-top:4px; font-size:10px;">${cache.hit ? 'cached ' + formatAge(cache.age_seconds) + ' ago' : 'just fetched'}</div>` : '';
  return '<div class="rates">' + rows.join('') + '</div>' + cacheNote;
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

function formatAge(s) {
  if (s < 60) return s + 's';
  if (s < 3600) return Math.round(s/60) + 'm';
  if (s < 86400) return Math.round(s/3600) + 'h';
  return Math.round(s/86400) + 'd';
}

const MONTHS = { jan:0, feb:1, mar:2, apr:3, may:4, jun:5, jul:6, aug:7, sep:8, sept:8, oct:9, nov:10, dec:11 };
const FUZZY_DAY = { early: 5, mid: 15, late: 25 };

// Parse loose season strings: "mid-May to early October", "May 15 to Oct 10",
// "late June to early September". Returns { open: Date, close: Date } or null.
function parseSeasonRange(s, year) {
  const norm = s.toLowerCase().replace(/[–—]/g, '-');
  // Split on " to " or "-" between two month-y bits.
  const parts = norm.split(/\s+to\s+|\s*->\s*|\s*through\s+/);
  if (parts.length < 2) return null;
  const open = parseDateBit(parts[0], year);
  const close = parseDateBit(parts[1], year);
  if (!open || !close) return null;
  return { open, close };
}
function parseDateBit(s, year) {
  // "mid-May", "May 15", "early October", "Oct 10"
  const fuzzy = s.match(/(early|mid|late)[\s-]+([a-z]+)/);
  if (fuzzy) {
    const month = MONTHS[fuzzy[2].slice(0,4)] ?? MONTHS[fuzzy[2].slice(0,3)];
    if (month == null) return null;
    return new Date(year, month, FUZZY_DAY[fuzzy[1]]);
  }
  const explicit = s.match(/([a-z]+)\.?\s+(\d{1,2})/);
  if (explicit) {
    const month = MONTHS[explicit[1].slice(0,4)] ?? MONTHS[explicit[1].slice(0,3)];
    if (month == null) return null;
    return new Date(year, month, parseInt(explicit[2], 10));
  }
  return null;
}
function formatMonthDay(d) {
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric' });
}

// Compute the season verdict for the verdict line. season is a string like
// "mid-May to early October" or "year-round (boat access)" or null.
// Returns the colored verdict line HTML, or '' if no useful info.
function seasonVerdict(seasonStr, reservable) {
  if (!seasonStr) {
    if (reservable === true) return '';        // no info to assert
    if (reservable === false) {
      return '<div class="verdict fcfs">First-come, first-served</div>';
    }
    return '';
  }
  const today = new Date();
  const range = parseSeasonRange(seasonStr, today.getFullYear());
  const fcfsHint = reservable === false ? ' · first-come' : '';

  if (range && range.open && range.close) {
    if (today >= range.open && today <= range.close) {
      return `<div class="verdict open">Open through ${formatMonthDay(range.close)}${fcfsHint}</div>`;
    }
    if (today < range.open) {
      return `<div class="verdict closed">Closed until ${formatMonthDay(range.open)}${fcfsHint}</div>`;
    }
    // Past close — likely re-opens next year.
    const nextOpen = new Date(range.open);
    nextOpen.setFullYear(nextOpen.getFullYear() + 1);
    return `<div class="verdict closed">Closed until ${formatMonthDay(nextOpen)}${fcfsHint}</div>`;
  }
  if (/year[\s-]*round/i.test(seasonStr)) {
    return `<div class="verdict open">Year-round${fcfsHint}</div>`;
  }
  // Couldn't parse — show the raw string as neutral info.
  return `<div class="verdict fcfs">${escapeHtml(seasonStr)}${fcfsHint}</div>`;
}

// Decide which Reserve URL to use. Order: parks_canada_url > bcparks_url >
// rec.gov by id > rec.gov search. Returns full <a class="btn"> HTML.
function reserveButtonHTML(p) {
  let url = '';
  let label = 'Reserve';
  if (p.reserve_url) {
    url = p.reserve_url;
    label = labelForReserveUrl(url);
  } else if (p.parks_canada_url && p.reservable) {
    // The /pn-np/ab/banff page is informational; the actual booking flow is
    // at reservation.pc.gc.ca. Send the user there directly so they don't
    // have to hunt for the "Reserve" link on the park page.
    url = 'https://reservation.pc.gc.ca';
    label = 'Reserve on parks.canada.ca';
  } else if (p.parks_canada_url) {
    url = p.parks_canada_url;
    label = 'Park info on parks.canada.ca';
  } else if (p.parks_alberta_url && p.reservable) {
    url = 'https://www.reservecamping.alberta.ca';
    label = 'Reserve on Alberta Parks';
  } else if (p.parks_alberta_url) {
    url = p.parks_alberta_url;
    label = 'Park info on albertaparks.ca';
  } else if (p.bcparks_url) {
    url = p.bcparks_url;
    label = 'Reserve on bcparks.ca';
  } else if (p.recgov_id) {
    url = `https://www.recreation.gov/camping/campgrounds/${p.recgov_id}`;
    label = 'Reserve on recreation.gov';
  } else if (p.category === 'federal') {
    const recq = encodeURIComponent(p.name);
    url = `https://www.recreation.gov/search?q=${recq}&entity_type=campground&inventory_type=camping`;
    label = 'Search recreation.gov';
  } else {
    const gq = encodeURIComponent(`${p.name} ${p.state || ''}`.trim());
    url = `https://www.google.com/search?q=${gq}+campground`;
    label = 'Search Google';
  }
  if (p.reservable === false && !p.reserve_url) {
    return `<span class="btn btn-disabled">First-come, first-served</span>`;
  }
  return `<a class="btn btn-primary" href="${url}" target="_blank" rel="noreferrer">${label}</a>`;
}
function labelForReserveUrl(url) {
  if (url.includes('reservation.pc.gc.ca')) return 'Reserve on parks.canada.ca';
  if (url.includes('reserve.albertaparks')) return 'Reserve on Alberta Parks';
  if (url.includes('camping.bcparks')) return 'Reserve on bcparks.ca';
  if (url.includes('recreation.gov')) return 'Reserve on recreation.gov';
  return 'Reserve';
}

// Render a campground popup from a clicked feature. Pulled out of the click
// handler so search-result click can call it through synthesizeClick path.
//
// Shared rendering helpers (amenities, cell coverage, rating, sites tag,
// last_verified footer) live in ./campground-card.js so the drawer path
// (RFC 0003) reuses them without drift.
//
// Fork: US federal pins with recgov_id AND ?drawer=1 → drawer; everything
// else → existing popup. This isolates the drawer rollout to opt-in users.
export function openCampgroundPopup(f) {
  if (drawerFlagOn() && f.properties?.recgov_id) {
    openCampgroundDrawer(f);
    return;
  }
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;
  const amenities = parseAmenities(p);
  const cc = parseCellCoverage(p);
  const rr = parseRatingReviews(p);

  const parent = p.parent_name || p.typeLabel || '';
  const region = p.state || p.country || '';
  const subline = [parent, region].filter(Boolean).join(' · ');
  const distLine = state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lat, lng))
    : '';
  const sub = [subline, distLine].filter(Boolean).join(' · ');

  const verdict = seasonVerdict(p.season, p.reservable);
  const reserveBtn = reserveButtonHTML(p);
  const callBtn = p.phone
    ? `<a class="btn btn-secondary" href="tel:${p.phone}">Call ${p.phone}</a>`
    : '';
  const pills = amenitiesPillsHTML(amenities);
  const cellPills = cellCoveragePillsHTML(cc);
  const rating = ratingHTML(rr);
  const sitesTag = sitesTagHTML(p);
  const footer = lastVerifiedFooterHTML(p);

  const html = `
    <div class="popup${p.curated ? ' curated' : ''}">
      <h3>${escapeHtml(p.name)}</h3>
      ${sub ? `<div class="sub">${escapeHtml(sub)}</div>` : ''}
      ${verdict}
      <div class="actions">
        ${reserveBtn}
        ${callBtn}
      </div>
      ${pills}
      ${cellPills}
      ${rating}
      ${sitesTag ? `<div style="margin-top:6px">${sitesTag}</div>` : ''}
      ${footer}
    </div>`;
  openPopup({ lngLat: [lng, lat], html });
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
  const distLine = state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lngLat.lat, lngLat.lng))
    : '';
  const subBits = [kind === 'np' ? 'National Park' : 'State Park', stateName, distLine].filter(Boolean);
  const sub = subBits.join(' · ');
  const pills = [
    mgr && mgr !== 'National Park Service' ? `<span class="pill">${escapeHtml(mgr)}</span>` : '',
    acres ? `<span class="pill">${escapeHtml(acres)}</span>` : '',
  ].filter(Boolean).join('');
  const primaryBtn = url
    ? `<a class="btn btn-primary" href="${url}" target="_blank" rel="noreferrer">${escapeHtml(label === 'nps.gov' ? 'Open on nps.gov' : 'Search ' + label)}</a>`
    : '';
  const html = `
    <div class="popup">
      <h3>${escapeHtml(name)}</h3>
      ${sub ? `<div class="sub">${escapeHtml(sub)}</div>` : ''}
      ${primaryBtn ? `<div class="actions">${primaryBtn}</div>` : ''}
      ${pills ? `<div class="pills">${pills}</div>` : ''}
    </div>`;
  openPopup({ lngLat, html });
}

export function openPlanetFitnessPopup(f) {
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;
  const addr = [p.street, p.city, p.state, p.postcode].filter(Boolean).join(', ');
  const website = p.website || 'https://www.planetfitness.com/gyms?q=' + encodeURIComponent([p.city, p.state].filter(Boolean).join(' '));
  const distLine = state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lat, lng))
    : '';
  const sub = [addr, distLine].filter(Boolean).join(' · ');
  const callBtn = p.phone
    ? `<a class="btn btn-secondary" href="tel:${p.phone}">Call ${escapeHtml(p.phone)}</a>`
    : '';
  const hours = p.opening_hours
    ? `<div class="pills"><span class="pill">${escapeHtml(p.opening_hours)}</span></div>`
    : '';
  const html = `
    <div class="popup">
      <h3>${escapeHtml(p.name || 'Planet Fitness')}</h3>
      ${sub ? `<div class="sub">${escapeHtml(sub)}</div>` : ''}
      <div class="actions">
        <a class="btn btn-primary" href="${website}" target="_blank" rel="noreferrer">Open in Planet Fitness</a>
        ${callBtn}
      </div>
      ${hours}
    </div>`;
  openPopup({ lngLat: [lng, lat], html });
}

export function openSuperchargerPopup(f) {
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;
  const addr = [p.street, p.city, p.state].filter(Boolean).join(', ');
  const distLine = state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lat, lng))
    : '';
  const sub = [addr, distLine].filter(Boolean).join(' · ');
  const stalls = [p.v2 && `V2×${p.v2}`, p.v3 && `V3×${p.v3}`, p.v4 && `V4×${p.v4}`].filter(Boolean).join(' · ');
  const plugs = [p.nacs && `NACS×${p.nacs}`, p.tpc && `TPC×${p.tpc}`].filter(Boolean).join(' · ');
  const priceId = 'price-' + p.id;
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
    ? `<a class="btn btn-primary" href="${teslaUrl}" target="_blank" rel="noopener">Open in Tesla</a>`
    : `<span class="btn btn-disabled">No Tesla link available</span>`;
  const tags = [
    `<span class="tag" style="background:${p.color};color:#fff">${escapeHtml(p.status || '')}</span>`,
    p.stallCount ? `<span class="tag">${p.stallCount} stalls</span>` : '',
    p.powerKilowatt ? `<span class="tag">${p.powerKilowatt} kW</span>` : '',
  ].filter(Boolean).join(' ');
  const html = `
    <div class="popup">
      <h3>${escapeHtml(p.name || '')}</h3>
      ${sub ? `<div class="sub">${escapeHtml(sub)}</div>` : ''}
      ${p.facility ? `<div class="sub">at ${escapeHtml(p.facility)}</div>` : ''}
      <div class="actions">
        ${primaryBtn}
      </div>
      <div style="margin-top:6px">${tags}</div>
      ${stalls ? `<div class="pills"><span class="pill">${escapeHtml(stalls)}</span></div>` : ''}
      ${plugs ? `<div class="pills"><span class="pill">${escapeHtml(plugs)}</span></div>` : ''}
      ${p.dateOpened ? `<div class="footer">Opened ${escapeHtml(p.dateOpened)}</div>` : ''}
      <div id="${priceId}" class="pricing" style="margin-top:8px; padding-top:6px; border-top:1px solid #eee;">
        ${p.locationId ? '<span class="meta">Loading pricing…</span>' : '<span class="meta">No pricing available</span>'}
      </div>
    </div>`;
  openPopup({ lngLat: [lng, lat], html });
  if (p.locationId) loadPricing(p.locationId, priceId);
}
