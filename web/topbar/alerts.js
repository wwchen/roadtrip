// web/topbar/alerts.js — nav "availability alerts" row + summary.
//
// A second row under the search box that appears when the user has any
// availability watches. Clicking it expands a summary table (POI, date,
// trigger, last-checked) listing active, paused, and done watches, with
// per-row pause/resume + delete (done watches show a ✅ found / ⌛ ended
// status instead of a toggle). Watches are global (no auth), so this
// reflects everyone's watches.
//
// Self-contained: owns its DOM (#tb-alerts), injects its own tb-* styles,
// and refreshes on the shared 'watches-changed' event fired whenever a watch
// is created/removed/paused/resumed anywhere in the app.

import { listWatches, updateWatch, deleteWatch } from '../api/watches-api.js';
import { fetchPoiDetail } from '../api/poi-api.js';
import { onWatchesChanged } from '../availability/watch-events.js';
import { escapeHtml } from '../core.js';

const WATCH_LIST_LIMIT = 200;
// Trigger-kind → display. Data-driven so future kinds (config'd Slack channel,
// an ATC action) render without touching the table code.
const TRIGGER_LABELS = {
  slack_notify: '🔔 Slack',
  atc: '🛒 ATC',
};

const poiNameCache = new Map();

let rootEl = null;
let expanded = false;
let watches = [];

export function initAlerts() {
  rootEl = document.getElementById('tb-alerts');
  if (!rootEl) return;
  injectAlertsStyles();
  rootEl.addEventListener('click', onClick);
  onWatchesChanged(refresh);
  refresh();
}

async function refresh() {
  try {
    const [active, paused, done] = await Promise.all([
      listWatches({ status: 'active', limit: WATCH_LIST_LIMIT }),
      listWatches({ status: 'paused', limit: WATCH_LIST_LIMIT }),
      listWatches({ status: 'done', limit: WATCH_LIST_LIMIT }),
    ]);
    // One flat list across all statuses, sorted by trip date (the Date column)
    // soonest-first so the nearest window is at the top; undated watches last.
    watches = [
      ...(active?.watches || []),
      ...(paused?.watches || []),
      ...(done?.watches || []),
    ].sort(byStartDate);
    await ensurePoiNames(watches);
    render();
  } catch (e) {
    console.warn('[alerts] watch fetch failed', e);
  }
}

async function ensurePoiNames(list) {
  const ids = [
    ...new Set(list.map((w) => w.poi_id).filter((id) => id != null && !poiNameCache.has(id))),
  ];
  await Promise.all(
    ids.map(async (id) => {
      try {
        const d = await fetchPoiDetail(id);
        poiNameCache.set(id, d?.properties?.name || d?.name || `POI ${id}`);
      } catch {
        poiNameCache.set(id, `POI ${id}`);
      }
    }),
  );
}

function activeCount() {
  return watches.filter((w) => w.status === 'active').length;
}

function pausedCount() {
  return watches.filter((w) => w.status === 'paused').length;
}

function doneCount() {
  return watches.filter((w) => w.status === 'done').length;
}

function render() {
  if (!rootEl) return;
  const active = activeCount();
  const paused = pausedCount();
  const done = doneCount();
  if (active + paused + done === 0) {
    rootEl.innerHTML = '';
    rootEl.classList.remove('visible');
    expanded = false;
    return;
  }
  rootEl.classList.add('visible');
  rootEl.innerHTML = `
    <button type="button" class="tb-alerts-bar" aria-expanded="${expanded}">
      <span class="tb-alerts-bell">🔔</span>
      <span class="tb-alerts-label">${escapeHtml(barLabel(active, paused, done))}</span>
      <svg class="tb-alerts-chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
    </button>
    ${expanded ? renderTable() : ''}
  `;
}

function barLabel(active, paused, done) {
  const total = active + paused + done;
  const base = `${total} availability alert${total === 1 ? '' : 's'}`;
  const extra = [];
  if (paused > 0) extra.push(`${paused} paused`);
  if (done > 0) extra.push(`${done} done`);
  return extra.length ? `${base} · ${extra.join(' · ')}` : base;
}

