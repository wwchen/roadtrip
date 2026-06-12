// Week-grid availability + per-day alert capture (RFC 0007).
//
// Replaces the legacy 30-day heat strip + full alert form. Surface area:
//   - 7 day cards with DOW · day · site count, color-coded available/
//     partial/booked/closed.
//   - ‹ › nav pages by 7 days. The per-month upstream cache makes paging
//     within a month free.
//   - Min-nights chip row (1/2/3/7), persisted in localStorage.
//   - Tap a day → day-detail panel below the grid with a single 🔔 CTA.
//   - Existing alert on a day → cell shows a 🔔 badge; CTA reads "Watching ✓"
//     and a second click removes the alert.
//
// Owns: visible week start, min nights, selected day, in-flight controller,
// cached list of user alerts (so badges render without a per-cell fetch).
// Does NOT own: drawer chrome (chrome.js), reserve link (campground.js's
// top-level action row), or any provider-specific knowledge — the API is
// poi-id-keyed and the backend dispatches.

import { escapeHtml } from '../core.js';
import {
  createCampsiteAlert,
  deleteCampsiteAlert,
  findMatchingAlert,
  listCampsiteAlerts,
} from '../api/campsite-alert-api.js';
import { requestCampsiteAvailability } from '../api/availability-api.js';
import { isActiveFeature } from './chrome.js';

const STORAGE_KEY_MIN_NIGHTS = 'cg.minNights';
const DEFAULT_MIN_NIGHTS = 1;
const MIN_NIGHTS_CHIPS = [1, 2, 3, 7];
const WEEK_DAYS = 7;
const SKELETON_RENDER_DELAY_MS = 150;
const MS_PER_DAY = 24 * 60 * 60 * 1000;

const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/**
 * Mount the week grid into the host element. Returns a controller with a
 * `dispose()` method the drawer should call on close (clears any pending
 * skeleton timer; in-flight fetches are killed via the AbortSignal already).
 *
 * @param {HTMLElement} host       DOM node the component renders into.
 * @param {object}      feature    POI feature (used for poi id, recgov_id).
 * @param {AbortSignal} signal     Drawer-scoped abort signal.
 */
export function mountAvailabilityWeek(host, feature, { signal } = {}) {
  const poiId = feature.id;
  // recgov_id is the top-level flattened key (set by flattenHydratedPoi /
  // flattenPoi). Fall back to provider_ref.recgov_id for any path that
  // skipped flattening — drawer should still be functional.
  const recgovId =
    feature.properties?.recgov_id ?? feature.properties?.provider_ref?.recgov_id ?? null;
  const ctx = {
    host,
    feature,
    poiId,
    recgovId,
    signal,
    weekStart: startOfTodayUtc(),
    minNights: loadMinNights(),
    selectedDate: null,
    days: null, // null = loading; array of {date,status,availableCount,total} = loaded
    cacheBlock: null,
    state: 'loading',
    error: null,
    alertsByDate: new Map(), // date string → alert row
    skeletonTimer: null,
  };

  host.innerHTML = renderShell(ctx);
  wireShell(ctx);
  fetchWeek(ctx);
  fetchAlerts(ctx);

  return {
    dispose() {
      clearTimeout(ctx.skeletonTimer);
    },
  };
}

// ---- render ---------------------------------------------------------------

