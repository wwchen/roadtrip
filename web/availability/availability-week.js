// Availability table component. Mounts inside the campground drawer and owns:
//
//   - the visible week start (LocalDate),
//   - the selected day,
//   - the in-flight controller (skeleton timer, AbortSignal),
//   - the cached list of the user's active watches (for badges).
//
// Render is split into pure modules:
//   - day-detail.js         — selected-day panel + alert / reserve CTAs.
//   - site-list.js          — all-site catalog or selected-day availability.
//   - site-matrix.js        — reservable rows crossed with visible dates.
//
// The drawer chrome (chrome.js) supplies the AbortSignal and active-feature
// guard; see openCampgroundDrawer in drawer/campground.js.

import { escapeHtml } from '../core.js';
import { requestPoiReservablesAvailability } from '../api/availability-api.js';
import { fetchPoiReservables } from '../api/reservable-api.js';
import { createWatch, deleteWatch, listWatches } from '../api/watches-api.js';
import { renderDayDetail } from './day-detail.js';
import { renderSiteMatrix, renderSiteMatrixSkeleton } from './site-matrix.js';
import { renderSiteList } from './site-list.js';
import { reservationUrlFromTemplate } from './booking-links.js';
import { mountCalendarPopover } from './calendar-popover.js';
import { availableCount } from './day-fields.js';
import { availabilityStatusLabel } from '../utils/availability-status.js';
import { addLocalDays, localToday, localYmd, parseLocalYmd, sameLocalDay } from '../utils/local-date.js';

const STORAGE_KEY_SITE_COLUMN_WIDTH = 'cg.siteMatrix.siteColumnWidth';
const DEFAULT_SITE_COLUMN_WIDTH = 128;
const LEGACY_DEFAULT_SITE_COLUMN_WIDTH = 178;
const MIN_SITE_COLUMN_WIDTH = 88;
const MAX_SITE_COLUMN_WIDTH = 270;
const WEEK_DAYS = 7;
const SKELETON_RENDER_DELAY_MS = 150;
const STALE_THRESHOLD_MIN = 10;
const CALENDAR_MAX_DAYS_OUT = 365;

/**
 * Mount the availability table into the host element. Returns a controller with a
 * `dispose()` method the drawer should call on close. In-flight fetches are
 * killed via the drawer's AbortSignal already.
 *
 * @param {HTMLElement} host
 * @param {object}      feature   POI feature (used for poi id, recgov_id, name).
 * @param {object}      [opts]
 * @param {AbortSignal} [opts.signal]
 */
export function mountAvailabilityWeek(host, feature, { signal } = {}) {
  const ctx = makeContext(host, feature, signal);

  rerender(ctx);
  wireRoot(ctx);
  const onResize = () => applyMatrixViewportWidth(ctx);
  window.addEventListener('resize', onResize);
  fetchWeek(ctx);
  fetchWatches(ctx);
  fetchSites(ctx);

  return {
    dispose() {
      endSiteColumnResize(ctx);
      clearTimeout(ctx.skeletonTimer);
      ctx.calendar?.dispose();
      ctx.calendar = null;
      window.removeEventListener('resize', onResize);
    },
  };
}

// ---- context --------------------------------------------------------------

function makeContext(host, feature, signal) {
  const earliestDate = featureEarliestDate(feature);
  return {
    host,
    feature,
    poiId: feature.id,
    signal,
    earliestDate,
    weekStart: earliestDate,
    siteColumnWidth: loadSiteColumnWidth(),
    selectedDate: null,
    state: 'loading', // 'loading' | 'success' | 'empty' | 'closed_for_season' | 'error'
    days: null,
    weekRequestSeq: 0,
    matrixFilters: {
      query: '',
      loop: '',
      type: '',
      sort: 'available',
    },
    selectedSiteRid: null,
    selectedSiteDate: null,
    armedBook: null,
    cacheBlock: null,
    season: null,
    error: null,
    watchesByWindow: new Map(),
    skeletonTimer: null,
    // Catalog (RFC 0008): the per-POI reservable list the BE serves at
    // /api/poi/{id}/reservables. When a day is selected, the week response's
    // available_reservable_ids filters this list to the sites available for
    // that date. The two fetches run in parallel.
    sitesState: 'loading',
    sites: [],
    sitesError: null,
    sitesExpanded: false,
    sitesRequestSeq: 0,
  };
}

