// Shared rendering for campground details — used by the popup path
// (web/popups.js) and the drawer path (web/drawer.js, RFC 0003) so they
// don't drift over time.
//
// What lives here: parsers + presentation for amenities, cell coverage,
// star ratings, sites tag, last_verified footer. These are layout-agnostic
// HTML strings the caller composes into its outer container (popup vs
// drawer-below-fold).
//
// What does NOT live here: campground name/subline, season verdict,
// reserve button, phone CTA. Those have popup vs drawer-specific framing
// (e.g. drawer uses a different button style) and stay with the caller.

import { escapeHtml } from './core.js';

/** Parse properties.amenities (legacy array or canonical object) → string[]. */
export function parseAmenities(p) {
  const value = p.amenities;
  if (Array.isArray(value)) return parseStringList(value);
  if (!value || typeof value !== 'object') return [];
  const out = [];
  for (const [key, raw] of Object.entries(value)) {
    if (key === 'toilets' && value.toilet_kind) continue;
    const label = amenityLabel(key, raw);
    if (label) out.push(label);
  }
  return out;
}

/** Parse properties.activities (array) → string[]; safe on bad input. */
export function parseActivities(p) {
  return parseStringList(p.activities);
}

function parseStringList(value) {
  if (!Array.isArray(value)) return [];
  return value.map(v => String(v).trim()).filter(Boolean);
}

/** Parse properties.cell_coverage → object or null. */
export function parseCellCoverage(p) {
  const value = p.cell_coverage || p.cell_service;
  return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
}

/** Parse properties.rating_reviews → [avgRating, reviewCount] or null. */
export function parseRatingReviews(p) {
  const value = p.rating_reviews;
  return Array.isArray(value) ? value : null;
}

/** Render amenities as a row of pill chips. Empty string when no amenities. */
export function amenitiesPillsHTML(amenities) {
  if (!amenities || !amenities.length) return '';
  return `<div class="pills">${amenities.map(a => `<span class="pill">${escapeHtml(a)}</span>`).join('')}</div>`;
}

const CARRIER_LABEL = { verizon: 'Verizon', att: 'AT&T', tmobile: 'T-Mobile', sprint: 'Sprint', uscell: 'US Cellular' };
/**
 * Render per-carrier cell-signal chips. cc is `{verizon: [avg, count], ...}`
 * where avg is rec.gov's 0–4 scale. Sorts by signal strength desc.
 */
export function cellCoveragePillsHTML(cc) {
  if (!cc) return '';
  const entries = Object.entries(cc)
    .map(([k, v]) => [k, normalizeCellValue(v)])
    .filter(([, v]) => v && Number.isFinite(v.avg));
  if (!entries.length) return '';
  entries.sort((a, b) => b[1].avg - a[1].avg);
  return '<div class="cell">' + entries.map(([k, v]) => {
    const { avg, count } = v;
    const bucket = Math.max(0, Math.min(4, Math.round(avg)));
    const label = CARRIER_LABEL[k] || k;
    const title = Number.isFinite(count) ? ` title="${count} reports"` : '';
    return `<span class="cell-pill" data-bucket="${bucket}"${title}><span class="carrier">${label}</span><span class="val">${avg.toFixed(1)}</span></span>`;
  }).join('') + '</div>';
}

/** 4.3 → "★★★★☆" — half-stars round down. */
export function renderStars(v) {
  const full = Math.round(v);
  return '★'.repeat(full) + '☆'.repeat(Math.max(0, 5 - full));
}

/** Render rating row from [avg, count]. Empty string when null. */
export function ratingHTML(rr) {
  if (!Array.isArray(rr)) return '';
  return `<div class="rating" style="margin-top:6px"><span class="stars">${renderStars(rr[0])}</span> ${rr[0].toFixed(1)}<span class="count">(${rr[1].toLocaleString()})</span></div>`;
}

/** Render sites count as a tag. Empty string when sites is null/0. */
export function sitesTagHTML(p) {
  return p.sites ? `<span class="tag">${p.sites} sites</span>` : '';
}

/**
 * Render last_verified footer. Returns '' when missing or unparsable.
 * Highlights with `.warn` class when older than 60 days.
 */
export function lastVerifiedFooterHTML(p) {
  if (!p.last_verified) return '';
  const verified = new Date(p.last_verified);
  if (isNaN(verified)) return '';
  const ageDays = (Date.now() - verified.getTime()) / 86400000;
  const cls = ageDays > 60 ? 'footer warn' : 'footer';
  const stale = ageDays > 60 ? ' · check before booking' : '';
  return `<div class="${cls}">Verified ${p.last_verified}${stale}</div>`;
}

