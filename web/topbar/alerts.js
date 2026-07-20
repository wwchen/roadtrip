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
//
// Slack deep-links: the alert cards link back here with ?alert=<id> (and an
// optional &alert_action=pause|resume|delete). On load we expand the panel,
// scroll the watch into view, and pulse the named control so the user finishes
// the action with one click on the existing in-app button — the app stays the
// only writer, so a stale or forwarded Slack card can't mutate a watch itself.

import { listWatches, getWatch, updateWatch, deleteWatch } from '../api/watches-api.js';
import { fetchPoiDetail } from '../api/poi-api.js';
import { onWatchesChanged } from '../availability/watch-events.js';
import { mountWatchEditor } from '../availability/watch-editor.js';
import { escapeHtml } from '../core.js';

const WATCH_LIST_LIMIT = 200;
// Slack alert deep-link params — kept in sync with WatchAlertDispatcher on the
// backend, which builds `<appRoot>/?alert=<id>&alert_action=<action>`.
const ALERT_PARAM = 'alert';
const ALERT_ACTION_PARAM = 'alert_action';
const ALERT_ACTIONS = new Set(['pause', 'resume', 'delete']);
// How long the focused row + armed control stay highlighted after a deep-link.
const FOCUS_HIGHLIGHT_MS = 6000;
// Slack's official 4-color mark, inlined so the alert row stays self-contained
// (no network fetch — works offline / behind CSP like the rest of the app).
const SLACK_ICON =
  '<svg class="tb-alerts-slack" viewBox="0 0 122.8 122.8" role="img" aria-label="Slack"><title>Slack</title>' +
  '<path fill="#E01E5A" d="M25.8 77.6c0 7.1-5.8 12.9-12.9 12.9S0 84.7 0 77.6s5.8-12.9 12.9-12.9h12.9v12.9zm6.5 0c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9v32.3c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V77.6z"/>' +
  '<path fill="#36C5F0" d="M45.2 25.8c-7.1 0-12.9-5.8-12.9-12.9S38.1 0 45.2 0s12.9 5.8 12.9 12.9v12.9H45.2zm0 6.5c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H12.9C5.8 58.1 0 52.3 0 45.2s5.8-12.9 12.9-12.9h32.3z"/>' +
  '<path fill="#2EB67D" d="M97 45.2c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9-5.8 12.9-12.9 12.9H97V45.2zm-6.5 0c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V12.9C64.7 5.8 70.5 0 77.6 0s12.9 5.8 12.9 12.9v32.3z"/>' +
  '<path fill="#ECB22E" d="M77.6 97c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9-12.9-5.8-12.9-12.9V97h12.9zm0-6.5c-7.1 0-12.9-5.8-12.9-12.9s5.8-12.9 12.9-12.9h32.3c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H77.6z"/>' +
  '</svg>';
const EMAIL_ICON =
  '<svg class="tb-alerts-email" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Email"><title>Email</title>' +
  '<rect width="20" height="16" x="2" y="4" rx="2"/>' +
  '<path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>' +
  '</svg>';
// Trigger-kind → display markup. Data-driven so future kinds (config'd Slack
// channel, an ATC action) render without touching the table code. Values are
// trusted markup (icon/emoji constants), injected unescaped by triggerHtml.
const TRIGGER_HTML = {
  slack_notify: SLACK_ICON,
  email_notify: EMAIL_ICON,
  atc: '🛒 ATC',
};

const poiNameCache = new Map();

let rootEl = null;
let expanded = false;
let watches = [];
// Transient deep-link focus: the watch to highlight and the control to pulse,
// cleared after FOCUS_HIGHLIGHT_MS (or on the next unrelated render).
let focusWatchId = null;
let focusAction = null;
let focusTimer = null;
let editing = null;
let editorController = null;

export function initAlerts() {
  rootEl = document.getElementById('tb-alerts');
  if (!rootEl) return;
  injectAlertsStyles();
  rootEl.addEventListener('click', onClick);
  onWatchesChanged(refresh);
  // Handle a Slack deep-link only after the first load has the watch rows, so
  // the target row exists to focus.
  refresh().then(applyAlertDeepLink);
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
    disposeEditor();
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
  mountCurrentEditor();
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
  // Campsite-targeted watches (no POI scope) carry a campsite object.
  const r = w.campsite;
  if (r?.name) return r.loop_name ? `${r.loop_name} / ${r.name}` : r.name;
  return `Watch #${w.id}`;
}

