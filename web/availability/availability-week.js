// 7-day availability search component (RFC 0007). Mounts inside the
// campground drawer and owns:
//
//   - the visible week start (LocalDate),
//   - min_nights (default 1, persisted in localStorage),
//   - the selected day,
//   - the in-flight controller (skeleton timer, AbortSignal),
//   - the cached list of the user's existing alerts (for 🔔 badges).
//
// Render is split into pure modules:
//   - week-grid.js          — 7 day cells.
//   - day-detail.js         — selected-day panel + alert / reserve CTAs.
//   - site-list.js          — all-site catalog or selected-day availability.
//   - site-matrix.js        — reservable rows crossed with visible dates.
//   - calendar-popover.js   — month picker shown when the user clicks the
//                             week label (jump to any date).
//
// The drawer chrome (chrome.js) supplies the AbortSignal and active-feature
// guard; see openCampgroundDrawer in drawer/campground.js.

import { escapeHtml } from '../core.js';
import {
  createCampsiteAlert,
  deleteCampsiteAlert,
  listCampsiteAlerts,
} from '../api/campsite-alert-api.js';
import { requestCampsiteAvailability } from '../api/availability-api.js';
import { fetchPoiReservables } from '../api/reservable-api.js';
import { isActiveFeature } from '../drawer/chrome.js';
import { mountCalendarPopover } from './calendar-popover.js';
import { renderDayDetail } from './day-detail.js';
import { renderSiteMatrix } from './site-matrix.js';
import { renderSiteList } from './site-list.js';
import { renderWeekGrid, renderWeekSkeleton } from './week-grid.js';

const STORAGE_KEY_MIN_NIGHTS = 'cg.minNights';
const STORAGE_KEY_AVAILABILITY_VIEW = 'cg.availabilityView';
const STORAGE_KEY_SITE_COLUMN_WIDTH = 'cg.siteMatrix.siteColumnWidth';
const DEFAULT_MIN_NIGHTS = 1;
const DEFAULT_AVAILABILITY_VIEW = 'table';
const DEFAULT_SITE_COLUMN_WIDTH = 128;
const LEGACY_DEFAULT_SITE_COLUMN_WIDTH = 178;
const MIN_SITE_COLUMN_WIDTH = 88;
const MAX_SITE_COLUMN_WIDTH = 270;
const MATRIX_SCROLL_LOAD_THRESHOLD_PX = 140;
const MIN_NIGHTS_CHIPS = [1, 2, 3, 7];
const WEEK_DAYS = 7;
const SKELETON_RENDER_DELAY_MS = 150;
const STALE_THRESHOLD_MIN = 10;
const MS_PER_DAY = 24 * 60 * 60 * 1000;

// Provider-agnostic horizon used by the calendar popover to disable
// dates beyond what the upstream is likely to expose. The route also
// enforces this server-side based on the actual provider capabilities;
// the FE bound is informational. Six months matches rec.gov's window.
const CALENDAR_MAX_DAYS_OUT = 180;