/**
 * Footnote naming the booking system the pin reserves through. Helps users
 * recognize the upstream booking flow + identifies why some pins have a heat
 * strip (we have a public API for that vendor) and others don't. The label
 * is computed by the backend (see PoiCta.bookingSystem) and shipped on
 * /api/pois/{id}.
 */
export function bookingSystemFooterHTML(p) {
  const sys = p.booking_system;
  if (!sys) return '';
  return `<div class="footer cg-booking-sys">Booking via ${escapeHtml(sys)}</div>`;
}

export function campgroundParentParkName(p) {
  const campgroundName = normalizeLinkTitle(firstText(p?.name));
  if (!Array.isArray(p?.links)) return '';
  for (const link of p.links) {
    if (!link || typeof link !== 'object') continue;
    const title = firstText(link.title, link.label, link.name);
    if (!title) continue;
    const normalized = normalizeLinkTitle(title);
    if (!normalized || normalized === campgroundName) continue;
    if (GENERIC_LINK_TITLE_RE.test(title) || NON_PARENT_LINK_TITLE_RE.test(title)) continue;
    if (PARENT_PARK_LINK_TITLE_RE.test(title)) return title;
  }
  return '';
}

export function structuredCampgroundDetailsHTML(p) {
  const stayRows = [
    detailRow('Status', firstText(p.status_description, titleCase(p.status))),
    detailRow('Price', priceDisplay(p.price)),
    detailRow('Check-in', scheduleTime(p.schedule, 'check_in_time')),
    detailRow('Check-out', scheduleTime(p.schedule, 'check_out_time')),
    detailRow('Max RV', lengthDisplay(p.max_rv_length)),
    detailRow('Max trailer', lengthDisplay(p.max_trailer_length)),
    detailRow('Pull-through', booleanDisplay(p.has_pull_through_sites)),
    detailRow('Big-rig friendly', booleanDisplay(p.big_rig_friendly)),
    detailRow('Elevation', elevationDisplay(p.elevation)),
  ].filter(Boolean).join('');

  const contactRows = [
    detailRow('Address', addressDisplay(p)),
    detailRow('Phone', p.phone),
    detailRow('Email', emailLink(p.email)),
    detailRow('Managed by', managementDisplay(p)),
  ].filter(Boolean).join('');

  const linkRows = linksHTML(p.links);
  const alerts = alertsHTML(p.alerts);
  const sourceRows = [
    detailRow('Data source', firstText(sourcesLabel(p.sources), p.source)),
    detailRow('Source ID', p.source_id),
    detailRow('Availability provider', firstText(p.availability_provider, p.provider_source)),
    detailRow('Booking site', firstText(p.booking_site, urlHost(firstText(p.reserve_url, p.reservation_url)))),
    detailRow('Last updated', firstText(p.metadata?.last_updated, p.last_verified)),
    connectionsRow(p.connections),
  ].filter(Boolean).join('');

  const sections = [
    detailSection('Stay details', stayRows),
    detailSection('Contact', contactRows),
    linkRows ? `<section class="cg-detail-group"><h4>Links</h4><div class="cg-link-list">${linkRows}</div></section>` : '',
    alerts,
    detailSection('Source metadata', sourceRows),
  ].filter(Boolean).join('');

  return sections ? `<div class="cg-structured-details">${sections}</div>` : '';
}

const MONTHS = { jan:0, feb:1, mar:2, apr:3, may:4, jun:5, jul:6, aug:7, sep:8, sept:8, oct:9, nov:10, dec:11 };
const FUZZY_DAY = { early: 5, mid: 15, late: 25 };