function rowHtml(w) {
  const name = watchName(w);
  const start = w.start_date ?? '';
  const stateClass = w.status === 'paused' ? ' is-paused' : w.status === 'done' ? ' is-done' : '';
  const focused = String(w.id) === String(focusWatchId);
  // A Slack deep-link may name an action to "arm" (pulse) on the focused row,
  // pointing the user at the exact control to click.
  const armed = focused ? focusAction : null;
  const delArmed = armed === 'delete' ? ' is-armed' : '';
  const editorHtml = String(editing?.id ?? '') === String(w.id) ? editorRowHtml() : '';
  return `
    <div class="tb-alerts-row${stateClass}${focused ? ' is-focus' : ''}" role="row" data-id="${escapeHtml(String(w.id))}" data-poi="${escapeHtml(String(w.poi_id ?? ''))}" data-week="${escapeHtml(start)}">
      <span class="tb-alerts-poi" role="cell" title="${escapeHtml(name)}">${escapeHtml(name)}</span>
      <span class="tb-alerts-date" role="cell">${escapeHtml(fmtDate(start))}</span>
      <span class="tb-alerts-trigger" role="cell">${triggerHtml(w)}</span>
      <span class="tb-alerts-checked" role="cell">${checkedHtml(w)}</span>
      <span class="tb-alerts-actions" role="cell">
        ${actionsHtml(w, armed)}
        <button type="button" class="tb-alerts-act tb-alerts-del${delArmed}" data-act="delete" data-id="${w.id}" title="Delete" aria-label="Delete watch">🗑</button>
      </span>
    </div>
    ${editorHtml}
  `;
}

// Active/paused get an interactive pause/resume toggle. Done watches are
// terminal — show a static status glyph instead: ✅ when availability was
// found, ⌛ when the watch window elapsed without a hit. [armed] pulses the
// matching control when a Slack deep-link targeted it.
function actionsHtml(w, armed) {
  if (w.status === 'done') {
    return doneKind(w) === 'expired'
      ? `<span class="tb-alerts-status" title="Watch window ended without availability">⌛</span>`
      : `<span class="tb-alerts-status" title="Availability found">✅</span>`;
  }
  const statusAction = w.status === 'paused'
    ? `<button type="button" class="tb-alerts-act${armed === 'resume' ? ' is-armed' : ''}" data-act="resume" data-id="${w.id}" title="Resume" aria-label="Resume watch">▶</button>`
    : `<button type="button" class="tb-alerts-act${armed === 'pause' ? ' is-armed' : ''}" data-act="pause" data-id="${w.id}" title="Pause" aria-label="Pause watch">⏸</button>`;
  const editAction = `<button type="button" class="tb-alerts-act" data-act="edit" data-id="${w.id}" title="Edit" aria-label="Edit watch">⚙</button>`;
  return `${statusAction}${editAction}`;
}

function editorRowHtml() {
  if (editing?.loading) {
    return '<div class="tb-alerts-editor-row"><div class="tb-alerts-editor-loading">Loading watch...</div></div>';
  }
  if (editing?.error) {
    return `<div class="tb-alerts-editor-row"><div class="tb-alerts-editor-error">${escapeHtml(editing.error)}</div></div>`;
  }
  return '<div class="tb-alerts-editor-row"><div class="tb-alerts-editor-host"></div></div>';
}

// A Slack alert card links back with ?alert=<id> (+ optional
// &alert_action=pause|resume|delete). Expand the panel, focus + scroll to the
// watch, and pulse the named control. We never auto-mutate: the user completes
// the action with the existing in-app control, so a stale/forwarded card can't
// change a watch on its own.
function applyAlertDeepLink() {
  if (!rootEl) return;
  const params = new URLSearchParams(window.location.search);
  const id = params.get(ALERT_PARAM);
  if (!id) return;
  const action = params.get(ALERT_ACTION_PARAM);
  // Strip the params so a manual refresh or back-nav doesn't re-focus.
  clearAlertDeepLinkParams();
  focusWatchId = id;
  focusAction = ALERT_ACTIONS.has(action) ? action : null;
  expanded = true;
  render();
  const row = rootEl.querySelector('.tb-alerts-row.is-focus');
  if (row) row.scrollIntoView({ block: 'nearest' });
  // The highlight is a transient cue — drop it (and re-render) after a while.
  clearTimeout(focusTimer);
  focusTimer = setTimeout(() => {
    clearFocusHighlight();
  }, FOCUS_HIGHLIGHT_MS);
}

