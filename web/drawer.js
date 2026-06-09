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
  bookingSystemFooterHTML,
  seasonVerdictHTML,
  reserveButtonHTML,
} from './campground-card.js';
import { upstreamDecorations } from './upstream-html.js';
import { directionsButtonHTML } from './popups.js';

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

function normalizeAspira(f) {
  const a = f.properties?.aspira;
  if (typeof a !== 'string') return f;
  // MapLibre wraps features so geometry/id are accessor properties — `{...f}`
  // would silently drop them. Mutate the properties bag in place; the
  // feature is per-click ephemeral, so this won't leak state into the source.
  let parsed = null;
  try { parsed = JSON.parse(a); } catch {}
  f.properties.aspira = parsed;
  return f;
}

// MapLibre stringifies nested-object properties on the way out of a GeoJSON
// source. Parse the ones the drawer reads; primitives survive untouched.
export function reviveJsonProp(p, key) {
  const v = p?.[key];
  if (typeof v !== 'string') return;
  try { p[key] = JSON.parse(v); } catch { p[key] = null; }
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
  // MapLibre's GeoJSON source serializes nested-object properties to JSON
  // strings when features round-trip through queryRenderedFeatures. Parse
  // the nested ones we actually read here. The legacy flat `aspira` field
  // is still used by campground-card.js (booking-system label, reserve URL)
  // — it's not the availability dispatch path.
  f = normalizeAspira(f);
  reviveJsonProp(f.properties, 'upstream');
  reviveJsonProp(f.properties, 'provider_ref');
  activeFeature = f;

  renderShell(f);
  show();
  // Backend dispatches by provider_ref on its end; the FE just hands over the
  // poi id (f.id, the pois.id PK). Skip the fetch for features without an id —
  // those are synthetic / not from /api/pois (the existing drawer can be
  // opened with a hand-crafted feature in some test paths).
  if (f.id != null) {
    fetchAvailability(f, openController.signal);
  }

  root.querySelector('.cg-drawer-close')?.addEventListener('click', close);
  attachDragHandlers(root);
}

/** Public close. Safe to call when the drawer isn't open. */
export function closeDrawer() {
  if (openController) {
    openController.abort();
    openController = null;
  }
  activeFeature = null;
  const root = document.getElementById(DRAWER_ROOT_ID);
  const backdrop = document.getElementById(BACKDROP_ID);
  if (!root?.classList.contains('open') && !backdrop?.classList.contains('open')) return;
  root?.classList.remove('open', 'full');
  backdrop?.classList.remove('open');
  setTimeout(() => {
    if (root) root.style.display = 'none';
    if (backdrop) backdrop.style.display = 'none';
  }, 220);
}