function parseSeasonRange(s, year) {
  const norm = s.toLowerCase().replace(/[–—]/g, '-');
  const parts = norm.split(/\s+to\s+|\s*->\s*|\s*through\s+/);
  if (parts.length < 2) return null;
  const open = parseDateBit(parts[0], year);
  const close = parseDateBit(parts[1], year);
  if (!open || !close) return null;
  return { open, close };
}
function parseDateBit(s, year) {
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

/**
 * Season verdict line. `seasonStr` is loose ("mid-May to early October",
 * "year-round (boat access)") and may be null. Returns colored verdict HTML
 * or '' when nothing useful to assert.
 */
export function seasonVerdictHTML(seasonStr, reservable) {
  if (!seasonStr) {
    if (reservable === false) return '<div class="verdict fcfs">First-come, first-served</div>';
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
    const nextOpen = new Date(range.open);
    nextOpen.setFullYear(nextOpen.getFullYear() + 1);
    return `<div class="verdict closed">Closed until ${formatMonthDay(nextOpen)}${fcfsHint}</div>`;
  }
  if (/year[\s-]*round/i.test(seasonStr)) {
    return `<div class="verdict open">Year-round${fcfsHint}</div>`;
  }
  return `<div class="verdict fcfs">${escapeHtml(seasonStr)}${fcfsHint}</div>`;
}

/**
 * Reserve / info button. The backend computes a {url, label, kind} CTA for
 * every campground pin — provider_ref + info_url → vendor-specific URL and
 * label, including dated Aspira NextGen deeplinks. The FE renders it
 * verbatim; the only fallback is a name search for pins with no upstream
 * link at all.
 *
 * `btnClass` is the CSS class prefix the caller wants (popup uses "btn",
 * drawer uses "cg-btn"). Returns full <a> HTML or a disabled span for
 * first-come-first-served pins with no info link.
 */
export function reserveButtonHTML(p, btnClass = 'btn') {
  let url = '';
  let label = 'Reserve';
  if (p.cta?.url) {
    url = p.cta.url;
    label = p.cta.label;
  } else if (p.reserve_url || p.reservation_url) {
    url = p.reserve_url || p.reservation_url;
    label = reserveUrlLabel(url);
  } else if (p.info_url || p.website) {
    url = p.info_url || p.website;
    label = 'Visit website';
  } else if (p.reservable === false) {
    // No CTA, marked FCFS — there's nothing to link to.
    return `<span class="${btnClass} ${btnClass}-disabled">First-come, first-served</span>`;
  } else {
    // No backend CTA and no booking provider — best-effort name search.
    // Region-specific park-system search beats raw Google when we can route
    // it confidently; falls through to Google otherwise.
    const regional = regionalParkSearch(p);
    if (regional) {
      [url, label] = regional;
    } else {
      const gq = encodeURIComponent(`${p.name} ${p.state || ''}`.trim());
      url = `https://www.google.com/search?q=${gq}+campground`;
      label = 'Search Google';
    }
  }
  return `<a class="${btnClass} ${btnClass}-primary" href="${url}" target="_blank" rel="noreferrer">${label}</a>`;
}

const AMENITY_LABELS = {
  camp_store: 'Camp store',
  dump_station: 'Dump station',
  electric_hookups: 'Electric hookups',
  fires_allowed: 'Fires allowed',
  pets_allowed: 'Pets allowed',
  sewer_hookups: 'Sewer hookups',
  showers: 'Showers',
  toilets: 'Toilets',
  trash: 'Trash',
  water: 'Water',
  water_hookups: 'Water hookups',
  wifi: 'Wi-Fi',
};
const NEGATIVE_AMENITY_LABELS = {
  electric_hookups: 'No electric hookups',
  sewer_hookups: 'No sewer hookups',
  showers: 'No showers',
  water: 'No water',
  water_hookups: 'No water hookups',
};
const PARENT_PARK_LINK_TITLE_RE = /\b(park|preserve|forest|recreation area|recreation site|conservation area|wilderness|monument|seashore|lakeshore|reserve)\b/i;
const GENERIC_LINK_TITLE_RE = /^(official\s+(page|site|website)|website|home|homepage|map|directions?)$/i;
const NON_PARENT_LINK_TITLE_RE = /\b(reservations?|booking|fees?|passes?|permits?|map|directions?|calendar|alerts?|brochure|guide)\b/i;

function amenityLabel(key, value) {
  if (value === null || value === undefined) return '';
  if (key === 'toilet_kind' && typeof value === 'string' && value.trim()) {
    return `${titleCase(value)} toilets`;
  }
  if (value === true) return AMENITY_LABELS[key] || titleCase(key);
  if (value === false) return NEGATIVE_AMENITY_LABELS[key] || '';
  if (typeof value === 'string' && value.trim()) return `${AMENITY_LABELS[key] || titleCase(key)}: ${value.trim()}`;
  return '';
}

function normalizeCellValue(value) {
  if (Array.isArray(value)) {
    const avg = Number(value[0]);
    const count = Number(value[1]);
    return { avg, count };
  }
  const avg = Number(value);
  return { avg, count: NaN };
}

function reserveUrlLabel(url) {
  const host = urlHost(url);
  if (host.endsWith('recreation.gov')) return 'View on recreation.gov';
  if (host.endsWith('reserveamerica.com')) return 'View on ReserveAmerica';
  if (host.endsWith('reservecalifornia.com')) return 'View on ReserveCalifornia';
  if (host.endsWith('parks.canada.ca') || host.endsWith('pc.gc.ca')) return 'View on Parks Canada';
  return 'Reserve';
}

function detailSection(title, body) {
  if (!body) return '';
  return `<section class="cg-detail-group"><h4>${escapeHtml(title)}</h4><div class="cg-detail-grid">${body}</div></section>`;
}

function detailRow(label, value) {
  if (value === null || value === undefined) return '';
  const html = typeof value === 'object' && value.__html != null
    ? String(value.__html).trim()
    : escapeHtml(String(value).trim());
  if (!html) return '';
  return `<div class="cg-detail-row"><span class="cg-detail-label">${escapeHtml(label)}</span><span class="cg-detail-value">${html}</span></div>`;
}

function emailLink(email) {
  const value = firstText(email);
  if (!value) return '';
  return { __html: `<a href="mailto:${escapeHtml(value)}">${escapeHtml(value)}</a>` };
}

function managementDisplay(p) {
  const management = p.management && typeof p.management === 'object' ? p.management : {};
  const name = firstText(p.agency, management.agency_name, management.agency, management.name);
  const url = safeUrl(firstText(management.agency_website, management.website_url, management.website, management.url));
  if (!name && !url) return '';
  if (!url) return name;
  const label = name || url;
  return { __html: `<a href="${escapeHtml(url)}" target="_blank" rel="noreferrer">${escapeHtml(label)}</a>` };
}

function addressDisplay(p) {
  const addr = p.address && typeof p.address === 'object' ? p.address : {};
  const nested = addr.address && typeof addr.address === 'object' ? addr.address : {};
  const full = firstText(p.full_address, addr.full, nested.full);
  if (full) return full;
  const street = firstText(p.street, addr.street, addr.street1, addr.address_line, nested.street, nested.street1, nested.address_line);
  const city = firstText(p.city, addr.city, nested.city);
  const region = firstText(p.state, addr.state, addr.state_code, nested.state, nested.state_code);
  const postcode = firstText(p.postcode, addr.postcode, addr.postal_code, addr.zipcode, nested.postcode, nested.postal_code, nested.zipcode);
  const country = firstText(p.country, addr.country, addr.country_code, nested.country, nested.country_code);
  const locality = [city, region, postcode].filter(Boolean).join(', ');
  return [street, locality, country].filter(Boolean).join(' · ');
}

function connectionsRow(connections) {
  if (!connections || typeof connections !== 'object' || Array.isArray(connections)) return '';
  const parts = Object.entries(connections)
    .filter(([, value]) => value !== null && value !== undefined && String(value).trim())
    .map(([key, value]) => `<span class="cg-connection"><span>${escapeHtml(key)}</span><code>${escapeHtml(String(value))}</code></span>`);
  if (!parts.length) return '';
  return detailRow('Connections', { __html: parts.join('') });
}

function linksHTML(links) {
  if (!Array.isArray(links)) return '';
  return links.map(linkHTML).filter(Boolean).join('');
}

function linkHTML(link) {
  if (!link || typeof link !== 'object') return '';
  const url = safeUrl(firstText(link.url, link.href));
  if (!url) return '';
  const label = firstText(link.title, link.label, link.name, url);
  return `<a class="cg-detail-link" href="${escapeHtml(url)}" target="_blank" rel="noreferrer">${escapeHtml(label)}</a>`;
}

function alertsHTML(alerts) {
  if (!Array.isArray(alerts) || !alerts.length) return '';
  const rows = alerts.map(alertHTML).filter(Boolean).join('');
  if (!rows) return '';
  return `<section class="cg-detail-group cg-alert-group"><h4>Alerts</h4><div class="cg-alert-list">${rows}</div></section>`;
}

function alertHTML(alert) {
  if (typeof alert === 'string') {
    const text = alert.trim();
    return text ? `<div class="cg-alert-item">${escapeHtml(text)}</div>` : '';
  }
  if (!alert || typeof alert !== 'object') return '';
  const title = firstText(alert.title, alert.name, alert.headline, alert.type);
  const body = firstText(alert.description, alert.message, alert.body, alert.text);
  if (!title && !body) return '';
  return `<div class="cg-alert-item">${title ? `<strong>${escapeHtml(title)}</strong>` : ''}${body ? `<span>${escapeHtml(body)}</span>` : ''}</div>`;
}

function priceDisplay(price) {
  if (!price || typeof price !== 'object') return '';
  const min = finiteNumber(price.minimum ?? price.min);
  const max = finiteNumber(price.maximum ?? price.max);
  if (min == null && max == null) return '';
  const currency = firstText(price.currency_code, price.currency) || 'USD';
  const format = (n) => `${currencySymbol(currency)}${formatNumber(n)}`;
  if (min != null && max != null && min !== max) return `${format(min)}-${format(max)}`;
  return format(min ?? max);
}

function scheduleTime(schedule, key) {
  if (!schedule || typeof schedule !== 'object') return '';
  return timeDisplay(schedule[key]);
}

function timeDisplay(value) {
  const text = firstText(value);
  const match = text.match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
  if (!match) return text;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return text;
  const suffix = hour >= 12 ? 'PM' : 'AM';
  const h12 = hour % 12 || 12;
  return `${h12}:${String(minute).padStart(2, '0')} ${suffix}`;
}

function lengthDisplay(value) {
  const n = finiteNumber(value);
  if (n == null) return '';
  return `${formatNumber(n)} ft`;
}

function elevationDisplay(value) {
  const n = finiteNumber(value);
  if (n == null) return '';
  return `${formatNumber(n)} ft`;
}

function booleanDisplay(value) {
  if (value === true) return 'Yes';
  if (value === false) return 'No';
  return '';
}

function currencySymbol(currency) {
  switch (currency.toUpperCase()) {
    case 'USD':
    case 'CAD':
      return '$';
    default:
      return `${currency.toUpperCase()} `;
  }
}

function formatNumber(n) {
  return n.toLocaleString('en-US', {
    maximumFractionDigits: Number.isInteger(n) ? 0 : 2,
  });
}

function finiteNumber(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function safeUrl(url) {
  const value = firstText(url);
  if (!value || !/^(https?:|mailto:|tel:|\/|#)/i.test(value)) return '';
  return value;
}

function firstText(...values) {
  for (const value of values) {
    if (typeof value !== 'string' && typeof value !== 'number') continue;
    const trimmed = String(value).trim();
    if (trimmed) return trimmed;
  }
  return '';
}

// Render the `sources` array from /api/pois/{id} as a comma-separated string.
// Empty array means firstText falls back to p.source.
function sourcesLabel(sources) {
  if (!Array.isArray(sources)) return '';
  const cleaned = sources
    .map(v => (typeof v === 'string' || typeof v === 'number' ? String(v).trim() : ''))
    .filter(Boolean);
  return [...new Set(cleaned)].join(', ');
}

function urlHost(url) {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
}

function normalizeLinkTitle(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function titleCase(value) {
  return String(value)
    .replace(/_/g, ' ')
    .replace(/\w\S*/g, word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase());
}

/**
 * Map a pin's (state, country) to its park-system search page. Returns
 * [url, label] or null if we don't have a confident route. Sites with their
 * own structured search are preferable to Google because the result set is
 * already filtered to actual park entries, not a noisy general web search.
 */
function regionalParkSearch(p) {
  const q = encodeURIComponent(p.name);
  if (p.country === 'CA') {
    if (p.state === 'AB') return [`https://www.albertaparks.ca/parks/?searchPhrase=${q}`, 'Search Alberta Parks'];
    if (p.state === 'BC') return [`https://bcparks.ca/?s=${q}`, 'Search BC Parks'];
  }
  // US state-park sites — only the ones with a working search-by-name URL.
  // Quietly returning null for the rest leaves the Google fallback in place.
  switch (p.state) {
    case 'WA': return [`https://parks.wa.gov/find-parks/parks-and-recreation-areas?keyword=${q}`, 'Search WA State Parks'];
    case 'OR': return [`https://stateparks.oregon.gov/index.cfm?do=search.results&searchTerm=${q}`, 'Search OR State Parks'];
    case 'CA': return [`https://www.parks.ca.gov/?page_id=21805&q=${q}`, 'Search CA State Parks'];
    case 'CO': return [`https://cpw.state.co.us/buyapply/Pages/CampingDetails.aspx?q=${q}`, 'Search CO Parks'];
    case 'TX': return [`https://tpwd.texas.gov/state-parks/find-a-park?keyword=${q}`, 'Search TX State Parks'];
    case 'NY': return [`https://parks.ny.gov/parks/?q=${q}`, 'Search NY State Parks'];
    case 'FL': return [`https://www.floridastateparks.org/parks-and-trails?keyword=${q}`, 'Search FL State Parks'];
    default: return null;
  }
}
