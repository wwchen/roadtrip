import { escapeHtml } from '../core.js';

const SLACK_ICON =
  '<svg viewBox="0 0 122.8 122.8" role="img" aria-label="Slack"><title>Slack</title>' +
  '<path fill="#E01E5A" d="M25.8 77.6c0 7.1-5.8 12.9-12.9 12.9S0 84.7 0 77.6s5.8-12.9 12.9-12.9h12.9v12.9zm6.5 0c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9v32.3c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V77.6z"/>' +
  '<path fill="#36C5F0" d="M45.2 25.8c-7.1 0-12.9-5.8-12.9-12.9S38.1 0 45.2 0s12.9 5.8 12.9 12.9v12.9H45.2zm0 6.5c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H12.9C5.8 58.1 0 52.3 0 45.2s5.8-12.9 12.9-12.9h32.3z"/>' +
  '<path fill="#2EB67D" d="M97 45.2c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9-5.8 12.9-12.9 12.9H97V45.2zm-6.5 0c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V12.9C64.7 5.8 70.5 0 77.6 0s12.9 5.8 12.9 12.9v32.3z"/>' +
  '<path fill="#ECB22E" d="M77.6 97c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9-12.9-5.8-12.9-12.9V97h12.9zm0-6.5c-7.1 0-12.9-5.8-12.9-12.9s5.8-12.9 12.9-12.9h32.3c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H77.6z"/>' +
  '</svg>';

const EMAIL_ICON =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Email"><title>Email</title>' +
  '<rect width="20" height="16" x="2" y="4" rx="2"/>' +
  '<path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>' +
  '</svg>';

export function poiCellHtml(watch, poiNames) {
  const id = watch.poi_id;
  if (id == null) return escapeHtml(watchFallbackName(watch));
  const name = poiNames.get(id) || `POI ${id}`;
  return `<a class="rt-watch-table-poi" href="/?poi=${encodeURIComponent(id)}">${escapeHtml(name)}</a>`;
}

export function dateCellHtml(iso) {
  if (!iso) return '<span style="color:var(--rt-faint)">—</span>';
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return escapeHtml(iso);
  return escapeHtml(d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' }));
}

export function triggerCellHtml(watch) {
  const kinds = Array.isArray(watch.trigger_kinds) ? watch.trigger_kinds : [];
  if (kinds.length === 0) return '<span style="color:var(--rt-faint)">—</span>';
  const parts = [];
  if (kinds.includes('slack_notify')) parts.push(SLACK_ICON);
  if (kinds.includes('email_notify')) parts.push(EMAIL_ICON);
  if (kinds.includes('atc')) parts.push('🛒');
  return `<span class="rt-watch-table-trigger">${parts.join(' ')}</span>`;
}

export function statusCellHtml(watch) {
  const s = watch.status || 'active';
  const labels = { active: 'Active', paused: 'Paused', done: 'Done' };
  return `<span class="rt-watch-table-status rt-watch-table-status-${escapeHtml(s)}">${labels[s] || s}</span>`;
}

export function checkedCellHtml(watch) {
  if (watch.last_run_status === 'failed') {
    const err = watch.last_run_error ? ` title="${escapeHtml(watch.last_run_error)}"` : '';
    return `<span style="color:var(--rt-warn)"${err}>⚠ error</span>`;
  }
  const at = watch.last_run_at;
  if (!at) return '<span style="color:var(--rt-faint)">—</span>';
  return `<span title="${escapeHtml(at)}">${escapeHtml(relativeTime(at))}</span>`;
}

export function actionsCellHtml(watch) {
  const toggleBtn = watch.status === 'active'
    ? `<button type="button" class="rt-watch-table-act" data-act="pause" data-id="${watch.id}" title="Pause" aria-label="Pause">⏸</button>`
    : `<button type="button" class="rt-watch-table-act" data-act="resume" data-id="${watch.id}" title="Resume" aria-label="Resume">▶</button>`;
  const editBtn = `<button type="button" class="rt-watch-table-act" data-act="edit" data-id="${watch.id}" title="Edit" aria-label="Edit">✏️</button>`;
  const deleteHost = `<span data-delete-host data-watch-id="${watch.id}"></span>`;
  return `<span class="rt-watch-table-actions">${toggleBtn}${editBtn}${deleteHost}</span>`;
}

function watchFallbackName(watch) {
  const r = watch.campsite;
  if (r?.name) return r.loop_name ? `${r.loop_name} / ${r.name}` : r.name;
  return `Watch #${watch.id}`;
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