// ---- render ---------------------------------------------------------------

function rerender(ctx) {
  const matrixScroll = captureMatrixScroll(ctx);
  ctx.calendar?.dispose();
  ctx.calendar = null;
  ctx.host.innerHTML = renderShell(ctx);
  applyMatrixViewportWidth(ctx);
  restoreMatrixScroll(ctx, matrixScroll);
}

function renderShell(ctx) {
  const selectedDay = selectedAvailabilityDay(ctx);
  const sitesDay = selectedDay && availableCount(selectedDay) > 0 ? selectedDay : null;
  return `
    <section class="cg-availability">
      ${renderAvailabilitySurface(ctx)}
      <div class="cg-freshness">${renderFreshness(ctx)}</div>
      ${renderDetail(ctx)}
      ${renderSiteList({
        state: ctx.sitesState,
        reservables: ctx.sites,
        error: ctx.sitesError,
        expanded: ctx.sitesExpanded,
        selectedDay: sitesDay,
        selectedEndDate: sitesDay ? stayEndDate(ctx, sitesDay.date) : null,
      })}
    </section>
  `;
}

function renderBody(ctx) {
  if (ctx.state === 'loading') {
    return renderSiteMatrixSkeleton({
      days: placeholderMatrixDays(ctx),
      siteColumnWidth: ctx.siteColumnWidth,
      weekStart: localYmd(ctx.weekStart),
      showToday: shouldShowEarliestButton(ctx),
    });
  }
  if (ctx.state === 'error') {
    return `<div class="cg-summary"><span class="cg-error">${escapeHtml(ctx.error || "Couldn't load availability")}</span> · <a href="#" class="cg-retry">Retry</a></div>`;
  }
  if (ctx.state === 'empty') {
    return '<div class="cg-closed-banner">No availability data for this campground.</div>';
  }
  if (ctx.state === 'closed_for_season') {
    const reopens = ctx.season?.reopens_on;
    const msg = reopens ? `Reopens ${reopens}` : 'Closed for season';
    return `<div class="cg-closed-banner">⛰️ ${escapeHtml(msg)}</div>`;
  }
  return renderSiteMatrixSkeleton({
    days: ctx.days,
    siteColumnWidth: ctx.siteColumnWidth,
    weekStart: localYmd(ctx.weekStart),
    showToday: shouldShowEarliestButton(ctx),
  });
}

function renderAvailabilitySurface(ctx) {
  if (ctx.state !== 'success') return renderBody(ctx);
  return renderSiteMatrix({
    state: ctx.sitesState,
    reservables: ctx.sites,
    days: Array.isArray(ctx.days) ? ctx.days : [],
    error: ctx.sitesError,
    selectedDate: null,
    siteColumnWidth: ctx.siteColumnWidth,
    filters: ctx.matrixFilters,
    selectedSiteRid: ctx.selectedSiteRid,
    weekStart: localYmd(ctx.weekStart),
    showToday: shouldShowEarliestButton(ctx),
    armedBook: ctx.armedBook,
  });
}

function renderFreshness(ctx) {
  if (ctx.state !== 'success' || !ctx.cacheBlock) return '&nbsp;';
  const ageMin = Math.max(1, Math.round((ctx.cacheBlock.age_seconds ?? 0) / 60));
  const stale = ageMin >= STALE_THRESHOLD_MIN;
  const stalePart = stale ? ' class="cg-stale"' : '';
  return `<span${stalePart}>checked ${ageMin}m ago · <a href="#" class="cg-refresh">refresh</a></span>`;
}

function renderDetail(ctx) {
  const day = selectedAvailabilityDay(ctx);
  if (!day || availableCount(day) > 0) return '';
  return renderDayDetail({
    day,
    watching: ctx.watchesByWindow.has(watchWindowKey(day.date, stayEndDate(ctx, day.date))),
    canWatch: ctx.poiId != null,
  });
}

function selectedAvailabilityDay(ctx) {
  if (ctx.state === 'loading' || ctx.state === 'error' || ctx.state === 'empty' || ctx.state === 'closed_for_season') {
    return null;
  }
  const days = Array.isArray(ctx.days) ? ctx.days : [];
  if (!ctx.selectedDate || days.length === 0) return null;
  return days.find((d) => d.date === ctx.selectedDate) || null;
}

