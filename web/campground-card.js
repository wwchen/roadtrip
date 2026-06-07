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
import { buildAspiraDeeplink } from './aspira.js';

/** Parse properties.amenities (JSON-encoded array) → string[]; safe on bad input. */
export function parseAmenities(p) {
  try { return JSON.parse(p.amenities) || []; } catch { return []; }
}

/** Parse properties.cell_coverage → object or null. */
export function parseCellCoverage(p) {
  try { return JSON.parse(p.cell_coverage); } catch { return null; }
}

/** Parse properties.rating_reviews → [avgRating, reviewCount] or null. */
export function parseRatingReviews(p) {
  try { return JSON.parse(p.rating_reviews); } catch { return null; }
}

/** Render amenities as a row of pill chips. Empty string when no amenities. */
export function amenitiesPillsHTML(amenities) {
  if (!amenities || !amenities.length) return '';
  return `<div class="pills">${amenities.map(a => `<span class="pill">${escapeHtml(a)}</span>`).join('')}</div>`;
}

const CARRIER_LABEL = { verizon: 'Verizon', att: 'AT&T', tmobile: 'T-Mobile', sprint: 'Sprint' };
/**
 * Render per-carrier cell-signal chips. cc is `{verizon: [avg, count], ...}`
 * where avg is rec.gov's 0–4 scale. Sorts by signal strength desc.
 */
export function cellCoveragePillsHTML(cc) {
  if (!cc) return '';
  const entries = Object.entries(cc);
  if (!entries.length) return '';
  entries.sort((a, b) => b[1][0] - a[1][0]);
  return '<div class="cell">' + entries.map(([k, v]) => {
    const [avg, cnt] = v;
    const bucket = Math.max(0, Math.min(4, Math.round(avg)));
    const label = CARRIER_LABEL[k] || k;
    return `<span class="cell-pill" data-bucket="${bucket}" title="${cnt} reports"><span class="carrier">${label}</span><span class="val">${avg.toFixed(1)}</span></span>`;
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
 * strip (we have a public API for that vendor) and others don't.
 */
export function bookingSystemFooterHTML(p) {
  const sys = bookingSystemLabel(p);
  if (!sys) return '';
  return `<div class="footer cg-booking-sys">Booking via ${sys}</div>`;
}

function bookingSystemLabel(p) {
  if (p.aspira?.host) {
    if (p.aspira.host === 'reservation.pc.gc.ca') return 'Aspira NextGen (Parks Canada)';
    if (p.aspira.host === 'camping.bcparks.ca') return 'Aspira NextGen (BC Parks)';
    if (p.aspira.host === 'washington.goingtocamp.com') return 'Aspira NextGen (WA State Parks)';
    return 'Aspira NextGen';
  }
  if (p.recgov_id) return 'Recreation.gov';
  if (p.parks_alberta_url) return 'Camis (Alberta Parks)';
  // bcparks_url without aspira: BC Parks site, but no booking system flagged.
  if (p.bcparks_url) return 'BC Parks';
  if (p.parks_canada_url) return 'Parks Canada';
  return null;
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
 * Reserve button — picks the right vendor URL by precedence: explicit
 * reserve_url, parks_canada, parks_alberta, bcparks, recgov_id, federal
 * search, Google. `btnClass` is the CSS class prefix the caller wants
 * (popup uses "btn", drawer uses "cg-btn"). Returns full <a> HTML or a
 * disabled span for first-come-first-served pins.
 */
export function reserveButtonHTML(p, btnClass = 'btn') {
  let url = '';
  let label = 'Reserve';
  // Aspira NextGen deeplink takes priority across all providers (Parks Canada,
  // BC Parks, WA State Parks). When we have the per-park IDs, we can drop the
  // user straight onto the booking flow instead of an info/homepage.
  if (p.aspira?.transactionLocationId != null && p.aspira?.mapId != null) {
    url = buildAspiraDeeplink({
      host: p.aspira.host || 'reservation.pc.gc.ca',
      transactionLocationId: p.aspira.transactionLocationId,
      mapId: p.aspira.mapId,
      resourceLocationId: p.aspira.resourceLocationId,
    });
    label = labelForAspiraHost(p.aspira.host);
  } else if (p.reserve_url) {
    url = p.reserve_url;
    label = labelForReserveUrl(url);
  } else if (p.parks_canada_url && p.reservable) {
    // No aspira IDs but the pin is reservable on Parks Canada — homepage is
    // the best we can do.
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
    // Region-specific park-system search beats a raw Google for the cases
    // we can route confidently. Each entry returns [url, label].
    // Falls through to Google if the (state, country) tuple isn't here —
    // a Google query is at least targeted, not random.
    const regional = regionalParkSearch(p);
    if (regional) {
      [url, label] = regional;
    } else {
      const gq = encodeURIComponent(`${p.name} ${p.state || ''}`.trim());
      url = `https://www.google.com/search?q=${gq}+campground`;
      label = 'Search Google';
    }
  }
  if (p.reservable === false && !p.reserve_url) {
    return `<span class="${btnClass} ${btnClass}-disabled">First-come, first-served</span>`;
  }
  return `<a class="${btnClass} ${btnClass}-primary" href="${url}" target="_blank" rel="noreferrer">${label}</a>`;
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

function labelForAspiraHost(host) {
  if (host === 'camping.bcparks.ca') return 'Book on BC Parks';
  if (host === 'washington.goingtocamp.com') return 'Book WA State Park';
  return 'Reserve on parks.canada.ca';
}

function labelForReserveUrl(url) {
  if (url.includes('reservation.pc.gc.ca')) return 'Reserve on parks.canada.ca';
  if (url.includes('reserve.albertaparks')) return 'Reserve on Alberta Parks';
  if (url.includes('camping.bcparks')) return 'Reserve on bcparks.ca';
  if (url.includes('recreation.gov')) return 'Reserve on recreation.gov';
  return 'Reserve';
}