function renderShell(ctx) {
  return `
    <section class="cg-availability">
      ${renderNightsRow(ctx)}
      ${renderWeekNav(ctx)}
      ${renderWeek(ctx)}
      <div class="cg-freshness">${renderFreshness(ctx)}</div>
      ${renderDayDetail(ctx)}
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
      <span class="cg-nights-label">Min nights</span>
      ${chips}
    </div>
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
  const prevDisabled = isBefore(addDays(ctx.weekStart, -WEEK_DAYS), startOfTodayUtc());
  return `
    <div class="cg-week-nav">
      <button type="button" class="cg-week-prev" aria-label="Previous week" ${prevDisabled ? 'disabled' : ''}>‹</button>
      <div class="cg-week-label">${escapeHtml(label)}</div>
      <button type="button" class="cg-week-next" aria-label="Next week">›</button>
    </div>
  `;
}

function renderWeek(ctx) {
  if (ctx.state === 'loading' || ctx.days == null) {
    return `<div class="cg-week" aria-busy="true">${'<div class="cg-day cg-day-skeleton"><div class="cg-day-dow">·</div><div class="cg-day-num">·</div><div class="cg-day-avail">·</div></div>'.repeat(WEEK_DAYS)}</div>`;
  }
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
  const today = isoDate(startOfTodayUtc());
  const cells = ctx.days.map((d) => {
    const dow = new Date(d.date + 'T00:00:00Z').getUTCDay();
    const dowLabel = DOW_LABELS[dow];
    const dayNum = parseInt(d.date.slice(8, 10), 10);
    const availLabel = renderAvailLabel(d);
    const watching = ctx.alertsByDate.has(d.date);
    const classes = [
      'cg-day',
      `cg-day-${d.status}`,
      d.date === today ? 'cg-day-today' : '',
      d.date === ctx.selectedDate ? 'cg-day-selected' : '',
      watching ? 'cg-day-watching' : '',
    ]
      .filter(Boolean)
      .join(' ');
    return `
      <button type="button" class="${classes}" data-date="${d.date}" aria-label="${escapeHtml(`${d.date} — ${d.status}`)}">
        <div class="cg-day-dow">${dowLabel}</div>
        <div class="cg-day-num">${dayNum}</div>
        <div class="cg-day-avail">${escapeHtml(availLabel)}</div>
      </button>
    `;
  });
  return `<div class="cg-week">${cells.join('')}</div>`;
}

function renderAvailLabel(day) {
  if (day.status === 'closed') return 'closed';
  if (day.status === 'booked') return 'full';
  if (day.total === 0) return '—';
  return String(day.availableCount);
}

function renderFreshness(ctx) {
  if (ctx.state !== 'success' || !ctx.cacheBlock) return '&nbsp;';
  const ageMin = Math.max(1, Math.round((ctx.cacheBlock.age_seconds ?? 0) / 60));
  const stale = ageMin >= 10;
  return stale
    ? `<span class="cg-stale">checked ${ageMin}m ago · <a href="#" class="cg-refresh">refresh</a></span>`
    : `<span>checked ${ageMin}m ago · <a href="#" class="cg-refresh">refresh</a></span>`;
}

function renderDayDetail(ctx) {
  if (!ctx.selectedDate || !ctx.days || ctx.days.length === 0) return '';
  const day = ctx.days.find((d) => d.date === ctx.selectedDate);
  if (!day) return '';
  const dateLabel = new Date(day.date + 'T00:00:00Z').toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
  const watching = ctx.alertsByDate.has(day.date);
  const stayLabel = `${ctx.minNights}-night stay`;
  let statusLine;
  switch (day.status) {
    case 'available':
      statusLine = `<span class="cg-status-ok">Available</span> · ${day.availableCount} of ${day.total} sites · ${stayLabel}`;
      break;
    case 'partial':
      statusLine = `<span class="cg-status-partial">Partial</span> · ${day.availableCount} of ${day.total} sites · ${stayLabel}`;
      break;
    case 'booked':
      statusLine = `<span class="cg-status-full">Full</span> · ${stayLabel}`;
      break;
    case 'closed':
      statusLine = `<span class="cg-status-full">Closed</span> · ${stayLabel}`;
      break;
    default:
      statusLine = stayLabel;
  }
  // Closed days can't have alerts (the campground isn't open). Available
  // days could in theory open more sites later — alert is still useful;
  // partial days definitely. Allow alerts on everything except `closed`.
  const canAlert = day.status !== 'closed' && Boolean(ctx.recgovId);
  let action = '';
  if (canAlert) {
    action = watching
      ? `<button type="button" class="cg-btn cg-btn-secondary cg-day-alert" data-state="watching">Watching ✓ — tap to remove</button>`
      : `<button type="button" class="cg-btn cg-btn-primary cg-day-alert" data-state="set">🔔 Set alert</button>`;
  } else if (!ctx.recgovId) {
    action = `<span class="cg-day-detail-meta">Alerts coming soon for this provider.</span>`;
  } else {
    action = `<span class="cg-day-detail-meta">No openings to watch on a closed day.</span>`;
  }

  return `
    <div class="cg-day-detail">
      <div class="cg-day-detail-head">
        <div class="cg-day-detail-date">${escapeHtml(dateLabel)}</div>
        <div class="cg-day-detail-meta">${statusLine}</div>
      </div>
      <div class="cg-day-detail-actions">${action}</div>
    </div>
  `;
}

// ---- wiring ---------------------------------------------------------------

function wireShell(ctx) {
  ctx.host.addEventListener('click', (e) => {
    const tgt = e.target;
    if (!(tgt instanceof Element)) return;

    const chipBtn = tgt.closest('[data-nights]');
    if (chipBtn) {
      const n = parseInt(chipBtn.getAttribute('data-nights'), 10);
      if (Number.isFinite(n) && n !== ctx.minNights) {
        ctx.minNights = n;
        saveMinNights(n);
        rerender(ctx); // selected day, if any, picks up new stay length
      }
      return;
    }
    if (tgt.closest('.cg-week-prev')) {
      const next = addDays(ctx.weekStart, -WEEK_DAYS);
      if (!isBefore(next, startOfTodayUtc())) {
        ctx.weekStart = next;
        ctx.selectedDate = null;
        fetchWeek(ctx);
      }
      return;
    }
    if (tgt.closest('.cg-week-next')) {
      ctx.weekStart = addDays(ctx.weekStart, WEEK_DAYS);
      ctx.selectedDate = null;
      fetchWeek(ctx);
      return;
    }
    const dayBtn = tgt.closest('.cg-day:not(.cg-day-skeleton)');
    if (dayBtn) {
      const date = dayBtn.getAttribute('data-date');
      ctx.selectedDate = ctx.selectedDate === date ? null : date;
      rerender(ctx);
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
    }
  });
}

// ---- data -----------------------------------------------------------------

async function fetchWeek(ctx, { force = false } = {}) {
  // Show a skeleton only if the fetch is slow; cache hits should feel
  // instant rather than flashing a shimmer.
  ctx.state = 'loading';
  ctx.error = null;
  ctx.skeletonTimer = setTimeout(() => {
    rerender(ctx);
  }, SKELETON_RENDER_DELAY_MS);
  try {
    const resp = await requestCampsiteAvailability(ctx.poiId, {
      days: WEEK_DAYS,
      start: isoDate(ctx.weekStart),
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
    // Backend `state` values: success | zero_available | closed_for_season |
    // empty | error. We collapse all of them into FE-side rendering buckets:
    //   - `empty`              → banner; no week grid (provider missing,
    //                            Camis stub, or no availability data).
    //   - `closed_for_season`  → banner; no week grid (off-season).
    //   - everything else      → render the 7 day cells.
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

async function fetchAlerts(ctx) {
  if (!ctx.recgovId) return;
  try {
    const alerts = await listCampsiteAlerts({ signal: ctx.signal });
    if (ctx.signal?.aborted) return;
    ctx.alertsByDate = indexAlertsByDate(alerts, ctx.recgovId);
    rerender(ctx);
  } catch (e) {
    if (e.name === 'AbortError') return;
    // Non-fatal: badges just don't render. Logged so it's diagnosable.
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

function rerender(ctx) {
  ctx.host.innerHTML = renderShell(ctx);
}

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

function isoDate(date) {
  return date.toISOString().slice(0, 10);
}

function parseIsoDate(s) {
  return new Date(s + 'T00:00:00Z');
}