// ---- event wiring ---------------------------------------------------------

function wireRoot(ctx) {
  ctx.host.addEventListener('click', (e) => onRootClick(ctx, e));
  ctx.host.addEventListener('input', (e) => onRootInput(ctx, e));
  ctx.host.addEventListener('change', (e) => onRootChange(ctx, e));
  ctx.host.addEventListener('pointerdown', (e) => onRootPointerDown(ctx, e));
  ctx.host.addEventListener('touchstart', (e) => onRootTouchStart(ctx, e), { passive: true });
  ctx.host.addEventListener('scroll', (e) => onRootScroll(ctx, e), true);
}

function onRootPointerDown(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;
  const bookBtn = tgt.closest('[data-book-rid]');
  if (bookBtn) {
    captureBookTapScroll(ctx, e.pointerType === 'touch');
  }
  const siteColumnResizer = tgt.closest('[data-site-column-resizer]');
  if (siteColumnResizer) {
    beginSiteColumnResize(ctx, e, siteColumnResizer);
  }
}

function onRootTouchStart(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;
  if (tgt.closest('[data-book-rid]')) {
    captureBookTapScroll(ctx, true);
  }
}

function captureBookTapScroll(ctx, isTouch) {
  const snapshot = captureMatrixScroll(ctx);
  if (!snapshot) return;
  ctx.pendingBookTapScroll = snapshot;
  ctx.pendingBookTapWasTouch = !!isTouch;
}

function onRootClick(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;

  const bookBtn = tgt.closest('[data-book-rid]');
  if (bookBtn) {
    e.preventDefault();
    const tapScroll = ctx.pendingBookTapScroll || captureMatrixScroll(ctx);
    const tapWasTouch = !!ctx.pendingBookTapWasTouch;
    ctx.pendingBookTapScroll = null;
    ctx.pendingBookTapWasTouch = false;
    const rid = bookBtn.getAttribute('data-book-rid');
    const date = bookBtn.getAttribute('data-book-date');
    if (!rid || !date) return;
    const armed = ctx.armedBook && String(ctx.armedBook.rid) === String(rid) && ctx.armedBook.date === date;
    const site = ctx.sites.find((s) => String(s.rid) === String(rid));
    if (armed) {
      const url = site
        ? reservationUrlFromTemplate(site, { startDate: date, endDate: stayEndDate(ctx, date) })
        : '';
      if (url) window.open(url, '_blank', 'noreferrer');
      ctx.armedBook = null;
      updateBookButtonState(bookBtn, site, date, false);
    } else {
      disarmBookButtonsInPlace(ctx);
      ctx.armedBook = { rid: String(rid), date };
      updateBookButtonState(bookBtn, site, date, true);
    }
    if (tapWasTouch) {
      bookBtn.blur?.();
    }
    restoreMatrixScrollAfterTap(ctx, tapScroll);
    return;
  }

  const wasArmed = ctx.armedBook != null;
  if (wasArmed) ctx.armedBook = null;

  const matrixDateBtn = tgt.closest('[data-matrix-date], .cg-day[data-date]');
  if (matrixDateBtn) {
    const date = matrixDateBtn.getAttribute('data-matrix-date') || matrixDateBtn.getAttribute('data-date');
    if (!date) return;
    const selected = ctx.selectedDate !== date;
    ctx.selectedDate = selected ? date : null;
    ctx.sitesExpanded = selected;
    rerender(ctx);
    return;
  }
  const siteHeaderBtn = tgt.closest('[data-site-header-rid]');
  if (siteHeaderBtn) {
    const rid = siteHeaderBtn.getAttribute('data-site-header-rid');
    if (!rid) return;
    ctx.selectedSiteRid = String(ctx.selectedSiteRid) === String(rid) ? null : rid;
    ctx.selectedSiteDate = null;
    rerender(ctx);
    return;
  }
  if (tgt.closest('.cg-week-prev')) {
    e.preventDefault();
    shiftWeek(ctx, -WEEK_DAYS);
    return;
  }
  if (tgt.closest('.cg-week-next')) {
    e.preventDefault();
    shiftWeek(ctx, WEEK_DAYS);
    return;
  }
  if (tgt.closest('.cg-week-today')) {
    e.preventDefault();
    jumpMatrixToToday(ctx);
    return;
  }
  const weekLabel = tgt.closest('.cg-week-label');
  if (weekLabel) {
    e.preventDefault();
    e.stopPropagation();
    openCalendar(ctx, weekLabel);
    return;
  }
  if (tgt.closest('.cg-refresh')) {
    e.preventDefault();
    fetchWeek(ctx, { force: true });
    return;
  }
  if (tgt.closest('.cg-retry')) {
    e.preventDefault();
    fetchWeek(ctx);
    return;
  }
  const alertBtn = tgt.closest('.cg-day-alert');
  if (alertBtn) {
    e.preventDefault();
    toggleWatch(ctx, alertBtn);
    return;
  }
  const sitesToggle = tgt.closest('.cg-sites-toggle');
  if (sitesToggle && !sitesToggle.disabled) {
    ctx.sitesExpanded = !ctx.sitesExpanded;
    rerender(ctx);
    return;
  }
  if (tgt.closest('.cg-sites-retry')) {
    e.preventDefault();
    fetchSites(ctx);
    return;
  }

  if (wasArmed) disarmBookButtonsInPlace(ctx);
}