function renderTable() {
  const rows = watches.map(rowHtml).join('');
  return `
    <div class="tb-alerts-table" role="table" aria-label="Availability alerts">
      <div class="tb-alerts-row tb-alerts-header" role="row">
        <span role="columnheader">POI</span>
        <span role="columnheader">Date</span>
        <span role="columnheader">Trigger</span>
        <span role="columnheader">Last checked</span>
        <span role="columnheader" class="tb-alerts-actions-col"></span>
      </div>
      ${rows}
    </div>
  `;
}

function watchName(w) {
  if (w.poi_id != null) return poiNameCache.get(w.poi_id) || `POI ${w.poi_id}`;
  // Reservable-targeted watches (no POI scope) carry a reservable object.
  const r = w.reservable;
  if (r?.name) return r.loop ? `${r.loop} / ${r.name}` : r.name;
  return `Watch #${w.id}`;
}

function rowHtml(w) {
  const name = watchName(w);
  const start = w.start_date ?? '';
  const stateClass = w.status === 'paused' ? ' is-paused' : w.status === 'done' ? ' is-done' : '';
  return `
    <div class="tb-alerts-row${stateClass}" role="row" data-poi="${escapeHtml(String(w.poi_id ?? ''))}" data-week="${escapeHtml(start)}">
      <span class="tb-alerts-poi" role="cell" title="${escapeHtml(name)}">${escapeHtml(name)}</span>
      <span class="tb-alerts-date" role="cell">${escapeHtml(fmtDate(start))}</span>
      <span class="tb-alerts-trigger" role="cell">${escapeHtml(triggerLabel(w))}</span>
      <span class="tb-alerts-checked" role="cell">${checkedHtml(w)}</span>
      <span class="tb-alerts-actions" role="cell">
        ${actionsHtml(w)}
        <button type="button" class="tb-alerts-act tb-alerts-del" data-act="delete" data-id="${w.id}" title="Delete" aria-label="Delete watch">🗑</button>
      </span>
    </div>
  `;
}

// Active/paused get an interactive pause/resume toggle. Done watches are
// terminal — show a static status glyph instead: ✅ when availability was
// found, ⌛ when the watch window elapsed without a hit.
function actionsHtml(w) {
  if (w.status === 'done') {
    return doneKind(w) === 'expired'
      ? `<span class="tb-alerts-status" title="Watch window ended without availability">⌛</span>`
      : `<span class="tb-alerts-status" title="Availability found">✅</span>`;
  }
  return w.status === 'paused'
    ? `<button type="button" class="tb-alerts-act" data-act="resume" data-id="${w.id}" title="Resume" aria-label="Resume watch">▶</button>`
    : `<button type="button" class="tb-alerts-act" data-act="pause" data-id="${w.id}" title="Pause" aria-label="Pause watch">⏸</button>`;
}