/**
 * Mount the week grid into the host element. Returns a controller with a
 * `dispose()` method the drawer should call on close (clears any pending
 * skeleton timer, removes calendar listeners; in-flight fetches are killed
 * via the drawer's AbortSignal already).
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
  fetchWeek(ctx);
  fetchAlerts(ctx);
  fetchSites(ctx);

  return {
    dispose() {
      endSiteColumnResize(ctx);
      clearTimeout(ctx.skeletonTimer);
      ctx.calendar?.dispose();
      ctx.calendar = null;
    },
  };
}

// ---- context --------------------------------------------------------------

function makeContext(host, feature, signal) {
  const recgovId =
    feature.properties?.recgov_id ?? feature.properties?.provider_ref?.recgov_id ?? null;
  return {
    host,
    feature,
    poiId: feature.id,
    recgovId,
    signal,
    weekStart: startOfTodayUtc(),
    minNights: loadMinNights(),
    availabilityView: loadAvailabilityView(),
    siteColumnWidth: loadSiteColumnWidth(),
    selectedDate: null,
    state: 'loading', // 'loading' | 'success' | 'empty' | 'closed_for_season' | 'error'
    days: null,
    matrixDays: null,
    matrixLoading: false,
    matrixEnd: false,
    matrixError: null,
    matrixScrollLeft: 0,
    matrixRequestSeq: 0,
    cacheBlock: null,
    summary: '',
    season: null,
    error: null,
    alertsByDate: new Map(),
    skeletonTimer: null,
    calendar: null,
    // Catalog (RFC 0008): the per-POI reservable list the BE serves at
    // /api/poi/{id}/reservables. When a day is selected, the week response's
    // available_reservable_ids filters this list to the sites available for
    // that date. The two fetches run in parallel.
    sitesState: 'loading',
    sites: [],
    sitesTotal: null,
    sitesError: null,
    sitesExpanded: false,
    sitesRequestSeq: 0,
  };
}

// ---- render ---------------------------------------------------------------

function rerender(ctx) {
  ctx.host.innerHTML = renderShell(ctx);
  restoreMatrixScroll(ctx);
}

function renderShell(ctx) {
  const selectedDay = selectedAvailabilityDay(ctx);
  const sitesDay = selectedDay && availableCount(selectedDay) > 0 ? selectedDay : null;
  return `
    <section class="cg-availability">
      ${renderNightsRow(ctx)}
      ${ctx.availabilityView === 'week' ? renderWeekNav(ctx) : ''}
      ${renderAvailabilitySurface(ctx)}
      <div class="cg-freshness">${renderFreshness(ctx)}</div>
      ${renderDetail(ctx)}
      ${renderSiteList({
        state: ctx.sitesState,
        reservables: ctx.sites,
        totalAtPoi: ctx.sitesTotal,
        error: ctx.sitesError,
        expanded: ctx.sitesExpanded,
        selectedDay: sitesDay,
      })}
    </section>
  `;
}

function renderNightsRow(ctx) {
  const chips = MIN_NIGHTS_CHIPS.map(
    (n) =>
      `<button type="button" class="cg-chip ${n === ctx.minNights ? 'cg-chip-active' : ''}" data-nights="${n}">${n}</button>`,
  ).join('');
  return `
    <div class="cg-nights-row">
      <div class="cg-nights-group">
        <span class="cg-nights-label">Min nights</span>
        ${chips}
      </div>
      <div class="cg-view-toggle" role="group" aria-label="Availability view">
        ${viewModeButton(ctx, 'table', 'Table')}
        ${viewModeButton(ctx, 'week', 'Week')}
      </div>
    </div>
  `;
}

function viewModeButton(ctx, mode, label) {
  const active = ctx.availabilityView === mode;
  return `
    <button type="button" class="cg-view-mode${active ? ' cg-view-mode-active' : ''}" data-view-mode="${mode}" aria-pressed="${active ? 'true' : 'false'}">
      ${escapeHtml(label)}
    </button>
  `;
}

function renderWeekNav(ctx) {
  const start = ctx.weekStart;
  const end = addDays(start, WEEK_DAYS - 1);
  const sameMonth = start.getUTCMonth() === end.getUTCMonth();
  const fmt = (d, opts) => d.toLocaleDateString('en-US', { ...opts, timeZone: 'UTC' });
  const label = sameMonth
    ? `${fmt(start, { month: 'short', day: 'numeric' })} – ${fmt(end, { day: 'numeric' })}, ${start.getUTCFullYear()}`
    : `${fmt(start, { month: 'short', day: 'numeric' })} – ${fmt(end, { month: 'short', day: 'numeric' })}, ${start.getUTCFullYear()}`;
  const today = startOfTodayUtc();
  const onCurrentWeek = sameDay(start, today);
  const prevDisabled = isBefore(addDays(start, -WEEK_DAYS), today);
  // "Today" shortcut sits next to the prev arrow when the user has paged
  // away from the current week. Hidden otherwise so it doesn't add noise
  // for the most common case.
  const todayBtn = onCurrentWeek
    ? ''
    : `<button type="button" class="cg-week-today" aria-label="Jump to today">Today</button>`;
  return `
    <div class="cg-week-nav">
      <div class="cg-week-nav-left">
        <button type="button" class="cg-week-prev" aria-label="Previous week" ${prevDisabled ? 'disabled' : ''}>‹</button>
        ${todayBtn}
      </div>
      <button type="button" class="cg-week-label" aria-label="Pick a date">${escapeHtml(label)}</button>
      <button type="button" class="cg-week-next" aria-label="Next week">›</button>
    </div>
  `;
}

function renderBody(ctx) {
  if (ctx.state === 'loading' || ctx.days == null) return renderWeekSkeleton();
  if (ctx.state === 'error') {
    return `<div class="cg-summary"><span class="cg-error">${escapeHtml(ctx.error || "Couldn't load availability")}</span> · <a href="#" class="cg-retry">Retry</a></div>`;
  }
  if (ctx.state === 'empty') {
    return `<div class="cg-closed-banner">${escapeHtml(ctx.summary || 'No availability data for this campground.')}</div>`;
  }
  if (ctx.state === 'closed_for_season') {
    const reopens = ctx.season?.reopens_on;
    const msg = reopens ? `Reopens ${reopens}` : 'Closed for season';
    return `<div class="cg-closed-banner">⛰️ ${escapeHtml(msg)}</div>`;
  }
  return renderWeekGrid({
    days: ctx.days,
    todayIso: isoDate(startOfTodayUtc()),
    selectedDate: ctx.selectedDate,
    watchedDates: new Set(ctx.alertsByDate.keys()),
  });
}

function renderAvailabilitySurface(ctx) {
  if (ctx.state !== 'success' || ctx.availabilityView === 'week') return renderBody(ctx);
  const days = matrixAvailabilityDays(ctx);
  return renderSiteMatrix({
    state: ctx.sitesState,
    reservables: ctx.sites,
    days,
    error: ctx.sitesError,
    selectedDate: ctx.selectedDate,
    siteColumnWidth: ctx.siteColumnWidth,
    loadingMore: ctx.matrixLoading,
    loadMoreError: ctx.matrixError,
    showToday: shouldShowMatrixToday(ctx),
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
    minNights: ctx.minNights,
    watching: ctx.alertsByDate.has(day.date),
    recgovId: ctx.recgovId,
  });
}

function selectedAvailabilityDay(ctx) {
  if (ctx.state === 'loading' || ctx.state === 'error' || ctx.state === 'empty' || ctx.state === 'closed_for_season') {
    return null;
  }
  const days = siteListAvailabilityDays(ctx);
  if (!ctx.selectedDate || !days || days.length === 0) return null;
  return days.find((d) => d.date === ctx.selectedDate) || null;
}

function availableCount(day) {
  return day?.available_count ?? day?.availableCount ?? 0;
}

// ---- event wiring ---------------------------------------------------------

function wireRoot(ctx) {
  ctx.host.addEventListener('click', (e) => onRootClick(ctx, e));
  ctx.host.addEventListener('pointerdown', (e) => onRootPointerDown(ctx, e));
  ctx.host.addEventListener('scroll', (e) => onRootScroll(ctx, e), true);
}

function onRootPointerDown(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;
  const siteColumnResizer = tgt.closest('[data-site-column-resizer]');
  if (siteColumnResizer) {
    beginSiteColumnResize(ctx, e, siteColumnResizer);
  }
}

function onRootClick(ctx, e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;

  // Close calendar if the click landed elsewhere; the popover handles its
  // own outside-click via document listeners but the explicit checks below
  // win first.
  const chipBtn = tgt.closest('[data-nights]');
  if (chipBtn) {
    const n = parseInt(chipBtn.getAttribute('data-nights'), 10);
    if (Number.isFinite(n) && n !== ctx.minNights) {
      ctx.minNights = n;
      saveMinNights(n);
      // Refetch — BE may use min_nights for stay-mode scoring (RFC 0007).
      // The URL changes, the cache key changes, and the day-detail picks
      // up the new "N-night stay" label after the response lands.
      fetchWeek(ctx);
      if (ctx.selectedDate) fetchSites(ctx);
    }
    return;
  }
  const viewModeBtn = tgt.closest('[data-view-mode]');
  if (viewModeBtn) {
    const mode = viewModeBtn.getAttribute('data-view-mode');
    if (mode === 'table' || mode === 'week') {
      ctx.availabilityView = mode;
      saveAvailabilityView(mode);
      rerender(ctx);
    }
    return;
  }
  if (tgt.closest('.cg-week-prev')) {
    if (tgt.closest('.cg-week-prev').disabled) return;
    ctx.weekStart = addDays(ctx.weekStart, -WEEK_DAYS);
    ctx.selectedDate = null;
    ctx.sitesExpanded = false;
    fetchWeek(ctx);
    return;
  }
  if (tgt.closest('.cg-week-next')) {
    ctx.weekStart = addDays(ctx.weekStart, WEEK_DAYS);
    ctx.selectedDate = null;
    ctx.sitesExpanded = false;
    fetchWeek(ctx);
    return;
  }
  if (tgt.closest('.cg-week-today')) {
    const today = startOfTodayUtc();
    if (sameDay(ctx.weekStart, today)) return;
    ctx.weekStart = today;
    ctx.selectedDate = null;
    ctx.sitesExpanded = false;
    fetchWeek(ctx);
    return;
  }
  if (tgt.closest('.cg-week-label')) {
    e.preventDefault();
    e.stopPropagation();
    openCalendar(ctx, tgt.closest('.cg-week-label'));
    return;
  }
  const dayBtn = tgt.closest('.cg-day:not(.cg-day-skeleton)');
  if (dayBtn) {
    const date = dayBtn.getAttribute('data-date');
    const selected = ctx.selectedDate !== date;
    ctx.selectedDate = selected ? date : null;
    ctx.sitesExpanded = selected;
    if (selected) {
      fetchSites(ctx);
    } else {
      rerender(ctx);
    }
    return;
  }
  const matrixDateBtn = tgt.closest('[data-matrix-date]');
  if (matrixDateBtn) {
    const date = matrixDateBtn.getAttribute('data-matrix-date');
    if (!date) return;
    const selected = ctx.selectedDate !== date;
    ctx.selectedDate = selected ? date : null;
    ctx.sitesExpanded = selected;
    if (selected) {
      fetchSites(ctx);
    } else {
      rerender(ctx);
    }
    return;
  }
  if (tgt.closest('[data-matrix-today]')) {
    e.preventDefault();
    jumpMatrixToToday(ctx);
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
    toggleAlert(ctx, alertBtn);
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
  }
}

function onRootScroll(ctx, e) {
  const scroll = e.target;
  if (!(scroll instanceof HTMLElement)) return;
  if (!scroll.classList.contains('cg-site-matrix-scroll')) return;
  ctx.matrixScrollLeft = scroll.scrollLeft;
  if (ctx.availabilityView !== 'table') return;
  if (ctx.state !== 'success') return;
  if (ctx.matrixLoading || ctx.matrixEnd) return;
  const remaining = scroll.scrollWidth - scroll.clientWidth - scroll.scrollLeft;
  if (remaining <= MATRIX_SCROLL_LOAD_THRESHOLD_PX) {
    fetchMoreMatrixDays(ctx);
  }
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

function jumpMatrixToToday(ctx) {
  const today = startOfTodayUtc();
  ctx.selectedDate = null;
  ctx.sitesExpanded = false;
  ctx.matrixScrollLeft = 0;
  if (!sameDay(ctx.weekStart, today)) {
    ctx.weekStart = today;
    fetchWeek(ctx);
    return;
  }
  ctx.matrixDays = Array.isArray(ctx.days) ? [...ctx.days] : ctx.matrixDays;
  ctx.matrixEnd = false;
  ctx.matrixError = null;
  rerender(ctx);
}

function openCalendar(ctx, anchorBtn) {
  // Close any prior popover (idempotent).
  ctx.calendar?.dispose();
  ctx.calendar = null;

  // Mount a sibling div next to the label so absolute positioning can hang
  // it under the anchor without disturbing layout.
  const popoverHost = document.createElement('div');
  popoverHost.className = 'cg-cal-host';
  anchorBtn.parentElement.appendChild(popoverHost);

  const today = startOfTodayUtc();
  ctx.calendar = mountCalendarPopover(popoverHost, {
    viewMonth: ctx.weekStart,
    today,
    selectedDate: ctx.weekStart,
    maxDate: addDays(today, CALENDAR_MAX_DAYS_OUT),
    onPick: (date) => {
      ctx.weekStart = date;
      ctx.selectedDate = null;
      ctx.sitesExpanded = false;
      ctx.calendar?.dispose();
      ctx.calendar = null;
      fetchWeek(ctx);
    },
    onClose: () => {
      ctx.calendar?.dispose();
      ctx.calendar = null;
      // The popover lives in the rendered tree; rerender drops it.
      rerender(ctx);
    },
  });
}

// ---- data -----------------------------------------------------------------

async function fetchWeek(ctx, { force = false } = {}) {
  ctx.state = 'loading';
  ctx.error = null;
  resetMatrixRange(ctx);
  // Skeleton only flashes for slow fetches; cache hits feel instant.
  clearTimeout(ctx.skeletonTimer);
  ctx.skeletonTimer = setTimeout(() => rerender(ctx), SKELETON_RENDER_DELAY_MS);
  try {
    const resp = await requestCampsiteAvailability(ctx.poiId, {
      days: WEEK_DAYS,
      start: isoDate(ctx.weekStart),
      minNights: ctx.minNights,
      force,
      signal: ctx.signal,
    });
    clearTimeout(ctx.skeletonTimer);
    if (!isActiveFeature(ctx.feature)) return;
    if (!resp.ok) {
      const json = await resp.json().catch(() => null);
      ctx.state = 'error';
      ctx.error = json?.error || `HTTP ${resp.status}`;
      rerender(ctx);
      return;
    }
    const json = await resp.json();
    ctx.cacheBlock = json.cache || null;
    if (json.state === 'empty') {
      ctx.state = 'empty';
      ctx.days = [];
      ctx.summary = json.summary || 'No availability data for this campground.';
    } else if (json.state === 'closed_for_season') {
      ctx.state = 'closed_for_season';
      ctx.days = [];
      ctx.season = json.season || null;
    } else {
      ctx.state = 'success';
      ctx.days = json.availability || [];
      ctx.matrixDays = [...ctx.days];
    }
    rerender(ctx);
  } catch (e) {
    clearTimeout(ctx.skeletonTimer);
    if (e.name === 'AbortError') return;
    if (!isActiveFeature(ctx.feature)) return;
    ctx.state = 'error';
    ctx.error = e.message || 'network';
    rerender(ctx);
  }
}

async function fetchMoreMatrixDays(ctx) {
  const visibleDays = matrixAvailabilityDays(ctx);
  const lastDay = visibleDays[visibleDays.length - 1];
  if (!lastDay?.date) return;
  const nextStart = addDays(parseIsoDate(lastDay.date), 1);
  const requestSeq = ++ctx.matrixRequestSeq;
  ctx.matrixLoading = true;
  ctx.matrixError = null;
  rerender(ctx);
  try {
    const resp = await requestCampsiteAvailability(ctx.poiId, {
      days: WEEK_DAYS,
      start: isoDate(nextStart),
      minNights: 1,
      signal: ctx.signal,
    });
    if (!isActiveFeature(ctx.feature)) return;
    if (ctx.signal?.aborted) return;
    if (requestSeq !== ctx.matrixRequestSeq) return;
    if (!resp.ok) {
      const json = await resp.json().catch(() => null);
      if (json?.error === 'bad_start') {
        ctx.matrixEnd = true;
      } else {
        ctx.matrixError = json?.error || `HTTP ${resp.status}`;
      }
      return;
    }
    const json = await resp.json();
    const nextDays = Array.isArray(json.availability) ? json.availability : [];
    const merged = mergeAvailabilityDays(visibleDays, nextDays);
    ctx.matrixDays = merged;
    ctx.matrixEnd = merged.length === visibleDays.length || nextDays.length < WEEK_DAYS;
    if (json.cache) ctx.cacheBlock = json.cache;
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (!isActiveFeature(ctx.feature)) return;
    if (requestSeq !== ctx.matrixRequestSeq) return;
    ctx.matrixError = e.message || 'network';
  } finally {
    if (requestSeq === ctx.matrixRequestSeq) {
      ctx.matrixLoading = false;
      rerender(ctx);
    }
  }
}

async function fetchSites(ctx) {
  if (ctx.poiId == null) return;
  const requestSeq = ++ctx.sitesRequestSeq;
  const start = ctx.selectedDate;
  ctx.sitesState = 'loading';
  ctx.sitesError = null;
  rerender(ctx);
  try {
    const json = await fetchPoiReservables(ctx.poiId, {
      start,
      minNights: start ? ctx.minNights : null,
      signal: ctx.signal,
    });
    if (ctx.signal?.aborted) return;
    if (!isActiveFeature(ctx.feature)) return;
    if (requestSeq !== ctx.sitesRequestSeq) return;
    ctx.sitesState = 'success';
    ctx.sites = Array.isArray(json?.reservables) ? json.reservables : [];
    ctx.sitesTotal = typeof json?.total_at_poi === 'number' ? json.total_at_poi : ctx.sites.length;
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    if (!isActiveFeature(ctx.feature)) return;
    if (requestSeq !== ctx.sitesRequestSeq) return;
    ctx.sitesState = 'error';
    ctx.sitesError = e.message || 'network';
    rerender(ctx);
  }
}

async function fetchAlerts(ctx) {
  if (!ctx.recgovId) return;
  try {
    const alerts = await listCampsiteAlerts({ signal: ctx.signal });
    if (ctx.signal?.aborted) return;
    ctx.alertsByDate = indexAlertsByDate(alerts, ctx.recgovId);
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    // Non-fatal: badges just don't render.
    console.warn('alert list fetch failed', e);
  }
}

function indexAlertsByDate(alerts, campgroundId) {
  const out = new Map();
  if (!Array.isArray(alerts)) return out;
  const cgId = String(campgroundId);
  for (const a of alerts) {
    if (!a) continue;
    if (a.status === 'done') continue;
    if (String(a.campground_id ?? a.campgroundId) !== cgId) continue;
    const date = a.start_date ?? a.startDate;
    if (date) out.set(date, a);
  }
  return out;
}

async function toggleAlert(ctx, button) {
  const date = ctx.selectedDate;
  if (!date) return;
  const watching = ctx.alertsByDate.has(date);
  const previousLabel = button.textContent;
  button.disabled = true;
  try {
    if (watching) {
      const existing = ctx.alertsByDate.get(date);
      button.textContent = 'Removing…';
      await deleteCampsiteAlert(existing.id, { signal: ctx.signal });
      ctx.alertsByDate.delete(date);
    } else {
      button.textContent = 'Setting alert…';
      const payload = buildAlertPayload(ctx, date);
      const created = await createCampsiteAlert(payload, { signal: ctx.signal });
      ctx.alertsByDate.set(date, { ...created, ...payload });
    }
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    button.textContent = previousLabel;
    button.disabled = false;
    console.warn('alert toggle failed', e);
  }
}

function buildAlertPayload(ctx, date) {
  const endDate = addDays(parseIsoDate(date), ctx.minNights);
  return {
    campground_id: String(ctx.recgovId),
    campground_name: ctx.feature.properties?.name || `Campground ${ctx.recgovId}`,
    parent_name: ctx.feature.properties?.parent_name || null,
    parent_id: ctx.feature.properties?.parent_id || null,
    start_date: date,
    end_date: isoDate(endDate),
    min_nights: ctx.minNights,
    campsite_types: [],
    equipment_types: [],
    notify_slack: false,
    auto_cart: false,
    stop_after_match: true,
  };
}

// ---- helpers --------------------------------------------------------------

function loadMinNights() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_MIN_NIGHTS);
    const n = parseInt(raw, 10);
    if (Number.isFinite(n) && n > 0 && n < 32) return n;
  } catch {
    // localStorage blocked (private mode, etc.) — default silently.
  }
  return DEFAULT_MIN_NIGHTS;
}

function saveMinNights(n) {
  try {
    localStorage.setItem(STORAGE_KEY_MIN_NIGHTS, String(n));
  } catch {
    // Non-fatal: just won't persist.
  }
}

function loadAvailabilityView() {
  try {
    const mode = localStorage.getItem(STORAGE_KEY_AVAILABILITY_VIEW);
    if (mode === 'table' || mode === 'week') return mode;
  } catch {
    // Non-fatal: default silently.
  }
  return DEFAULT_AVAILABILITY_VIEW;
}

function saveAvailabilityView(mode) {
  try {
    localStorage.setItem(STORAGE_KEY_AVAILABILITY_VIEW, mode);
  } catch {
    // Non-fatal: just won't persist.
  }
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

function restoreMatrixScroll(ctx) {
  if (ctx.availabilityView !== 'table' || !ctx.matrixScrollLeft) return;
  const left = ctx.matrixScrollLeft;
  window.requestAnimationFrame?.(() => {
    const scroll = ctx.host.querySelector('.cg-site-matrix-scroll');
    if (scroll instanceof HTMLElement) scroll.scrollLeft = left;
  });
}

function resetMatrixRange(ctx) {
  ctx.matrixDays = null;
  ctx.matrixLoading = false;
  ctx.matrixEnd = false;
  ctx.matrixError = null;
  ctx.matrixScrollLeft = 0;
  ctx.matrixRequestSeq += 1;
}

function matrixAvailabilityDays(ctx) {
  return Array.isArray(ctx.matrixDays) ? ctx.matrixDays : (Array.isArray(ctx.days) ? ctx.days : []);
}

function shouldShowMatrixToday(ctx) {
  const days = matrixAvailabilityDays(ctx);
  const firstDate = days[0]?.date;
  return days.length > WEEK_DAYS || (firstDate != null && firstDate !== isoDate(startOfTodayUtc()));
}

function siteListAvailabilityDays(ctx) {
  if (ctx.availabilityView === 'table') return matrixAvailabilityDays(ctx);
  return Array.isArray(ctx.days) ? ctx.days : [];
}

function mergeAvailabilityDays(current, next) {
  const byDate = new Map();
  for (const day of current || []) {
    if (day?.date) byDate.set(day.date, day);
  }
  for (const day of next || []) {
    if (day?.date) byDate.set(day.date, day);
  }
  return [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));
}

function clampSiteColumnWidth(width) {
  return Math.max(MIN_SITE_COLUMN_WIDTH, Math.min(MAX_SITE_COLUMN_WIDTH, Math.round(width)));
}

function startOfTodayUtc() {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
}

function addDays(date, days) {
  return new Date(date.getTime() + days * MS_PER_DAY);
}

function isBefore(a, b) {
  return a.getTime() < b.getTime();
}

function sameDay(a, b) {
  return isoDate(a) === isoDate(b);
}

function isoDate(date) {
  return date.toISOString().slice(0, 10);
}

function parseIsoDate(s) {
  return new Date(s + 'T00:00:00Z');
}