const close = closeDrawer;

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

  // Backdrop is intentionally pointer-events:none — MapLibre still needs to
  // receive pan/zoom gestures even with the drawer open. Instead, listen
  // for a map click that misses every interactive POI layer and treat it
  // as "click outside POI → close drawer." Layer-specific click handlers
  // run before this catch-all because we use queryRenderedFeatures to test.
  if (state.map) {
    const POI_LAYERS = ['cg-points-hit', 'sc-points-hit', 'pf-points-hit', 'np-pts-hit', 'sp-pts-hit', 'np-fill', 'sp-fill'];
    state.map.on('click', (e) => {
      if (!root.classList.contains('open')) return;
      const present = POI_LAYERS.filter(id => state.map.getLayer(id));
      if (!present.length) { closeDrawer(); return; }
      const hits = state.map.queryRenderedFeatures(e.point, { layers: present });
      if (!hits.length) closeDrawer();
    });
  }

  // Delegated handler for the per-POI Directions button. Bound once at DOM
  // creation so it survives the innerHTML rewrites in renderShell/openDrawer.
  // Routes through window.__rtAddTripStop (set up by topbar.js) so the
  // drawer doesn't depend on the topbar module.
  root.querySelector('.cg-drawer-content').addEventListener('click', (e) => {
    const btn = e.target.closest?.('.rt-poi-directions');
    if (!btn) return;
    e.preventDefault();
    const lng = Number(btn.dataset.lng);
    const lat = Number(btn.dataset.lat);
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return;
    if (typeof window.__rtAddTripStop !== 'function') return;
    window.__rtAddTripStop({
      name: btn.dataset.name || 'Selected place',
      lng, lat,
      kind: btn.dataset.kind || 'PLACE',
    });
    closeDrawer();
  });

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

  // Per-source decorations from properties.upstream — RIDB ships rich
  // MEDIA / FacilityDescription / fees / parent-park name that other ETLs
  // don't carry. Each section is empty string when absent so the drawer
  // renders sparse for sources that don't have them.
  const decor = upstreamDecorations(p.upstream);

  // Subline: parent park (RIDB RECAREA[0].RecAreaName when present, else
  // legacy parent_name / typeLabel) → region → distance.
  const parent = decor.parentName || p.parent_name || p.typeLabel || '';
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
  const bookingSysFooter = bookingSystemFooterHTML(p);

  const verdict = seasonVerdictHTML(p.season, p.reservable);

  // Hero photo lands flush against the top edges when present. Prefer the
  // RIDB MEDIA hero (Primary → Preview → first), fall back to legacy
  // p.photo_url. Falls back to extra header padding when neither (drawer-
  // head's first-child rule).
  const heroUrl = decor.heroUrl || p.photo_url;
  const hero = heroUrl
    ? `<div class="cg-hero" role="img" aria-label="${escapeHtml(p.name)}" style="background-image: url('${escapeHtml(heroUrl)}')"></div>`
    : '';

  // Pins that have an availability provider (rec.gov or Aspira NextGen) get
  // the availability-first treatment: heat-strip, watch CTA, reserve as
  // secondary. Detected via provider_ref (set on the row when an aspira or
  // recgov ETL imported it); the legacy recgov_id / aspira flat fields are
  // still on the feature for FE-only deeplinks below.
  const pr = p.provider_ref;
  const hasAvailability = !!(pr && (pr.recgov_id || pr.mapId != null));
  const availabilitySection = hasAvailability
    ? `
      <section class="cg-availability" aria-live="polite">
        <div class="cg-summary">Checking availability…</div>
        <div class="cg-freshness">&nbsp;</div>
        <div class="cg-strip" aria-hidden="true">
          ${'<div class="cg-cell skeleton"></div>'.repeat(30)}
        </div>
        <div class="cg-day-labels">
          <span class="today">Today</span>
          <span class="end"></span>
        </div>
        <div class="cg-legend" aria-hidden="true">
          <span><span class="cg-legend-dot cg-cell-available"></span>Available</span>
          <span><span class="cg-legend-dot cg-cell-partial"></span>Some sites</span>
          <span><span class="cg-legend-dot cg-cell-booked"></span>Booked</span>
        </div>
      </section>`
    : '';

  const dirBtn = directionsButtonHTML({ name: p.name, lng, lat, kind: 'CG' });
  const actions = p.recgov_id
    ? `
      <div class="cg-actions">
        ${dirBtn}
        <a class="cg-btn cg-btn-primary" href="/campsite?campground=${encodeURIComponent(p.recgov_id)}" data-cta="watch">Watch for openings</a>
        <a class="cg-btn cg-btn-secondary" href="https://www.recreation.gov/camping/campgrounds/${encodeURIComponent(p.recgov_id)}" target="_blank" rel="noreferrer" data-cta="reserve">Reserve on rec.gov</a>
      </div>`
    : `
      <div class="cg-actions">
        ${dirBtn}
        ${reserveButtonHTML(p, 'cg-btn')}
      </div>`;

  const detailsBody = [pills, cellPills, rating,
    sitesTag ? `<div class="cg-sites">${sitesTag}</div>` : '',
    bookingSysFooter,
    footer].filter(Boolean).join('');
  // Desktop has the room — open by default. Mobile keeps the accordion
  // collapsed so the heat-strip + CTAs stay above the fold.
  const isDesktop = typeof window !== 'undefined' && window.matchMedia?.('(min-width: 768px)').matches;
  const detailsSection = detailsBody
    ? `<details class="cg-details"${isDesktop ? ' open' : ''}>
         <summary>More details</summary>
         ${detailsBody}
       </details>`
    : '';

  // Raw upstream payload (whatever the ETL didn't promote). Flat key/value
  // table for top-level fields, nested objects/arrays as collapsed JSON.
  // Always collapsed by default — this is a "what's available" surface,
  // not the primary read.
  const upstreamSection = upstreamHTML(p.upstream);

  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  content.innerHTML = `
    ${hero}
    <header class="cg-drawer-head">
      <h2>${escapeHtml(p.name)}</h2>
      ${sub ? `<div class="cg-sub">${escapeHtml(sub)}</div>` : ''}
      ${verdict ? `<div class="cg-verdict-row">${verdict}</div>` : ''}
    </header>

    ${availabilitySection}
    ${actions}
    ${decor.about}
    ${decor.fees}
    ${decor.meta}
    ${detailsSection}
    ${upstreamSection}
  `;
}

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

