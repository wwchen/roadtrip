// Campsite availability drawer for US federal campground pins (RFC 0003).
//
// Mobile: bottom sheet with two snap states (60% half / 90% full) using dvh
// units + visualViewport.resize so iOS Safari URL-bar collapse doesn't break
// height. Desktop: right-side panel ~420px wide.
//
// Renders one of six frontend states from the JSON contract returned by
// /api/campsite/availability/{recgov_id}: loading (client-side), success,
// zero_available, closed_for_season, error, empty.
//
// Above-the-fold composition (mobile half, ~310px headroom inside ~480px):
//   campground name → park/state subline → verdict pill → summary sentence
//   → freshness (checked Nm ago) → 30-day heat-strip → primary CTA
//   → secondary CTA → "Details" divider → below-fold (photos, amenities,
//   cell, ratings, last_verified) — pulled in from web/campground-card.js
//
// Pin reselect while drawer open: opacity-only fade (~150ms), DOM stable,
// skeleton overlay during fetch. Inflight fetch is cancelled.

import { state, distanceKm, formatDistance, escapeHtml } from './core.js';
import {
  parseAmenities,
  parseCellCoverage,
  parseRatingReviews,
  amenitiesPillsHTML,
  cellCoveragePillsHTML,
  ratingHTML,
  sitesTagHTML,
  lastVerifiedFooterHTML,
  seasonVerdictHTML,
  reserveButtonHTML,
} from './campground-card.js';

const DRAWER_ROOT_ID = 'cg-drawer';
const BACKDROP_ID = 'cg-drawer-backdrop';

let openController = null;   // AbortController for inflight fetch on the open campground
let activeFeature = null;    // currently displayed feature

/**
 * Generic drawer entry point. Caller provides ready-to-mount HTML; the
 * drawer module owns shell, animation, dismissal, and pin-reselect plumbing.
 * Optional onMounted is invoked after the content is in the DOM (use it for
 * fetches that fill in placeholders — e.g. supercharger pricing).
 */
export function openDrawer(contentHtml, onMounted) {
  ensureDrawerDOM();
  const root = document.getElementById(DRAWER_ROOT_ID);
  const backdrop = document.getElementById(BACKDROP_ID);

  if (openController) openController.abort();
  openController = new AbortController();
  activeFeature = null;

  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  content.innerHTML = contentHtml;
  show();
  if (typeof onMounted === 'function') onMounted(openController.signal);

  root.querySelector('.cg-drawer-close')?.addEventListener('click', close);
  attachDragHandlers(root);
}

/**
 * Campground-specific drawer. Renders availability for recgov pins and
 * skips it for everything else.
 */
export function openCampgroundDrawer(f) {
  ensureDrawerDOM();
  const root = document.getElementById(DRAWER_ROOT_ID);

  if (openController) openController.abort();
  openController = new AbortController();
  activeFeature = f;

  renderShell(f);
  show();
  if (f.properties?.recgov_id) {
    fetchAvailability(f, openController.signal);
  }

  root.querySelector('.cg-drawer-close')?.addEventListener('click', close);
  attachDragHandlers(root);
}

function close() {
  if (openController) {
    openController.abort();
    openController = null;
  }
  activeFeature = null;
  const root = document.getElementById(DRAWER_ROOT_ID);
  const backdrop = document.getElementById(BACKDROP_ID);
  root?.classList.remove('open', 'full');
  backdrop?.classList.remove('open');
  setTimeout(() => {
    if (root) root.style.display = 'none';
    if (backdrop) backdrop.style.display = 'none';
  }, 220);
}

function show() {
  const root = document.getElementById(DRAWER_ROOT_ID);
  const backdrop = document.getElementById(BACKDROP_ID);
  if (root) {
    root.style.display = 'flex';
    requestAnimationFrame(() => root.classList.add('open'));
  }
  if (backdrop) {
    backdrop.style.display = 'block';
    requestAnimationFrame(() => backdrop.classList.add('open'));
  }
}

/**
 * Build the drawer DOM once and reuse. Sibling of #map at body level so
 * MapLibre gestures pass through the (pointer-events:none) backdrop.
 */