function onRootInput(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;
  const control = tgt.closest('[data-matrix-filter="query"]');
  if (!control || !('value' in control)) return;
  const cursor = typeof control.selectionStart === 'number' ? control.selectionStart : null;
  if (updateMatrixFilter(ctx, 'query', control.value)) {
    rerender(ctx);
    restoreMatrixFilterFocus(ctx, 'query', cursor);
  }
}

function onRootChange(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;
  const control = tgt.closest('[data-matrix-filter]');
  if (!control || !('value' in control)) return;
  const key = control.getAttribute('data-matrix-filter');
  if (key !== 'loop' && key !== 'type' && key !== 'sort') return;
  if (updateMatrixFilter(ctx, key, control.value)) {
    rerender(ctx);
    restoreMatrixFilterFocus(ctx, key);
  }
}

function updateMatrixFilter(ctx, key, value) {
  const current = ctx.matrixFilters || {};
  const nextValue = typeof value === 'string' ? value : '';
  if ((current[key] || '') === nextValue) return false;
  ctx.matrixFilters = {
    query: current.query || '',
    loop: current.loop || '',
    type: current.type || '',
    sort: current.sort || 'available',
    [key]: nextValue,
  };
  ctx.armedBook = null;
  return true;
}

function restoreMatrixFilterFocus(ctx, key, cursor = null) {
  window.requestAnimationFrame?.(() => {
    const control = ctx.host.querySelector(`[data-matrix-filter="${key}"]`);
    if (!(control instanceof HTMLElement)) return;
    control.focus({ preventScroll: true });
    if (typeof cursor === 'number' && 'setSelectionRange' in control) {
      control.setSelectionRange(cursor, cursor);
    }
  });
}

function onRootScroll(ctx, e) {
  const scroll = e.target;
  if (!(scroll instanceof HTMLElement)) return;
  if (!scroll.classList.contains('cg-site-matrix-scroll')) return;
  if (ctx.restoringMatrixScroll) return;
}

function disarmBookButtonsInPlace(ctx) {
  for (const button of ctx.host.querySelectorAll('.cg-site-matrix-cell-button.is-armed')) {
    if (!(button instanceof HTMLElement)) continue;
    const rid = button.getAttribute('data-book-rid');
    const date = button.getAttribute('data-book-date');
    const site = ctx.sites.find((s) => String(s.rid) === String(rid));
    updateBookButtonState(button, site, date, false);
  }
}

function updateBookButtonState(button, site, date, armed) {
  if (!(button instanceof HTMLElement) || !date) return;
  button.classList.toggle('is-armed', armed);
  button.textContent = armed ? 'Book' : availabilityStatusLabel('available');
  const label = siteLabel(site);
  const aria = `${label} ${date}: available`;
  button.setAttribute(
    'aria-label',
    armed
      ? `${aria}; Book, click to open booking page`
      : `${aria}; click to book`,
  );
}

