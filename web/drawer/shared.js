// Cross-view drawer helpers shared by every category renderer:
//   - drawerHeader / directionsButtonHTML — slot-shared chrome HTML
//   - upstreamHTML / reviveJsonProp — JSON-property plumbing for MapLibre
//   - buildSubline / distanceTo / normalizeAspira — small composers

import { state, distanceKm, formatDistance, escapeHtml } from '../core.js';

/** Compose subline from arbitrary parts: "Loop · State · 2.4 km away". */
export function buildSubline(parts) {
  return parts.filter(Boolean).join(' · ');
}

/** Distance string from user to lng/lat, or '' when location is off. */
export function distanceTo(lng, lat) {
  return state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lat, lng))
    : '';
}

/**
 * Per-POI Directions button. Same visual slot in every drawer; the click is
 * picked up by a delegated listener in chrome.js that routes through
 * window.__rtAddTripStop. Label flips based on current trip mode so the
 * button reads "Add stop" once a route is being built.
 */
export function directionsButtonHTML({ name, lng, lat, kind = 'PLACE' }) {
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return '';
  const mode = (typeof window !== 'undefined' && typeof window.__rtTripMode === 'function')
    ? window.__rtTripMode() : 'browse';
  // Icon-only button. The aria-label keeps it a11y-readable, and the
  // tooltip flips between Directions / Add stop based on trip mode so a
  // hover (desktop) discloses what's about to happen.
  const a11yLabel = mode === 'directions' ? 'Add stop' : 'Directions';
  const icon = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10l-7 7-3-3-9 9"/><path d="M14 10h7v7"/></svg>`;
  return `<button type="button" class="cg-btn cg-btn-secondary rt-poi-directions"
    aria-label="${escapeHtml(a11yLabel)}"
    title="${escapeHtml(a11yLabel)}"
    data-name="${escapeHtml(name || '')}"
    data-lng="${lng}"
    data-lat="${lat}"
    data-kind="${escapeHtml(kind)}">${icon}</button>`;
}

/** Drawer header: name + subline + optional verdict. cg-* classes match chrome.js. */
export function drawerHeader(name, sub, verdictHtml = '') {
  return `
    <header class="cg-drawer-head">
      <h2>${escapeHtml(name)}</h2>
      ${sub ? `<div class="cg-sub">${escapeHtml(sub)}</div>` : ''}
      ${verdictHtml ? `<div class="cg-verdict-row">${verdictHtml}</div>` : ''}
    </header>`;
}

// MapLibre stringifies nested-object properties on the way out of a GeoJSON
// source. Parse the ones the drawer reads; primitives survive untouched.
export function reviveJsonProp(p, key) {
  const v = p?.[key];
  if (typeof v !== 'string') return;
  try { p[key] = JSON.parse(v); } catch { p[key] = null; }
}

/**
 * MapLibre wraps features so geometry/id are accessor properties — `{...f}`
 * would silently drop them. Mutate the properties bag in place; the
 * feature is per-click ephemeral, so this won't leak state into the source.
 */
export function normalizeAspira(f) {
  const a = f.properties?.aspira;
  if (typeof a !== 'string') return f;
  let parsed = null;
  try { parsed = JSON.parse(a); } catch {}
  f.properties.aspira = parsed;
  return f;
}

/**
 * Raw upstream payload (whatever the ETL didn't promote). Flat key/value
 * table for top-level fields, nested objects/arrays as collapsed JSON.
 * Always collapsed by default — this is a "what's available" surface,
 * not the primary read.
 */
export function upstreamHTML(upstream) {
  if (!upstream || typeof upstream !== 'object') return '';
  const entries = Object.entries(upstream).filter(([, v]) => {
    if (v === null || v === undefined) return false;
    if (typeof v === 'string') return v.trim() !== '';
    if (Array.isArray(v)) return v.length > 0;
    if (typeof v === 'object') return Object.keys(v).length > 0;
    return true;
  });
  if (entries.length === 0) return '';
  const rows = entries.map(([k, v]) => {
    const label = escapeHtml(k);
    if (typeof v === 'object') {
      const json = JSON.stringify(v, null, 2);
      return `<tr><th>${label}</th><td><details><summary>${Array.isArray(v) ? `[${v.length}]` : '{…}'}</summary><pre>${escapeHtml(json)}</pre></details></td></tr>`;
    }
    const text = String(v);
    if (/^https?:\/\//.test(text)) {
      return `<tr><th>${label}</th><td><a href="${escapeHtml(text)}" target="_blank" rel="noreferrer">${escapeHtml(text)}</a></td></tr>`;
    }
    return `<tr><th>${label}</th><td>${escapeHtml(text)}</td></tr>`;
  }).join('');
  return `
    <details class="cg-upstream">
      <summary>Upstream data (${entries.length})</summary>
      <table class="cg-upstream-table"><tbody>${rows}</tbody></table>
    </details>
  `;
}