async function fetchAvailability(f, signal) {
  // Single dispatch endpoint keyed by pois.id. Backend reads provider_ref
  // and routes to rec.gov or Aspira NextGen; response shape is the same
  // either way. See CampsiteAvailabilityRoutes.kt.
  const url = `/api/campsite/availability/${encodeURIComponent(f.id)}?days=30`;
  await runFetch(url, f, signal);
}

async function runFetch(url, f, signal) {
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

    renderState(json, f);
  } catch (e) {
    if (e.name === 'AbortError') return;
    renderError('network', 30, f);
  }
}

/** Render success / zero_available / closed_for_season / empty. */
function renderState(json, f) {
  const summaryEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-summary`);
  const freshEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-freshness`);
  const stripEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-strip`);
  const labelEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-day-labels span:last-child`);
  const primaryBtn = document.querySelector(`#${DRAWER_ROOT_ID} .cg-btn-primary`);
  // Watch / Snipe relabels are rec.gov-only — they point at /campsite, our
  // openings tracker, which only knows recgov_ids. For Aspira pins the
  // primary button is the upstream Reserve link; relabeling it to "Watch
  // for openings" would just lie about where the click goes.
  const isRecgov = !!f?.properties?.recgov_id;

  summaryEl.textContent = json.summary || '';
  const ageMin = Math.max(1, Math.round((json.cache?.age_seconds ?? 0) / 60));
  const stale = ageMin >= 10;
  freshEl.innerHTML = stale
    ? `<span class="cg-stale">checked ${ageMin}m ago · <a href="#" class="cg-refresh">refresh</a></span>`
    : `<span>checked ${ageMin}m ago</span>`;

  if (json.state === 'closed_for_season') {
    // Replace strip with a banner.
    stripEl.outerHTML = `<div class="cg-closed-banner">⛰️ ${json.season?.reopens_on ? 'Reopens ' + json.season.reopens_on : 'Closed for season'}</div>`;
    if (isRecgov) primaryBtn.textContent = 'Watch for opening day';
    if (labelEl) labelEl.textContent = '';
    return;
  }

  if (json.state === 'empty') {
    summaryEl.textContent = 'No availability data — try the Reserve link';
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

  if (isRecgov) {
    if (json.state === 'zero_available') {
      primaryBtn.textContent = 'Snipe a cancellation';
    } else {
      primaryBtn.textContent = 'Watch for openings';
    }
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
    if (f.id != null) fetchAvailability(f, openController.signal);
  });
}

function renderEmpty() {
  const summaryEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-summary`);
  const stripEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-strip`);
  if (!summaryEl || !stripEl) return;
  summaryEl.textContent = 'No availability data — try Reserve on rec.gov directly';
  stripEl.style.display = 'none';
}