// A watch goes `done` two ways (see WatchAlertDispatcher / AvailabilityPollerRepo.retire):
// a successful trigger (availability found), or its end date elapsing. We can't
// see the trigger flag from the list payload, so infer "expired" from the window
// having passed; anything still within its window that's done was triggered.
function doneKind(w) {
  const end = w.end_date ?? '';
  return end && end < todayIso() ? 'expired' : 'found';
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function triggerLabel(w) {
  const kinds = Array.isArray(w.trigger_kinds) ? w.trigger_kinds : [];
  if (kinds.length === 0) return '—';
  return kinds.map((k) => TRIGGER_LABELS[k] || k).join(', ');
}

function checkedHtml(w) {
  if (w.last_run_status === 'failed') {
    const err = w.last_run_error ? ` title="${escapeHtml(w.last_run_error)}"` : '';
    return `<span class="tb-alerts-err"${err}>⚠ error</span>`;
  }
  const at = w.last_run_at;
  if (!at) return '<span class="tb-alerts-faint">—</span>';
  return `<span title="${escapeHtml(at)}">${escapeHtml(relativeTime(at))}</span>`;
}

// Ascending by start date. Dates are ISO 'YYYY-MM-DD', so lexicographic
// compare is chronological. Undated watches sort last.
function byStartDate(a, b) {
  const da = a.start_date ?? '';
  const db = b.start_date ?? '';
  if (da === db) return 0;
  if (!da) return 1;
  if (!db) return -1;
  return da < db ? -1 : 1;
}

function fmtDate(iso) {
  if (!iso) return '—';
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

function relativeTime(iso) {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const secs = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (secs < 60) return 'just now';
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  return `${days}d ago`;
}

async function onClick(e) {
  const tgt = e.target;
  if (!(tgt instanceof Element)) return;

  const actBtn = tgt.closest('.tb-alerts-act');
  if (actBtn) {
    e.stopPropagation();
    const id = actBtn.getAttribute('data-id');
    const act = actBtn.getAttribute('data-act');
    if (!id) return;
    actBtn.disabled = true;
    try {
      if (act === 'delete') await deleteWatch(id);
      else if (act === 'pause') await updateWatch(id, { status: 'paused' });
      else if (act === 'resume') await updateWatch(id, { status: 'active' });
      await refresh();
    } catch (err) {
      console.warn('[alerts] action failed', act, err);
      actBtn.disabled = false;
    }
    return;
  }

  if (tgt.closest('.tb-alerts-bar')) {
    expanded = !expanded;
    render();
    return;
  }

  const row = tgt.closest('.tb-alerts-row:not(.tb-alerts-header)');
  if (row) {
    const poi = row.getAttribute('data-poi');
    if (poi && typeof window.__rtOpenPoiById === 'function') window.__rtOpenPoiById(poi);
  }
}

function injectAlertsStyles() {
  if (document.getElementById('tb-alerts-styles')) return;
  const css = `
  #tb-alerts { display: none; border-top: 1px solid var(--cg-border); }
  #tb-alerts.visible { display: block; }
  .tb-alerts-bar {
    width: 100%;
    display: flex; align-items: center; gap: 8px;
    padding: 8px 12px;
    background: transparent; border: 0; cursor: pointer;
    color: var(--cg-text); font: inherit; font-size: 12px; text-align: left;
  }
  .tb-alerts-bar:hover { background: var(--cg-bg-hover); }
  .tb-alerts-bell { flex-shrink: 0; }
  .tb-alerts-label { flex: 1; min-width: 0; font-weight: 500; }
  .tb-alerts-chevron { flex-shrink: 0; transition: transform 150ms ease; }
  .tb-alerts-bar[aria-expanded="true"] .tb-alerts-chevron { transform: rotate(180deg); }
  .tb-alerts-table {
    max-height: min(40vh, 320px); overflow-y: auto;
    border-top: 1px solid var(--cg-border);
    font-size: 12px; font-variant-numeric: tabular-nums;
  }
  .tb-alerts-row {
    display: grid;
    grid-template-columns: minmax(0,1.4fr) auto auto minmax(0,1fr) auto;
    align-items: center; gap: 8px;
    padding: 7px 12px;
    border-bottom: 1px solid var(--cg-border);
    cursor: pointer;
  }
  .tb-alerts-row:last-child { border-bottom: 0; }
  .tb-alerts-row:hover:not(.tb-alerts-header) { background: var(--cg-bg-hover); }
  .tb-alerts-header {
    cursor: default;
    color: var(--cg-faint);
    font-size: 9px; text-transform: uppercase; letter-spacing: 0.06em;
  }
  .tb-alerts-header:hover { background: transparent; }
  .tb-alerts-row.is-paused, .tb-alerts-row.is-done { opacity: 0.55; }
  .tb-alerts-poi { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--cg-text); }
  .tb-alerts-date, .tb-alerts-trigger { white-space: nowrap; color: var(--cg-muted); }
  .tb-alerts-checked { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--cg-muted); }
  .tb-alerts-err { color: var(--cg-warn, #f1a04a); }
  .tb-alerts-faint { color: var(--cg-faint); }
  .tb-alerts-actions { display: flex; gap: 2px; justify-self: end; }
  .tb-alerts-act {
    width: 24px; height: 24px;
    background: transparent; border: 0; color: var(--cg-faint);
    border-radius: 4px; cursor: pointer; font-size: 12px;
    display: grid; place-items: center;
  }
  .tb-alerts-act:hover { background: var(--cg-bg-hover); color: var(--cg-text); }
  .tb-alerts-del:hover { color: var(--cg-error); }
  .tb-alerts-act:disabled { opacity: 0.5; cursor: wait; }
  .tb-alerts-status {
    width: 24px; height: 24px;
    display: grid; place-items: center; font-size: 12px;
  }
  `;
  const tag = document.createElement('style');
  tag.id = 'tb-alerts-styles';
  tag.textContent = css;
  document.head.appendChild(tag);
}
