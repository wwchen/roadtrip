// Campsite availability drawer for campground pins (RFC 0003 + 0007).
//
// Above-the-fold composition (mobile half, ~310px headroom inside ~480px):
//   campground name → park/state subline → verdict pill → 7-day grid +
//   day-detail panel (mounted from availability-week.js) → action row
//   (Directions + View on rec.gov) → "More details" accordion.
//
// Watch capture lives in the day-detail panel — there's no top-level
// "Watch" CTA. The reserve link is intentionally neutral ("View on
// rec.gov") because our availability is more permissive than the actual
// booking flow; routing user intent through watches avoids implying a
// guarantee we can't keep.

import { state, distanceKm, formatDistance, escapeHtml, flattenHydratedPoi } from '../core.js';
import {
  parseActivities,
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
import { descriptionSectionHTML, upstreamDecorations } from '../upstream-html.js';
import {
  DRAWER_ROOT_ID,
  ensureDrawerDOM,
  beginSession,
  isActiveFeature,
  show,
  closeDrawer,
  attachDragHandlers,
} from './chrome.js';
import {
  directionsButtonHTML,
  updatePoiAddressUrl,
  reviveJsonProp,
  upstreamHTML,
} from './shared.js';
import { requestPoiDetail } from '../api/poi-api.js';

// Tracks the currently mounted week component so we can dispose it on
// re-render (pin-reselect, hydration completing, retry). Disposal kills
// pending skeleton timers; in-flight fetches are killed via the drawer's
// abort signal already.
let mountedWeek = null;

/**
 * Campground-specific drawer. Renders availability for pins the backend
 * marks as supported and skips it for everything else.
 */
export function openCampgroundDrawer(f) {
  ensureDrawerDOM();
  updatePoiAddressUrl(f);
  const root = document.getElementById(DRAWER_ROOT_ID);

  // MapLibre's GeoJSON source serializes nested-object properties to JSON
  // strings when features round-trip through queryRenderedFeatures. Parse
  // the nested ones we actually read here.
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
    renderShell(f, signal);
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
    const r = await requestPoiDetail(f.id, { signal });
    if (!r.ok) {
      // /api/pois/{id} 404 / 5xx — render whatever we already have rather
      // than blocking the user. The week component still tries to fetch
      // availability; it'll show its own error state if the row is bad.
      if (!isActiveFeature(f)) return;
      renderShell(f, signal);
      return;
    }
    detail = await r.json();
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (!isActiveFeature(f)) return;
    renderShell(f, signal);
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
  // Re-stake the hydrated feature as the active one so the week
  // component's isActiveFeature() guard passes against the new identity.
  // Same signal — beginSession aborts the prior controller, but that's our
  // own (already used) one, so no in-flight is lost.
  const availSignal = beginSession(hydrated);
  renderShell(hydrated, availSignal);
}

/** Render the static parts (name, subline, verdict, CTAs) from the feature. */
function renderShell(f, signal) {
  const p = f.properties;
  const [lng, lat] = f.geometry.coordinates;

  // Per-source auxiliary decorations from properties.upstream. Product
  // fields such as description/photo_url are promoted by ETL and rendered
  // from the POI DTO; raw is only used for secondary upstream details.
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
  const agency = typeof p.agency === 'string' ? p.agency.trim() : '';

  const amenities = parseAmenities(p);
  const activities = parseActivities(p);
  const cc = parseCellCoverage(p);
  const rr = parseRatingReviews(p);
  const pills = amenitiesPillsHTML([...amenities, ...activities]);
  const cellPills = cellCoveragePillsHTML(cc);
  const rating = ratingHTML(rr);
  const sitesTag = sitesTagHTML(p);
  const footer = lastVerifiedFooterHTML(p);
  const bookingSysFooter = bookingSystemFooterHTML(p);

  const verdict = seasonVerdictHTML(p.season, p.reservable);

  // Hero photo lands flush against the top edges when present. The backend
  // owns source-specific promotion into properties.photo_url.
  const heroUrl = p.photo_url;
  const hero = heroUrl
    ? `<div class="cg-hero" role="img" aria-label="${escapeHtml(p.name)}" style="background-image: url('${escapeHtml(heroUrl)}')"></div>`
    : '';

  // The backend owns provider capability detection.
  const hasAvailability = availabilitySupported(p);
  const availabilityMount = hasAvailability
    ? `<div class="cg-availability-mount"></div>`
    : '';

  const dirBtn = directionsButtonHTML({ name: p.name, lng, lat, kind: 'CG' });
  // reserveButtonHTML reads p.cta (backend-computed) plus the FE-only
  // Aspira-deeplink path; per-vendor URL precedence lives on the server.
  // Alert capture for any availability provider lives inside the week
  // component's day-detail panel — this top-level button is just the
  // "go look at the source" affordance.
  const actions = `
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
  // TODO: Decide whether raw-upstream auxiliary UI belongs in this feature;
  // this PR's first-class POI scope is description/photo_url only.
  const upstreamSection = upstreamHTML(p.upstream);
  const aboutSection = descriptionSectionHTML(p.description);

  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  content.innerHTML = `
    ${hero}
    <header class="cg-drawer-head">
      <h2>${escapeHtml(p.name)}</h2>
      ${agency ? `<div class="cg-agency-subtitle">${escapeHtml(agency)}</div>` : ''}
      ${sub ? `<div class="cg-sub">${escapeHtml(sub)}</div>` : ''}
      ${verdict ? `<div class="cg-verdict-row">${verdict}</div>` : ''}
    </header>

    ${actions}
    ${availabilityMount}
    ${aboutSection}
    ${decor.fees}
    ${decor.meta}
    ${detailsSection}
    ${upstreamSection}
  `;

  // Dispose the previous mount before swapping innerHTML left it
  // orphaned. Pin reselect re-enters renderShell on the same drawer DOM.
  mountedWeek?.dispose();
  mountedWeek = null;

}

function availabilitySupported(_p) {
  // The old per-POI reservables availability route was retired with the
  // canonical campsite catalog reset. Re-enable when the drawer reads
  // canonical campsite availability instead of RID streams.
  return false;
}