/** Drag-past-30% dismissal.
 *
 * Two start regions:
 *   - the handle (always; widely-spaced 40px-tall hitbox via CSS),
 *   - the drawer body, but ONLY when scrolled to the top. Once the user has
 *     scrolled the drawer content, vertical touches are scroll, not dismiss.
 *
 * This is the standard iOS bottom-sheet pattern: small-handle gesture for
 * deliberate dismiss, body-gesture as a discoverability shortcut.
 */
function attachDragHandlers(root) {
  if (root.dataset.dragWired) return;
  root.dataset.dragWired = '1';

  const handle = root.querySelector('.cg-drawer-handle');

  let startY = 0;
  let startH = 0;
  // 'pending' = touch started but we haven't decided drag-vs-scroll yet
  // 'handle'  = drag began on the grab bar; always tracks
  // 'body'    = drag began in the body and exceeded the slop threshold
  //             downward while at scrollTop=0 — we own the gesture
  let phase = null;
  // Pixels the user must travel before a body touch becomes a drag. Below
  // this, we let the browser interpret the touch as a tap or scroll.
  const SLOP = 8;

  // Records whether the touch began on an interactive element (link,
  // button, input). We can't reject those at touchstart — the user
  // needs to be able to drag-from-anywhere — but if motion stays below
  // the slop threshold we let the tap through unimpeded.
  let startedOnInteractive = false;

  function onStart(e, originatedAtHandle) {
    if (e.touches.length !== 1) return;
    startY = e.touches[0].clientY;
    startH = root.getBoundingClientRect().height;
    startedOnInteractive =
      !originatedAtHandle && !!e.target.closest('a, button, input, select, textarea');
    phase = originatedAtHandle ? 'handle' : 'pending';
  }

  function onMove(e) {
    if (phase == null) return;
    const dy = e.touches[0].clientY - startY;

    // Pending body touch → decide whether this is a drag or a scroll.
    if (phase === 'pending') {
      if (dy > SLOP) {
        // Body drag exceeded slop. If content is scrolled mid-drawer,
        // hand back to the native scroll. Otherwise we own the gesture
        // even when it started over a link or button — the user is
        // clearly swiping, not tapping.
        if (root.scrollTop > 0) {
          phase = null;
          return;
        }
        phase = 'body';
      } else if (dy < -SLOP || root.scrollTop > 0) {
        // User wants to scroll; release the gesture entirely.
        phase = null;
        return;
      } else {
        return; // not enough motion yet — let any tap through
      }
    }

    if (phase === 'body' && dy < 0) {
      // Drag flipped upward; hand back to native scroll.
      root.style.height = '';
      phase = null;
      return;
    }
    // Active drag — claim the gesture so iOS doesn't rubber-band the body.
    if (e.cancelable) e.preventDefault();
    if (dy > 0) {
      const newH = Math.max(0, startH - dy);
      root.style.height = `${newH}px`;
    } else if (phase === 'handle') {
      root.classList.add('full');
    }
  }

  function onEnd(e) {
    if (phase == null) return;
    const dy = e.changedTouches[0].clientY - startY;
    const dragged = (dy / startH) * 100;
    root.style.height = '';
    if (phase !== 'pending') {
      if (dragged > 30) {
        close();
      } else if (phase === 'handle' && dy < -50) {
        root.classList.add('full');
      } else if (phase === 'handle') {
        root.classList.remove('full');
      }
    }
    phase = null;
  }

  if (handle) {
    handle.addEventListener('touchstart', (e) => onStart(e, true), { passive: true });
  }
  root.addEventListener('touchstart', (e) => {
    if (handle && handle.contains(e.target)) return;
    onStart(e, false);
  }, { passive: true });
  // Non-passive: we need preventDefault to claim the gesture from iOS scroll.
  root.addEventListener('touchmove', onMove, { passive: false });
  root.addEventListener('touchend', onEnd);
  root.addEventListener('touchcancel', onEnd);
}