function ensureDrawerDOM() {
  if (document.getElementById(DRAWER_ROOT_ID)) return;

  const backdrop = document.createElement('div');
  backdrop.id = BACKDROP_ID;
  backdrop.className = 'cg-drawer-backdrop';
  document.body.appendChild(backdrop);

  const root = document.createElement('aside');
  root.id = DRAWER_ROOT_ID;
  root.className = 'cg-drawer';
  root.setAttribute('role', 'dialog');
  root.setAttribute('aria-label', 'Pin details');
  root.innerHTML = `
    <div class="cg-drawer-handle" aria-hidden="true"></div>
    <button class="cg-drawer-close" aria-label="Close">&times;</button>
    <div class="cg-drawer-content"></div>
  `;
  document.body.appendChild(root);

  // iOS Safari: visualViewport.resize fires on URL-bar collapse, keyboard,
  // and zoom. Filter for URL-bar (large height delta, no zoom change).
  if (window.visualViewport) {
    let lastH = window.visualViewport.height;
    window.visualViewport.addEventListener('resize', () => {
      const dh = Math.abs(window.visualViewport.height - lastH);
      // Only react to substantial changes — small deltas are zoom artifacts.
      if (dh > 40) {
        lastH = window.visualViewport.height;
        // CSS dvh handles the rest; this is a hook for future fine-tuning.
      }
    });
  }
}

/** Render the static parts (name, subline, verdict, CTAs) from the feature. */
function renderShell(f) {
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;
  const parent = p.parent_name || p.typeLabel || '';
  const region = p.state || p.country || '';
  const subline = [parent, region].filter(Boolean).join(' · ');
  const distLine = state.userLocation
    ? formatDistance(distanceKm(state.userLocation.lat, state.userLocation.lng, lat, lng))
    : '';
  const sub = [subline, distLine].filter(Boolean).join(' · ');

  const amenities = parseAmenities(p);
  const cc = parseCellCoverage(p);
  const rr = parseRatingReviews(p);
  const pills = amenitiesPillsHTML(amenities);
  const cellPills = cellCoveragePillsHTML(cc);
  const rating = ratingHTML(rr);
  const sitesTag = sitesTagHTML(p);
  const footer = lastVerifiedFooterHTML(p);

  const verdict = seasonVerdictHTML(p.season, p.reservable);
  const callBtn = p.phone
    ? `<a class="cg-btn cg-btn-secondary" href="tel:${p.phone}">Call ${escapeHtml(p.phone)}</a>`
    : '';

  // Recgov pins get the availability-first treatment: heat-strip, /campsite
  // deeplink for alerts, rec.gov as secondary. Non-recgov pins skip
  // availability entirely and just surface the existing reserve button.
  const availabilitySection = p.recgov_id
    ? `
      <section class="cg-availability" aria-live="polite">
        <div class="cg-summary">Checking availability…</div>
        <div class="cg-freshness">&nbsp;</div>
        <div class="cg-strip" aria-hidden="true">
          ${'<div class="cg-cell skeleton"></div>'.repeat(30)}
        </div>
        <div class="cg-day-labels"><span>Today</span><span></span></div>
      </section>`
    : '';

  const actions = p.recgov_id
    ? `
      <div class="cg-actions">
        <a class="cg-btn cg-btn-primary" href="/campsite?campground=${encodeURIComponent(p.recgov_id)}" data-cta="watch">Watch for openings</a>
        <a class="cg-btn cg-btn-secondary" href="https://www.recreation.gov/camping/campgrounds/${encodeURIComponent(p.recgov_id)}" target="_blank" rel="noreferrer" data-cta="reserve">Reserve on rec.gov</a>
        ${callBtn}
      </div>`
    : `
      <div class="cg-actions">
        ${reserveButtonHTML(p, 'cg-btn')}
        ${callBtn}
      </div>`;

  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  content.innerHTML = `
    <header class="cg-drawer-head">
      <h2>${escapeHtml(p.name)}</h2>
      ${sub ? `<div class="cg-sub">${escapeHtml(sub)}</div>` : ''}
      ${verdict ? `<div class="cg-verdict-row">${verdict}</div>` : ''}
    </header>

    ${availabilitySection}
    ${actions}

    <details class="cg-details" open>
      <summary>Details</summary>
      ${pills}
      ${cellPills}
      ${rating}
      ${sitesTag ? `<div class="cg-sites">${sitesTag}</div>` : ''}
      ${footer}
    </details>
  `;
}

async function fetchAvailability(f, signal) {
  const recgovId = f.properties.recgov_id;
  const url = `/api/campsite/availability/${encodeURIComponent(recgovId)}?days=30`;
  try {
    const resp = await fetch(url, { signal });
    const json = await resp.json().catch(() => null);

    // Discard stale response if the user has since selected a different pin.
    if (activeFeature !== f) return;

    if (resp.status === 503 || (json && json.state === 'error')) {
      renderError(json?.error || 'unknown', json?.retry_after_s || 60, f);
      return;
    }
    if (resp.status === 400 || resp.status === 404) {
      // Bad ID / not found — fall back to the empty state silently.
      renderEmpty();
      return;
    }
    if (!resp.ok || !json) {
      renderError('unknown', 30, f);
      return;
    }

    renderState(json);
  } catch (e) {
    if (e.name === 'AbortError') return;
    renderError('network', 30, f);
  }
}