function siteLabel(site) {
  if (!site) return 'Site';
  if (site.name) return site.name;
  if (site.vendor_id) return `Site #${site.vendor_id}`;
  return site.rid || 'Site';
}

const AVAIL_ERROR_LABELS = {
  rate_limited: 'Upstream rate-limited',
  upstream_blocked: 'Upstream blocked the request',
  upstream_5xx: 'Upstream unavailable',
  unsupported: 'Provider not supported',
  provider_misconfigured: 'Provider misconfigured',
  ip_throttled: 'Too many requests',
};

function formatAvailabilityError(json, httpStatus) {
  const code = typeof json?.error === 'string' ? json.error : null;
  const base = code ? AVAIL_ERROR_LABELS[code] || code : `HTTP ${httpStatus}`;
  if (typeof json?.upstream_status === 'number') {
    return `${base} (upstream HTTP ${json.upstream_status})`;
  }
  return base;
}

function beginSiteColumnResize(ctx, event, handle) {
  if (event.pointerId == null) return;
  event.preventDefault();
  event.stopPropagation();

  endSiteColumnResize(ctx);
  const scroll = handle.closest('.cg-site-matrix-scroll');
  const startWidth = ctx.siteColumnWidth || DEFAULT_SITE_COLUMN_WIDTH;
  ctx.siteColumnResize = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startWidth,
    scroll,
    onMove: null,
    onUp: null,
  };
  ctx.siteColumnResize.onMove = (moveEvent) => updateSiteColumnResize(ctx, moveEvent);
  ctx.siteColumnResize.onUp = () => endSiteColumnResize(ctx);
  try {
    handle.setPointerCapture?.(event.pointerId);
  } catch {
    // Synthetic tests may not have an active pointer to capture.
  }
  document.body.classList.add('cg-site-column-resizing');
  window.addEventListener('pointermove', ctx.siteColumnResize.onMove);
  window.addEventListener('pointerup', ctx.siteColumnResize.onUp, { once: true });
  window.addEventListener('pointercancel', ctx.siteColumnResize.onUp, { once: true });
}

function updateSiteColumnResize(ctx, event) {
  const active = ctx.siteColumnResize;
  if (!active) return;
  const nextWidth = clampSiteColumnWidth(active.startWidth + event.clientX - active.startX);
  ctx.siteColumnWidth = nextWidth;
  active.scroll?.style.setProperty('--cg-site-column-width', `${nextWidth}px`);
}

function endSiteColumnResize(ctx) {
  const active = ctx.siteColumnResize;
  if (!active) return;
  window.removeEventListener('pointermove', active.onMove);
  window.removeEventListener('pointerup', active.onUp);
  window.removeEventListener('pointercancel', active.onUp);
  document.body.classList.remove('cg-site-column-resizing');
  ctx.siteColumnResize = null;
  saveSiteColumnWidth(ctx.siteColumnWidth || DEFAULT_SITE_COLUMN_WIDTH);
}

function shiftWeek(ctx, days) {
  resetWeekViewState(ctx);
  const next = addLocalDays(ctx.weekStart, days);
  ctx.weekStart = next < ctx.earliestDate ? ctx.earliestDate : next;
  fetchWeek(ctx);
}

function openCalendar(ctx, anchorBtn) {
  ctx.calendar?.dispose();
  ctx.calendar = null;

  const popoverHost = document.createElement('div');
  popoverHost.className = 'cg-cal-host';
  anchorBtn.parentElement.appendChild(popoverHost);

  const today = ctx.earliestDate;
  ctx.calendar = mountCalendarPopover(popoverHost, {
    viewMonth: ctx.weekStart,
    today,
    selectedDate: ctx.weekStart,
    maxDate: addLocalDays(today, CALENDAR_MAX_DAYS_OUT),
    onPick: (date) => {
      ctx.calendar?.dispose();
      ctx.calendar = null;
      resetWeekViewState(ctx);
      ctx.weekStart = date;
      fetchWeek(ctx);
    },
    onClose: () => {
      ctx.calendar?.dispose();
      ctx.calendar = null;
      rerender(ctx);
    },
  });
}

