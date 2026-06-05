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

const CARRIER_LABEL = { verizon: 'VZ', att: 'ATT', tmobile: 'TMo', sprint: 'Spr' };
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
