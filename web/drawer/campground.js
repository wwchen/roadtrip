// Campsite availability drawer for US federal campground pins (RFC 0003).
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

import { state, distanceKm, formatDistance, escapeHtml, flattenHydratedPoi } from '../core.js';
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
} from '../campground-card.js';
import { upstreamDecorations } from '../upstream-html.js';
import {
  DRAWER_ROOT_ID,
  ensureDrawerDOM,
  beginSession,
  isActiveFeature,
  restartController,
  show,
  closeDrawer,
  attachDragHandlers,
} from './chrome.js';
import {
  directionsButtonHTML,
  reviveJsonProp,
  normalizeAspira,
  upstreamHTML,
} from './shared.js';

/**
 * Campground-specific drawer. Renders availability for recgov pins and
 * skips it for everything else.
 */
export function openCampgroundDrawer(f) {
  ensureDrawerDOM();
  const root = document.getElementById(DRAWER_ROOT_ID);

  // MapLibre's GeoJSON source serializes nested-object properties to JSON
  // strings when features round-trip through queryRenderedFeatures. Parse
  // the nested ones we actually read here. The legacy flat `aspira` field
  // is still used by campground-card.js (booking-system label, reserve URL)
  // — it's not the availability dispatch path.
  f = normalizeAspira(f);
  reviveJsonProp(f.properties, 'upstream');
  reviveJsonProp(f.properties, 'provider_ref');

  const signal = beginSession(f);

  show();
  root.querySelector('.cg-drawer-close')?.addEventListener('click', closeDrawer);
  attachDragHandlers(root);

  // Slim /api/pois doesn't ship the wide property set the campground shell
  // needs (name, region, photo_url, parent_name, recgov_id, raw upstream).
  // If we got a slim feature, render a loading placeholder, fetch the wide
  // row, and then re-render with availability. If the feature is already
  // wide (legacy paths / tests / map-click with full data), render
  // synchronously.
  if (isSlimFeature(f)) {
    renderLoadingShell();
    hydrateFromApi(f, signal);
  } else {
    renderShell(f);
    if (f.id != null) fetchAvailability(f, signal);
  }
}

/**
 * Slim features carry only category/subcategory in properties. Wide ones
 * have name/source/etc. Tell them apart so the drawer knows whether a
 * /api/pois/{id} round-trip is needed.
 */
function isSlimFeature(f) {
  const p = f?.properties;
  if (!p) return true;
  // Wide features always have a name. Slim ones never do.
  return p.name == null && p.source == null;
}

function renderLoadingShell() {
  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  if (!content) return;
  content.innerHTML = `
    <header class="cg-drawer-head">
      <h2 style="opacity:0.5">Loading…</h2>
    </header>
  `;
}

async function hydrateFromApi(f, signal) {
  if (f.id == null) return;
  let detail = null;
  try {
    const r = await fetch(`/api/pois/${encodeURIComponent(f.id)}`, { signal });
    if (!r.ok) {
      // /api/pois/{id} 404 / 5xx — render whatever we already have rather
      // than blocking the user, and let availability still try (some test
      // paths use a hand-crafted feature with no DB row).
      if (!isActiveFeature(f)) return;
      renderShell(f);
      fetchAvailability(f, signal);
      return;
    }
    detail = await r.json();
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (!isActiveFeature(f)) return;
    renderShell(f);
    return;
  }
  // The user clicked a different pin in the time the detail fetch took —
  // `signal` is aborted and `activeFeature` is something else. Bail.
  if (signal.aborted || !isActiveFeature(f)) return;

  // Merge detail.properties on top of what we had. Keep id + geometry from
  // the input feature (the slim shape's centroid coords match the wide
  // shape's geometry for points; for polygons the wide shape wins).
  const merged = {
    type: 'Feature',
    id: f.id,
    geometry: detail.geometry || f.geometry,
    properties: { ...(f.properties || {}), ...(detail.properties || {}) },
  };
  // Run the same revivers the synchronous path applies, then promote the
  // wide nested shape into the flat keys the renderShell + campground-card
  // helpers read (recgov_id, aspira, Unit_Nm, photo_url alias, etc.).
  merged.properties.upstream != null && reviveJsonProp(merged.properties, 'upstream');
  merged.properties.provider_ref != null && reviveJsonProp(merged.properties, 'provider_ref');
  const hydrated = flattenHydratedPoi(merged);
  // Re-stake the hydrated feature as the active one so the availability
  // fetch's isActiveFeature() check passes against the new identity. Same
  // signal — beginSession aborts the prior controller, but that's our own
  // (already used) one, so no in-flight is lost.
  const availSignal = beginSession(hydrated);
  renderShell(hydrated);
  fetchAvailability(hydrated, availSignal);
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
    if (!isActiveFeature(f)) return;

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
    const signal = restartController();
    renderShell(f);
    if (f.id != null) fetchAvailability(f, signal);
  });
}

function renderEmpty() {
  const summaryEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-summary`);
  const stripEl = document.querySelector(`#${DRAWER_ROOT_ID} .cg-strip`);
  if (!summaryEl || !stripEl) return;
  summaryEl.textContent = 'No availability data — try Reserve on rec.gov directly';
  stripEl.style.display = 'none';
}