function jumpMatrixToToday(ctx) {
  const today = ctx.earliestDate;
  resetWeekViewState(ctx);
  if (sameLocalDay(ctx.weekStart, today)) {
    rerender(ctx);
    return;
  }
  ctx.weekStart = today;
  fetchWeek(ctx);
}

function resetWeekViewState(ctx) {
  ctx.selectedDate = null;
  ctx.selectedSiteRid = null;
  ctx.selectedSiteDate = null;
  ctx.sitesExpanded = false;
  ctx.armedBook = null;
}

// ---- data -----------------------------------------------------------------

async function fetchWeek(ctx, { force = false } = {}) {
  const requestSeq = ++ctx.weekRequestSeq;
  ctx.state = 'loading';
  ctx.error = null;
  ctx.days = null;
  // Skeleton only flashes for slow fetches; cache hits feel instant.
  clearTimeout(ctx.skeletonTimer);
  ctx.skeletonTimer = setTimeout(() => {
    if (requestSeq === ctx.weekRequestSeq) rerender(ctx);
  }, SKELETON_RENDER_DELAY_MS);
  const startDate = localYmd(ctx.weekStart);
  const endDate = localYmd(addLocalDays(ctx.weekStart, WEEK_DAYS));
  try {
    const resp = await requestPoiReservablesAvailability(ctx.poiId, {
      startDate,
      endDate,
      force,
      signal: ctx.signal,
    });
    if (requestSeq !== ctx.weekRequestSeq) return;
    clearTimeout(ctx.skeletonTimer);
    if (!resp.ok) {
      const json = await resp.json().catch(() => null);
      ctx.state = 'error';
      ctx.error = formatAvailabilityError(json, resp.status);
      rerender(ctx);
      return;
    }
    const json = await resp.json();
    if (requestSeq !== ctx.weekRequestSeq) return;
    const fused = fusePoiReservablesAvailability(json, startDate, endDate);
    ctx.cacheBlock = fused.cacheBlock;
    if (fused.state === 'empty') {
      ctx.state = 'empty';
      ctx.days = [];
    } else if (fused.state === 'closed_for_season') {
      ctx.state = 'closed_for_season';
      ctx.days = [];
      ctx.season = fused.season;
    } else {
      ctx.state = 'success';
      ctx.days = fused.days;
    }
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (requestSeq !== ctx.weekRequestSeq) return;
    clearTimeout(ctx.skeletonTimer);
    ctx.state = 'error';
    ctx.error = e.message || 'network';
    rerender(ctx);
  }
}

// Fuse the BE response (one envelope per reservable) into the per-day
// classifications the matrix renders. Server-side classification ran a single
// rollup over all reservables; the new endpoint hands us the streams and lets
// the FE decide how to combine them. Same rollup rules:
//   - status: available > first_come > unknown > reserved > closed > unknown
//   - reservable_statuses: { rid → status } for the matrix tooltip
//   - available_reservable_ids: rids that are bookable that day
//
// state shortcut:
//   - reservables: []          → 'empty' (POI has no online-bookable sites)
//   - every reservable closed_for_season → 'closed_for_season' (carry a
//     season block from the first reservable that has one)
//   - else                     → 'success'
function fusePoiReservablesAvailability(json, startDate, endDate) {
  const reservables = Array.isArray(json?.reservables) ? json.reservables : [];
  if (reservables.length === 0) {
    return {
      state: 'empty',
      days: [],
      season: null,
      cacheBlock: null,
    };
  }
  const closedForSeason = reservables.every((r) => r?.state === 'closed_for_season');
  if (closedForSeason) {
    const seasonHint = reservables.find((r) => r?.season && r.season.reopens_on)?.season ?? null;
    return {
      state: 'closed_for_season',
      days: [],
      season: seasonHint,
      cacheBlock: oldestCacheBlock(reservables),
    };
  }

  const dates = enumerateDates(startDate, endDate);
  const days = dates.map((date) => fuseDay(date, reservables));
  return {
    state: 'success',
    days,
    season: null,
    cacheBlock: oldestCacheBlock(reservables),
  };
}

