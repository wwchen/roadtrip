// Selected-day available reservable list. Hidden until a day is selected,
// then filters the POI catalog by the week availability response's
// available_reservable_ids so the visible rows match that day's count.

import { escapeHtml } from '../core.js';

/**
 * The "Available sites" panel for the selected date.
 *
 * Loading: shows a single skeleton row.
 * Error:   shows the error text + a retry link (controller wires it).
 * Empty:   hidden; the day-detail fallback owns no-site selected days.
 * Loaded:  expanded by default when the user selects a bookable date.
 *
 * @param {object} args
 * @param {'loading'|'success'|'error'} args.state
 * @param {Array<object>}        args.reservables  Rows from BE (rid/name/loop/site_type/reservation_url).
 * @param {string|null}          args.error
 * @param {boolean}               args.expanded
 * @param {object|null}           args.selectedDay  Per-day availability row.
 */
export function renderSiteList({
  state,
  reservables,
  error,
  expanded,
  selectedDay = null,
}) {
  const availableIds = availableReservableIds(selectedDay);
  if (availableIds == null || availableIds.length === 0) return '';
  const count = availableCount(selectedDay) ?? availableIds.length;
  const total = selectedDay.total ?? selectedDay.totalAtPoi ?? null;

  if (state === 'loading') {
    return renderSection({
      header: renderHeader({ count, total, expanded: false, disabled: true }),
      body: '<div class="cg-sites-skeleton" aria-busy="true">Loading sites…</div>',
    });
  }
  if (state === 'error') {
    return renderSection({
      header: renderHeader({ count, total, expanded: false, disabled: true }),
      body: `<div class="cg-sites-error">${escapeHtml(error || "Couldn't load sites")} · <a href="#" class="cg-sites-retry">Retry</a></div>`,
    });
  }
  // success
  const rows = reservablesForIds(reservables, availableIds);
  const body = expanded ? renderRows(rows) : '';
  return renderSection({
    header: renderHeader({ count, total, expanded, disabled: false }),
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

function renderHeader({ count, total, expanded, disabled }) {
  const label = renderHeaderLabel(count, total);
  const aria = expanded ? 'true' : 'false';
  const disabledAttr = disabled ? 'disabled' : '';
  return `
    <button type="button" class="cg-sites-toggle" aria-expanded="${aria}" ${disabledAttr}>
      <span class="cg-sites-label">${escapeHtml(label)}</span>
      <span class="cg-sites-chevron" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
    </button>
  `;
}

function renderHeaderLabel(count, total) {
  if (count == null) return 'Available sites';
  if (total != null) return `Available sites (${count} of ${total} sites)`;
  return `Available sites (${count})`;
}

function renderRows(reservables) {
  if (!Array.isArray(reservables) || reservables.length === 0) {
    return '<div class="cg-sites-empty">No available sites for this date.</div>';
  }
  // Stable sort: loop alphabetical, then site name. Loop-less rows fall
  // to the bottom — that's what Aspira's resource-id-only rows look like.
  const sorted = [...reservables].sort(compareReservable);
  const rows = sorted.map((r) => renderRow(r)).join('');
  return `<ol class="cg-sites-rows">${rows}</ol>`;
}

function availableReservableIds(day) {
  if (!day) return null;
  const ids = day.available_reservable_ids ?? day.availableReservableIds;
  return Array.isArray(ids) ? ids : null;
}

function availableCount(day) {
  const count = day?.available_count ?? day?.availableCount;
  return typeof count === 'number' ? count : null;
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

function renderRow(r) {
  const name = r.name || formatFallbackName(r);
  const loopLine = r.loop ? `<div class="cg-sites-row-loop">${escapeHtml(r.loop)}</div>` : '';
  const details = renderSiteDetails(r);
  const typeTag = r.site_type
    ? `<span class="cg-sites-row-type">${escapeHtml(r.site_type)}</span>`
    : '';
  const url = r.reservation_url || r.reservationUrl || null;
  const bookTag = url ? '<span class="cg-sites-row-book">Book</span>' : '';
  const side = typeTag || bookTag ? `<div class="cg-sites-row-side">${typeTag}${bookTag}</div>` : '';
  const inner = `
    <div class="cg-sites-row-main">
      <div class="cg-sites-row-name">${escapeHtml(name)}</div>
      ${loopLine}
      ${details}
    </div>
    ${side}
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

/**
 * Aspira `/api/availability/map` doesn't ship per-resource names — only
 * resource ids. Show "Site #<vendor_id>" rather than "(unnamed)".
 */
function formatFallbackName(r) {
  if (r.vendor_id) return `Site #${r.vendor_id}`;
  return r.rid || '(unknown)';
}

function renderSiteDetails(r) {
  const raw = r.raw && typeof r.raw === 'object' ? r.raw : {};
  const details = [capacityLabel(raw), descriptionSummary(raw.description)].filter(Boolean);
  if (details.length === 0) return '';
  return `<div class="cg-sites-row-details">${details.map(escapeHtml).join(' · ')}</div>`;
}

function capacityLabel(raw) {
  const min = numberValue(raw.min_capacity ?? raw.minCapacity ?? raw.min_num_people ?? raw.minNumPeople);
  const max = numberValue(raw.max_capacity ?? raw.maxCapacity ?? raw.max_num_people ?? raw.maxNumPeople);
  if (min != null && max != null && min !== max) return `Sleeps ${min}-${max}`;
  if (max != null) return `Sleeps up to ${max}`;
  if (min != null) return `Sleeps ${min}+`;
  return '';
}

function numberValue(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}

function descriptionSummary(value) {
  if (typeof value !== 'string') return '';
  const text = value
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!text) return '';
  return text.length > 120 ? `${text.slice(0, 117).trim()}...` : text;
}

function compareReservable(a, b) {
  const al = a.loop || '￿';
  const bl = b.loop || '￿';
  if (al !== bl) return al.localeCompare(bl);
  const an = a.name || a.vendor_id || '';
  const bn = b.name || b.vendor_id || '';
  return an.localeCompare(bn, undefined, { numeric: true });
}
