// Per-POI reservable catalog list. Renders the rows from
// /api/poi/{id}/reservables under the week grid. Pure: takes context,
// returns HTML; the controller in availability-week.js owns state and
// click handling.
//
// Catalog ≠ availability. Each row tells you the site exists at this
// campground; whether it's open on a given date is the week grid's
// concern. We deliberately do NOT colorize rows by availability — that
// would imply a per-row roundtrip we aren't making.

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
 */
export function renderSiteList({ state, reservables, totalAtPoi, error, expanded }) {
  if (state === 'loading') {
    return renderSection({
      header: renderHeader({ count: null, expanded: false, disabled: true }),
      body: '<div class="cg-sites-skeleton" aria-busy="true">Loading sites…</div>',
    });
  }
  if (state === 'error') {
    return renderSection({
      header: renderHeader({ count: null, expanded: false, disabled: true }),
      body: `<div class="cg-sites-error">${escapeHtml(error || "Couldn't load sites")} · <a href="#" class="cg-sites-retry">Retry</a></div>`,
    });
  }
  // success
  const count = totalAtPoi ?? reservables.length;
  if (!count) return ''; // silently hide; nothing to browse
  const body = expanded ? renderRows(reservables) : '';
  return renderSection({
    header: renderHeader({ count, expanded, disabled: false }),
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

function renderHeader({ count, expanded, disabled }) {
  const label =
    count == null
      ? 'All sites'
      : `All sites (${count})`;
  const aria = expanded ? 'true' : 'false';
  const disabledAttr = disabled ? 'disabled' : '';
  return `
    <button type="button" class="cg-sites-toggle" aria-expanded="${aria}" ${disabledAttr}>
      <span class="cg-sites-label">${escapeHtml(label)}</span>
      <span class="cg-sites-chevron" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
    </button>
  `;
}

function renderRows(reservables) {
  if (!Array.isArray(reservables) || reservables.length === 0) {
    return '<div class="cg-sites-empty">No sites in catalog yet.</div>';
  }
  // Stable sort: loop alphabetical, then site name. Loop-less rows fall
  // to the bottom — that's what Aspira's resource-id-only rows look like.
  const sorted = [...reservables].sort(compareReservable);
  const rows = sorted.map(renderRow).join('');
  return `<ol class="cg-sites-rows">${rows}</ol>`;
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