function fuseDay(date, reservables) {
  // reservable_statuses: { rid → status } across all reservables for that date.
  const statuses = {};
  for (const r of reservables) {
    const rid = r?.reservable_id;
    if (!rid) continue;
    const day = (Array.isArray(r.availability) ? r.availability : []).find((d) => d?.date === date);
    statuses[rid] = day?.status || 'unknown';
  }
  const ridsSorted = Object.keys(statuses).sort();
  const orderedStatuses = {};
  for (const rid of ridsSorted) orderedStatuses[rid] = statuses[rid];

  const availableIds = ridsSorted.filter((rid) => statuses[rid] === 'available');
  const status = rollupStatus(ridsSorted.map((rid) => statuses[rid]));
  return {
    date,
    status,
    available_reservable_ids: availableIds,
    reservable_statuses: orderedStatuses,
  };
}

function rollupStatus(values) {
  if (values.length === 0) return 'unknown';
  if (values.includes('available')) return 'available';
  if (values.includes('first_come')) return 'first_come';
  if (values.includes('unknown')) return 'unknown';
  if (values.includes('reserved')) return 'reserved';
  if (values.every((v) => v === 'closed')) return 'closed';
  return 'unknown';
}

function oldestCacheBlock(reservables) {
  // The matrix shows one freshness pill. Pick the staleest age — that's the
  // honest answer when streams have different cache hit times.
  let chosen = null;
  for (const r of reservables) {
    const cb = r?.cache;
    if (!cb) continue;
    if (!chosen || (cb.age_seconds ?? 0) > (chosen.age_seconds ?? 0)) chosen = cb;
  }
  return chosen;
}

function enumerateDates(startDate, endDate) {
  const out = [];
  const end = parseLocalYmd(endDate);
  for (let cur = parseLocalYmd(startDate); cur < end; cur = addLocalDays(cur, 1)) {
    out.push(localYmd(cur));
  }
  return out;
}

async function fetchSites(ctx) {
  if (ctx.poiId == null) return;
  const requestSeq = ++ctx.sitesRequestSeq;
  ctx.sitesState = 'loading';
  ctx.sitesError = null;
  rerender(ctx);
  try {
    const json = await fetchPoiReservables(ctx.poiId, {
      signal: ctx.signal,
    });
    if (ctx.signal?.aborted) return;
    if (requestSeq !== ctx.sitesRequestSeq) return;
    ctx.sitesState = 'success';
    ctx.sites = Array.isArray(json?.reservables) ? json.reservables : [];
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (ctx.signal?.aborted) return;
    if (requestSeq !== ctx.sitesRequestSeq) return;
    ctx.sitesState = 'error';
    ctx.sitesError = e.message || 'network';
    rerender(ctx);
  }
}

async function fetchWatches(ctx) {
  if (ctx.poiId == null) return;
  try {
    const data = await listWatches({ status: 'active', poiId: ctx.poiId, signal: ctx.signal });
    if (ctx.signal?.aborted) return;
    ctx.watchesByWindow = indexWatchesByWindow(data?.watches, ctx.poiId);
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    // Non-fatal: badges just don't render.
    console.warn('watch list fetch failed', e);
  }
}

function indexWatchesByWindow(watches, poiId) {
  const out = new Map();
  if (!Array.isArray(watches)) return out;
  const id = String(poiId);
  for (const w of watches) {
    if (!w || w.status === 'done') continue;
    if (String(w.poi_id ?? '') !== id) continue;
    const start = w.start_date ?? w.startDate;
    const end = w.end_date ?? w.endDate;
    if (start && end) out.set(watchWindowKey(start, end), w);
  }
  return out;
}

async function toggleWatch(ctx, button) {
  const date = ctx.selectedDate;
  if (!date) return;
  const endDate = stayEndDate(ctx, date);
  const key = watchWindowKey(date, endDate);
  const watching = ctx.watchesByWindow.has(key);
  const previousLabel = button.textContent;
  button.disabled = true;
  try {
    if (watching) {
      const existing = ctx.watchesByWindow.get(key);
      button.textContent = 'Removing...';
      await deleteWatch(existing.id, { signal: ctx.signal });
      ctx.watchesByWindow.delete(key);
    } else {
      button.textContent = 'Creating watch...';
      const payload = buildWatchPayload(ctx, date, endDate);
      const created = await createWatch(payload, { signal: ctx.signal });
      ctx.watchesByWindow.set(key, created.watch || { ...payload, id: created.id });
    }
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    button.textContent = previousLabel;
    button.disabled = false;
    console.warn('watch toggle failed', e);
  }
}