function clearFocusHighlight() {
  focusWatchId = null;
  focusAction = null;
  if (alertEditorHasFocus()) {
    clearFocusHighlightInPlace();
    return;
  }
  render();
}

function alertEditorHasFocus() {
  return alertEditorContainsFocus(rootEl, document.activeElement);
}

export function alertEditorContainsFocus(root, active) {
  return active instanceof Element &&
    !!root?.contains(active) &&
    !!active.closest('.tb-alerts-editor-host');
}

function clearFocusHighlightInPlace() {
  rootEl?.querySelectorAll('.tb-alerts-row.is-focus').forEach((row) => row.classList.remove('is-focus'));
  rootEl?.querySelectorAll('.tb-alerts-act.is-armed').forEach((button) => button.classList.remove('is-armed'));
}

function clearAlertDeepLinkParams() {
  const url = new URL(window.location.href);
  if (!url.searchParams.has(ALERT_PARAM) && !url.searchParams.has(ALERT_ACTION_PARAM)) return;
  url.searchParams.delete(ALERT_PARAM);
  url.searchParams.delete(ALERT_ACTION_PARAM);
  window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
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

function triggerHtml(w) {
  const kinds = Array.isArray(w.trigger_kinds) ? w.trigger_kinds : [];
  if (kinds.length === 0) return '—';
  // Known kinds map to trusted markup; unknown kinds fall back to escaped text.
  // Object.hasOwn guards the allowlist so inherited keys (toString, constructor)
  // can't bypass escaping.
  return kinds.map((k) => (Object.hasOwn(TRIGGER_HTML, k) ? TRIGGER_HTML[k] : escapeHtml(k))).join(' ');
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
      if (act === 'edit') {
        actBtn.disabled = false;
        await openEditor(id);
        return;
      }
      if (act === 'delete') await deleteWatch(id);
      else if (act === 'pause') await updateWatch(id, { status: 'paused' });
      else if (act === 'resume') await updateWatch(id, { status: 'active' });
      if (String(editing?.id ?? '') === String(id)) closeEditor();
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

async function openEditor(id) {
  editing = { id, loading: true, error: null, detail: null };
  expanded = true;
  render();
  try {
    const detail = await getWatch(id);
    editing = { id, loading: false, error: null, detail };
    render();
  } catch (err) {
    editing = { id, loading: false, error: 'Could not load this watch.', detail: null };
    render();
  }
}

function closeEditor() {
  editing = null;
  disposeEditor();
  render();
}

function mountCurrentEditor() {
  disposeEditor();
  if (!editing?.detail) return;
  const host = rootEl.querySelector('.tb-alerts-editor-host');
  if (!host) return;
  const watch = editing.detail.watch;
  editorController = mountWatchEditor(host, {
    title: `Edit ${watchName(watch)}`,
    subtitle: fmtWatchWindow(watch),
    watch,
    capabilities: editing.detail.watch_capabilities,
    onSave: async (payload) => {
      await updateWatch(watch.id, payload);
      editing = null;
      await refresh();
    },
    onRemove: async () => {
      await deleteWatch(watch.id);
      editing = null;
      await refresh();
    },
    onClose: closeEditor,
  });
}

function disposeEditor() {
  editorController?.dispose();
  editorController = null;
}

function fmtWatchWindow(watch) {
  const start = watch?.start_date ?? '';
  const end = watch?.end_date ?? '';
  if (!start || !end) return '';
  return `${fmtDate(start)} - ${fmtDate(end)}`;
}

function injectAlertsStyles() {
  if (document.getElementById('tb-alerts-styles')) return;
  const css = `
  #tb-alerts { display: none; border-top: 1px solid var(--rt-border); }
  #tb-alerts.visible { display: block; }
  .tb-alerts-bar {
    width: 100%;
    display: flex; align-items: center; gap: 8px;
    padding: 8px 12px;
    background: transparent; border: 0; cursor: pointer;
    color: var(--rt-text); font: inherit; font-size: 12px; text-align: left;
  }
  .tb-alerts-bar:hover { background: var(--rt-fill-hover); }
  .tb-alerts-bell { flex-shrink: 0; }
  .tb-alerts-label { flex: 1; min-width: 0; font-weight: 500; }
  .tb-alerts-chevron { flex-shrink: 0; transition: transform 150ms ease; }
  .tb-alerts-bar[aria-expanded="true"] .tb-alerts-chevron { transform: rotate(180deg); }
  .tb-alerts-table {
    max-height: min(40vh, 320px); overflow-y: auto;
    border-top: 1px solid var(--rt-border);
    font-size: 12px; font-variant-numeric: tabular-nums;
  }
  .tb-alerts-row {
    display: grid;
    grid-template-columns: minmax(0,1.4fr) auto auto minmax(0,1fr) auto;
    align-items: center; gap: 8px;
    padding: 7px 12px;
    border-bottom: 1px solid var(--rt-border);
    cursor: pointer;
  }
  .tb-alerts-row:last-child { border-bottom: 0; }
  .tb-alerts-row:hover:not(.tb-alerts-header) { background: var(--rt-fill-hover); }
  .tb-alerts-header {
    cursor: default;
    color: var(--rt-faint);
    font-size: 9px; text-transform: uppercase; letter-spacing: 0.06em;
  }
  .tb-alerts-header:hover { background: transparent; }
  .tb-alerts-row.is-paused, .tb-alerts-row.is-done { opacity: 0.55; }
  .tb-alerts-poi { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--rt-text); }
  .tb-alerts-date, .tb-alerts-trigger { white-space: nowrap; color: var(--rt-muted); }
  .tb-alerts-slack,
  .tb-alerts-email { width: 14px; height: 14px; vertical-align: -2px; }
  .tb-alerts-checked { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--rt-muted); }
  .tb-alerts-err { color: var(--rt-warn); }
  .tb-alerts-faint { color: var(--rt-faint); }
  .tb-alerts-actions { display: flex; gap: 2px; justify-self: end; }
  .tb-alerts-act {
    width: 24px; height: 24px;
    background: transparent; border: 0; color: var(--rt-faint);
    border-radius: 4px; cursor: pointer; font-size: 12px;
    display: grid; place-items: center;
  }
  .tb-alerts-act:hover { background: var(--rt-fill-hover); color: var(--rt-text); }
  .tb-alerts-del:hover { color: var(--rt-error); }
  .tb-alerts-act:disabled { opacity: 0.5; cursor: wait; }
  /* Deep-link focus: the row a Slack card pointed at, and the control it named. */
  .tb-alerts-row.is-focus { background: var(--rt-fill-hover); box-shadow: inset 2px 0 0 var(--rt-brand); }
  .tb-alerts-act.is-armed { color: var(--rt-text); animation: tb-alerts-pulse 1.2s ease-in-out infinite; }
  .tb-alerts-del.is-armed { color: var(--rt-error); }
  @keyframes tb-alerts-pulse {
    0%, 100% { transform: scale(1); background: var(--rt-fill-hover); }
    50% { transform: scale(1.18); background: var(--rt-brand); }
  }
  @media (prefers-reduced-motion: reduce) {
    .tb-alerts-act.is-armed { animation: none; background: var(--rt-fill-hover); }
  }
  .tb-alerts-status {
    width: 24px; height: 24px;
    display: grid; place-items: center; font-size: 12px;
  }
  .tb-alerts-editor-row {
    padding: 8px 12px 12px 12px;
    border-bottom: 1px solid var(--rt-border);
    background: var(--rt-fill-hover);
  }
  .tb-alerts-editor-host { max-width: 360px; margin-left: auto; }
  .tb-alerts-editor-loading,
  .tb-alerts-editor-error {
    color: var(--rt-muted);
    font-size: 12px;
    padding: 8px;
  }
  .tb-alerts-editor-error { color: var(--rt-error); }
  `;
  const tag = document.createElement('style');
  tag.id = 'tb-alerts-styles';
  tag.textContent = css;
  document.head.appendChild(tag);
}