/** Render success / zero_available / closed_for_season / empty. */
function renderState(json) {
  const summaryEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-summary`);
  const freshEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-freshness`);
  const stripEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-strip`);
  const labelEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-day-labels span:last-child`);
  const primaryBtn = document.querySelector(`#${DRAWER_ROOT_ID} .cg-btn-primary`);

  summaryEl.textContent = json.summary || '';
  const ageMin = Math.max(1, Math.round((json.cache?.age_seconds ?? 0) / 60));
  const stale = ageMin >= 10;
  freshEl.innerHTML = stale
    ? `<span class="cg-stale">checked ${ageMin}m ago · <a href="#" class="cg-refresh">refresh</a></span>`
    : `<span>checked ${ageMin}m ago</span>`;

  if (json.state === 'closed_for_season') {
    // Replace strip with a banner.
    stripEl.outerHTML = `<div class="cg-closed-banner">⛰️ ${json.season?.reopens_on ? 'Reopens ' + json.season.reopens_on : 'Closed for season'}</div>`;
    primaryBtn.textContent = 'Watch for opening day';
    if (labelEl) labelEl.textContent = '';
    return;
  }

  if (json.state === 'empty') {
    summaryEl.textContent = 'No availability data — try Reserve on rec.gov directly';
    stripEl.style.display = 'none';
    if (labelEl) labelEl.textContent = '';
    return;
  }

  // success / zero_available — render heat-strip cells.
  const cells = (json.availability || []).map((d) => {
    const status = d.status || 'closed';
    const dow = new Date(d.date + 'T00:00:00Z').getUTCDay();
    const isWeekend = dow === 5 || dow === 6 || dow === 0;
    return `<div class="cg-cell cg-cell-${status}${isWeekend ? ' weekend' : ''}" title="${d.date}: ${status}"></div>`;
  }).join('');
  stripEl.innerHTML = cells;

  if (json.state === 'zero_available') {
    primaryBtn.textContent = 'Snipe a cancellation';
  } else {
    primaryBtn.textContent = 'Watch for openings';
  }

  if (labelEl && json.window?.start && json.window?.days) {
    const last = new Date(json.window.start + 'T00:00:00Z');
    last.setUTCDate(last.getUTCDate() + json.window.days - 1);
    labelEl.textContent = last.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
  }
}

function renderError(code, retryAfter, f) {
  const summaryEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-summary`);
  const stripEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-strip`);
  if (!summaryEl || !stripEl) return;
  const msg = code === 'rate_limited'
    ? "rec.gov is rate-limiting us"
    : code === 'ip_throttled'
    ? "Too many requests — give it a minute"
    : "Couldn't reach rec.gov";
  summaryEl.innerHTML = `<span class="cg-error">${escapeHtml(msg)} · <a href="#" class="cg-retry">Retry</a></span>`;
  // Replace skeleton with hashed cells.
  stripEl.innerHTML = '<div class="cg-cell cg-cell-closed"></div>'.repeat(30);

  document.querySelector(`#${DRAWER_ROOT_ID} .cg-retry`)?.addEventListener('click', (e) => {
    e.preventDefault();
    if (openController) openController.abort();
    openController = new AbortController();
    renderShell(f);
    fetchAvailability(f, openController.signal);
  });
}

function renderEmpty() {
  const summaryEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-summary`);
  const stripEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-strip`);
  if (!summaryEl || !stripEl) return;
  summaryEl.textContent = 'No availability data — try Reserve on rec.gov directly';
  stripEl.style.display = 'none';
}

/** Drag-past-30% dismissal. Drag handle only — body of drawer scrolls normally. */
function attachDragHandlers(root) {
  const handle = root.querySelector('.cg-drawer-handle');
  if (!handle || handle.dataset.wired) return;
  handle.dataset.wired = '1';

  let startY = null;
  let startH = null;

  handle.addEventListener('touchstart', (e) => {
    startY = e.touches[0].clientY;
    startH = root.getBoundingClientRect().height;
  }, { passive: true });

  handle.addEventListener('touchmove', (e) => {
    if (startY == null) return;
    const dy = e.touches[0].clientY - startY;
    if (dy > 0) {
      // dragging down
      const newH = Math.max(0, startH - dy);
      root.style.height = `${newH}px`;
    } else {
      // dragging up — snap to full
      root.classList.add('full');
    }
  }, { passive: true });

  handle.addEventListener('touchend', (e) => {
    if (startY == null) return;
    const dy = e.changedTouches[0].clientY - startY;
    const dragged = (dy / startH) * 100;
    root.style.height = ''; // restore to CSS-driven height
    if (dragged > 30) {
      close();
    } else if (dy < -50) {
      root.classList.add('full');
    } else {
      root.classList.remove('full');
    }
    startY = null;
    startH = null;
  });
}