function buildWatchPayload(ctx, date, endDate) {
  return {
    poi_id: Number(ctx.poiId),
    reservable_filters: {},
    start_date: date,
    end_date: endDate,
    cadence_sec: 60,
    trigger_kinds: ['availability'],
    trigger_config: {},
    stop_when_triggered: true,
  };
}

// ---- helpers --------------------------------------------------------------

function stayEndDate(ctx, startDate) {
  return localYmd(addLocalDays(parseLocalYmd(startDate), 1));
}

function watchWindowKey(startDate, endDate) {
  return `${startDate}|${endDate}`;
}

function loadSiteColumnWidth() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_SITE_COLUMN_WIDTH);
    const width = parseInt(raw, 10);
    if (Number.isFinite(width)) {
      if (width === LEGACY_DEFAULT_SITE_COLUMN_WIDTH) return DEFAULT_SITE_COLUMN_WIDTH;
      return clampSiteColumnWidth(width);
    }
  } catch {
    // Non-fatal: default silently.
  }
  return DEFAULT_SITE_COLUMN_WIDTH;
}

function saveSiteColumnWidth(width) {
  try {
    localStorage.setItem(STORAGE_KEY_SITE_COLUMN_WIDTH, String(clampSiteColumnWidth(width)));
  } catch {
    // Non-fatal: just won't persist.
  }
}

function applyMatrixViewportWidth(ctx) {
  const scroll = ctx.host.querySelector('.cg-site-matrix-scroll');
  if (!(scroll instanceof HTMLElement)) return;
  scroll.style.setProperty('--cg-site-matrix-viewport-width', `${scroll.clientWidth}px`);
}

function captureMatrixScroll(ctx) {
  const scroll = ctx.host.querySelector('.cg-site-matrix-scroll');
  if (!(scroll instanceof HTMLElement)) return null;
  return {
    left: scroll.scrollLeft,
    top: scroll.scrollTop,
  };
}

function restoreMatrixScroll(ctx, snapshot) {
  if (!snapshot) return;
  const scroll = ctx.host.querySelector('.cg-site-matrix-scroll');
  if (!(scroll instanceof HTMLElement)) return;
  const maxLeft = Math.max(0, scroll.scrollWidth - scroll.clientWidth);
  const maxTop = Math.max(0, scroll.scrollHeight - scroll.clientHeight);
  const left = Math.min(Math.max(0, snapshot.left || 0), maxLeft);
  const top = Math.min(Math.max(0, snapshot.top || 0), maxTop);
  if (scroll.scrollLeft === left && scroll.scrollTop === top) return;
  ctx.restoringMatrixScroll = true;
  scroll.scrollLeft = left;
  scroll.scrollTop = top;
  const clearRestoring = () => {
    ctx.restoringMatrixScroll = false;
  };
  if (typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(clearRestoring);
  } else {
    window.setTimeout(clearRestoring, 0);
  }
}

function restoreMatrixScrollAfterTap(ctx, snapshot) {
  if (!snapshot) return;
  restoreMatrixScroll(ctx, snapshot);
  const restore = () => restoreMatrixScroll(ctx, snapshot);
  if (typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(() => {
      restore();
      window.requestAnimationFrame(restore);
    });
  } else {
    window.setTimeout(restore, 0);
  }
}

function placeholderMatrixDays(ctx) {
  return Array.from({ length: WEEK_DAYS }, (_, i) => {
    const date = addLocalDays(ctx.weekStart, i);
    return { date: localYmd(date) };
  });
}

function featureEarliestDate(feature) {
  const raw = feature?.properties?.earliest_date ?? feature?.properties?.earliestDate;
  const parsed = parseLocalYmd(raw);
  return Number.isFinite(parsed.getTime()) ? parsed : localToday();
}

function shouldShowEarliestButton(ctx) {
  return !sameLocalDay(ctx.weekStart, ctx.earliestDate);
}

function clampSiteColumnWidth(width) {
  return Math.max(MIN_SITE_COLUMN_WIDTH, Math.min(MAX_SITE_COLUMN_WIDTH, Math.round(width)));
}
