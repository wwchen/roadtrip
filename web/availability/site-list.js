// Selected-day available reservable list. Hidden until a day is selected,
// then filters the POI catalog by the week availability response's
// available_reservable_ids so the visible rows match that day's count.

import { buildAspiraDeeplink } from '../aspira.js';
import { escapeHtml } from '../core.js';

/**
 * The "Available sites" panel for the selected date.
 *
 * Loading: shows a single skeleton row.
 * Error:   shows the error text + a retry link (controller wires it).
 * Empty:   shows a zero-state when the selected date has no open sites.
 * Loaded:  expanded by default when the user selects a date.
 *
 * @param {object} args
 * @param {'loading'|'success'|'error'} args.state
 * @param {Array<object>}        args.reservables  Rows from BE (rid/name/loop/site_type).
 * @param {number|null}          args.totalAtPoi   total_at_poi from BE.
 * @param {string|null}          args.error
 * @param {boolean}               args.expanded
 * @param {object|null}           args.selectedDay  Per-day availability row.
 * @param {number}                args.minNights
 * @param {string|null}           args.providerHost
 */
export function renderSiteList({
  state,
  reservables,
  error,
  expanded,
  selectedDay = null,
  minNights = 1,
  providerHost = null,
}) {
  const availableIds = availableReservableIds(selectedDay);
  if (availableIds == null) return '';

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
  const rows = reservablesForIds(reservables, availableIds);
  const body = expanded ? renderRows(rows, selectedDay, minNights, providerHost) : '';
  return renderSection({
    header: renderHeader({ count: rows.length, expanded, disabled: false }),
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
      ? 'Available sites'
      : `Available sites (${count})`;
  const aria = expanded ? 'true' : 'false';
  const disabledAttr = disabled ? 'disabled' : '';
  return `
    <button type="button" class="cg-sites-toggle" aria-expanded="${aria}" ${disabledAttr}>
      <span class="cg-sites-label">${escapeHtml(label)}</span>
      <span class="cg-sites-chevron" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
    </button>
  `;
}

function renderRows(reservables, selectedDay, minNights, providerHost) {
  if (!Array.isArray(reservables) || reservables.length === 0) {
    return '<div class="cg-sites-empty">No available sites for this date.</div>';
  }
  // Stable sort: loop alphabetical, then site name. Loop-less rows fall
  // to the bottom — that's what Aspira's resource-id-only rows look like.
  const sorted = [...reservables].sort(compareReservable);
  const rows = sorted.map((r) => renderRow(r, selectedDay.date, minNights, providerHost)).join('');
  return `<ol class="cg-sites-rows">${rows}</ol>`;
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
  const vendor = parts[1] || '';
  const vendorId = parts.slice(2).join(':') || String(rid);
  return { rid, vendor, vendor_id: vendorId };
}

function renderRow(r, selectedDate, minNights, providerHost) {
  const name = r.name || formatFallbackName(r);
  const loopLine = r.loop ? `<div class="cg-sites-row-loop">${escapeHtml(r.loop)}</div>` : '';
  const typeTag = r.site_type
    ? `<span class="cg-sites-row-type">${escapeHtml(r.site_type)}</span>`
    : '';
  const url = reservationUrl(r, selectedDate, minNights, providerHost);
  const inner = `
    <div class="cg-sites-row-main">
      <div class="cg-sites-row-name">${escapeHtml(name)}</div>
      ${loopLine}
    </div>
    ${typeTag}
  `;
  const body = url
    ? `<a class="cg-sites-row-link" href="${escapeHtml(url)}" target="_blank" rel="noreferrer">${inner}</a>`
    : inner;
  return `
    <li class="cg-sites-row" data-rid="${escapeHtml(r.rid)}">
      ${body}
    </li>
  `;
}

function reservationUrl(r, selectedDate, minNights, providerHost) {
  if (!selectedDate) return null;
  const endDate = checkoutDate(selectedDate, minNights);
  const vendor = r.vendor || parseRid(r.rid).vendor;
  const vendorId = r.vendor_id || r.vendorId || parseRid(r.rid).vendorId;
  if (vendor === 'recgov' && vendorId) {
    const siteId = encodeURIComponent(vendorId);
    return `https://www.recreation.gov/camping/campsites/${siteId}?startDate=${encodeURIComponent(selectedDate)}&endDate=${encodeURIComponent(endDate)}`;
  }
  if (vendor && vendor.startsWith('aspira_')) {
    const raw = r.raw || {};
    const host = providerHost || hostForAspiraVendor(vendor);
    const transactionLocationId = raw._parent_aspira_txn_loc;
    const mapId = raw._parent_aspira_map_id;
    if (host && transactionLocationId != null && mapId != null) {
      return buildAspiraDeeplink({
        host,
        transactionLocationId,
        mapId,
        resourceLocationId: raw._parent_aspira_resource_loc,
        startDate: selectedDate,
        endDate,
      });
    }
  }
  return null;
}

function parseRid(rid) {
  const parts = String(rid || '').split(':');
  return {
    vendor: parts[1] || '',
    vendorId: parts.slice(2).join(':'),
  };
}

function checkoutDate(selectedDate, minNights) {
  const date = new Date(`${selectedDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + Math.max(1, Number(minNights) || 1));
  return date.toISOString().slice(0, 10);
}

function hostForAspiraVendor(vendor) {
  switch (vendor) {
    case 'aspira_pc':
      return 'reservation.pc.gc.ca';
    case 'aspira_bc':
      return 'camping.bcparks.ca';
    case 'aspira_wa':
      return 'washington.goingtocamp.com';
    default:
      return null;
  }
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
