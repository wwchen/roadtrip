// Per-POI reservable list. With no selected day this renders the catalog from
// /api/poi/{id}/reservables. With a selected day it filters the catalog by
// the week availability response's available_reservable_ids so the visible
// rows match the selected day's availability count.

import { escapeHtml } from '../core.js';

/**
 * The "Browse all sites at this campground" panel.
 *
 * Loading: shows a single skeleton row.
 * Error:   shows the error text + a retry link (controller wires it).
 * Empty:   hides the section entirely (a campground with zero linked
 *          reservables is the joiner-not-yet-run case; nothing useful
 *          to show).
 * Loaded:  collapsed summary line by default; expands to a row list.
 *
 * @param {object} args
 * @param {'loading'|'success'|'error'} args.state
 * @param {Array<object>}        args.reservables  Rows from BE (rid/name/loop/site_type).
 * @param {number|null}          args.totalAtPoi   total_at_poi from BE.
 * @param {string|null}          args.error
 * @param {boolean}               args.expanded
 * @param {object|null}           args.selectedDay  Per-day availability row.
 */
export function renderSiteList({ state, reservables, totalAtPoi, error, expanded, selectedDay = null }) {
  if (state === 'loading') {
    return renderSection({
      header: renderHeader({ count: null, expanded: false, disabled: true, mode: listMode(selectedDay) }),
      body: '<div class="cg-sites-skeleton" aria-busy="true">Loading sites…</div>',
    });
  }
  if (state === 'error') {
    return renderSection({
      header: renderHeader({ count: null, expanded: false, disabled: true, mode: listMode(selectedDay) }),
      body: `<div class="cg-sites-error">${escapeHtml(error || "Couldn't load sites")} · <a href="#" class="cg-sites-retry">Retry</a></div>`,
    });
  }
  // success
  const availableIds = availableReservableIds(selectedDay);
  const mode = availableIds ? 'available' : 'all';
  const rows = mode === 'available'
    ? reservablesForIds(reservables, availableIds)
    : reservables;
  const count = mode === 'available' ? rows.length : totalAtPoi ?? reservables.length;
  if (!count && mode === 'all') return ''; // silently hide; nothing to browse
  const body = expanded ? renderRows(rows, mode) : '';
  return renderSection({
    header: renderHeader({ count, expanded, disabled: false, mode }),
    body,
  });
}

function renderSection({ header, body }) {
  return `
    <section class="cg-sites">
      ${header}
      ${body}
    </section>
  `;
}

function renderHeader({ count, expanded, disabled, mode }) {
  const label =
    count == null
      ? (mode === 'available' ? 'Available sites' : 'All sites')
      : `${mode === 'available' ? 'Available sites' : 'All sites'} (${count})`;
  const aria = expanded ? 'true' : 'false';
  const disabledAttr = disabled ? 'disabled' : '';
  return `
    <button type="button" class="cg-sites-toggle" aria-expanded="${aria}" ${disabledAttr}>
      <span class="cg-sites-label">${escapeHtml(label)}</span>
      <span class="cg-sites-chevron" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
    </button>
  `;
}

function renderRows(reservables, mode) {
  if (!Array.isArray(reservables) || reservables.length === 0) {
    const label = mode === 'available' ? 'No available sites for this date.' : 'No sites in catalog yet.';
    return `<div class="cg-sites-empty">${escapeHtml(label)}</div>`;
  }
  // Stable sort: loop alphabetical, then site name. Loop-less rows fall
  // to the bottom — that's what Aspira's resource-id-only rows look like.
  const sorted = [...reservables].sort(compareReservable);
  const rows = sorted.map(renderRow).join('');
  return `<ol class="cg-sites-rows">${rows}</ol>`;
}

function listMode(selectedDay) {
  return availableReservableIds(selectedDay) ? 'available' : 'all';
}

function availableReservableIds(day) {
  if (!day) return null;
  const ids = day.available_reservable_ids ?? day.availableReservableIds;
  return Array.isArray(ids) ? ids : null;
}

function reservablesForIds(reservables, ids) {
  const byRid = new Map((Array.isArray(reservables) ? reservables : []).map((r) => [r.rid, r]));
  return ids.map((rid) => byRid.get(rid) || fallbackReservable(rid));
}

function fallbackReservable(rid) {
  const parts = String(rid).split(':');
  const vendorId = parts.slice(2).join(':') || String(rid);
  return { rid, vendor_id: vendorId };
}

function renderRow(r) {
  const name = r.name || formatFallbackName(r);
  const loopLine = r.loop ? `<div class="cg-sites-row-loop">${escapeHtml(r.loop)}</div>` : '';
  const typeTag = r.site_type
    ? `<span class="cg-sites-row-type">${escapeHtml(r.site_type)}</span>`
    : '';
  return `
    <li class="cg-sites-row" data-rid="${escapeHtml(r.rid)}">
      <div class="cg-sites-row-main">
        <div class="cg-sites-row-name">${escapeHtml(name)}</div>
        ${loopLine}
      </div>
      ${typeTag}
    </li>
  `;
}

/**
 * Aspira `/api/availability/map` doesn't ship per-resource names — only
 * resource ids. Show "Site #<vendor_id>" rather than "(unnamed)".
 */
function formatFallbackName(r) {
  if (r.vendor_id) return `Site #${r.vendor_id}`;
  return r.rid || '(unknown)';
}

function compareReservable(a, b) {
  const al = a.loop || '￿';
  const bl = b.loop || '￿';
  if (al !== bl) return al.localeCompare(bl);
  const an = a.name || a.vendor_id || '';
  const bn = b.name || b.vendor_id || '';
  return an.localeCompare(bn, undefined, { numeric: true });
}
