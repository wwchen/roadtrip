// Reservable-by-date availability matrix for the campground drawer.
// It uses the same POI-scoped availability response as the week strip:
// each day carries available_reservable_ids, and the catalog rows come
// from /api/poi/{id}/reservables.

import { escapeHtml } from '../core.js';

const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

export function renderSiteMatrix({
  state,
  reservables,
  days,
  error,
  selectedDate,
  siteColumnWidth,
  loadingMore = false,
  loadMoreError = null,
}) {
  const visibleDays = Array.isArray(days) ? days.filter((d) => d?.date) : [];
  if (visibleDays.length === 0) return '';

  if (state === 'loading') {
    return renderSection({
      meta: `${visibleDays.length} dates`,
      loadingMore,
      loadMoreError,
      body: '<div class="cg-site-matrix-status" aria-busy="true">Loading sites...</div>',
    });
  }
  if (state === 'error') {
    return renderSection({
      meta: `${visibleDays.length} dates`,
      loadingMore,
      loadMoreError,
      body: `<div class="cg-site-matrix-status cg-site-matrix-error">${escapeHtml(error || "Couldn't load sites")} <a href="#" class="cg-sites-retry">Retry</a></div>`,
    });
  }

  const rows = sortedReservables(reservables);
  if (rows.length === 0) {
    return renderSection({
      meta: `${visibleDays.length} dates`,
      loadingMore,
      loadMoreError,
      body: '<div class="cg-site-matrix-status">No reservable sites found for this campground.</div>',
    });
  }

  const availabilityByDate = new Map(
    visibleDays.map((day) => [day.date, new Set(availableReservableIds(day))]),
  );
  const headers = visibleDays.map(dateHeaderHtml).join('');
  const bodyRows = rows.map((row) => rowHtml(row, visibleDays, availabilityByDate, selectedDate)).join('');
  const meta = `${rows.length} ${rows.length === 1 ? 'site' : 'sites'} / ${visibleDays.length} dates`;
  const widthStyle = matrixScrollStyle(siteColumnWidth, visibleDays.length);

  return renderSection({
    meta,
    loadingMore,
    loadMoreError,
    body: `
      <div class="cg-site-matrix-scroll"${widthStyle}>
        <table class="cg-site-matrix-table">
          <thead>
            <tr>
              <th scope="col" class="cg-site-matrix-site cg-site-matrix-site-heading">
                <span>Site</span>
                <button type="button" class="cg-site-matrix-resizer" data-site-column-resizer aria-label="Resize site column" title="Resize site column"></button>
              </th>
              ${headers}
            </tr>
          </thead>
          <tbody>${bodyRows}</tbody>
        </table>
      </div>
      ${renderLoadMoreStatus({ loadingMore, loadMoreError })}
    `,
  });
}

function renderSection({ meta, body, loadingMore = false, loadMoreError = null }) {
  const status = loadingMore
    ? '<span class="cg-site-matrix-meta-status">Loading...</span>'
    : loadMoreError
      ? '<span class="cg-site-matrix-meta-status cg-site-matrix-meta-error">Load failed</span>'
      : '';
  return `
    <section class="cg-site-matrix" aria-label="Sites by date">
      <div class="cg-site-matrix-head">
        <div>
          <div class="cg-site-matrix-title">Sites by date</div>
          <div class="cg-site-matrix-legend">
            <span class="cg-site-matrix-key cg-site-matrix-key-available">Open</span>
            <span class="cg-site-matrix-key cg-site-matrix-key-booked">Full</span>
            <span class="cg-site-matrix-key cg-site-matrix-key-closed">Closed</span>
          </div>
        </div>
        <div class="cg-site-matrix-actions">
          <button type="button" class="cg-site-matrix-today" data-matrix-today>Today</button>
          <div class="cg-site-matrix-meta">${escapeHtml(meta)}${status ? ` ${status}` : ''}</div>
        </div>
      </div>
      ${body}
    </section>
  `;
}

function matrixScrollStyle(siteColumnWidth, dateCount) {
  const datesWidth = Math.max(1, dateCount) * 66;
  const props = [`--cg-site-dates-width: ${datesWidth}px;`];
  if (typeof siteColumnWidth === 'number' && Number.isFinite(siteColumnWidth)) {
    props.push(`--cg-site-column-width: ${Math.round(siteColumnWidth)}px;`);
  }
  return ` style="${props.join(' ')}"`;
}

function renderLoadMoreStatus({ loadingMore, loadMoreError }) {
  if (loadingMore) {
    return '<div class="cg-site-matrix-load-status" aria-live="polite">Loading next week...</div>';
  }
  if (loadMoreError) {
    return `<div class="cg-site-matrix-load-status cg-site-matrix-error">${escapeHtml(loadMoreError)}</div>`;
  }
  return '';
}

function dateHeaderHtml(day) {
  const date = day.date;
  const parsed = new Date(`${date}T00:00:00Z`);
  const dow = DOW_LABELS[parsed.getUTCDay()] || '';
  const dayNum = parseInt(date.slice(8, 10), 10);
  return `
    <th scope="col" class="cg-site-matrix-date">
      <span>${escapeHtml(dow)}</span>
      <strong>${Number.isFinite(dayNum) ? dayNum : escapeHtml(date)}</strong>
    </th>
  `;
}

function rowHtml(row, days, availabilityByDate, selectedDate) {
  const siteLabel = siteName(row);
  const cells =
    days.map((day) => cellHtml(row, day, availabilityByDate.get(day.date), selectedDate, siteLabel)).join('');
  return `
    <tr>
      <th scope="row" class="cg-site-matrix-site" title="${escapeHtml(siteLabel)}">
        ${siteLabelHtml(row, siteLabel)}
      </th>
      ${cells}
    </tr>
  `;
}

function siteLabelHtml(row, siteLabel) {
  const loop = row.loop ? `<span class="cg-site-matrix-loop">${escapeHtml(row.loop)}</span>` : '';
  const type = row.site_type ? `<span class="cg-site-matrix-type">${escapeHtml(row.site_type)}</span>` : '';
  const text = `
    <span class="cg-site-matrix-name">${escapeHtml(siteLabel)}</span>
    ${loop}
    ${type}
  `;
  const url = row.reservation_url || row.reservationUrl || '';
  if (!url) return text;
  return `<a href="${escapeHtml(url)}" target="_blank" rel="noreferrer">${text}</a>`;
}

function cellHtml(row, day, availableIds, selectedDate, siteLabel) {
  const state = cellState(row, day, availableIds);
  const isSelected = selectedDate === day.date;
  const selectedClass = isSelected ? ' is-selected' : '';
  const aria = `${siteLabel} ${day.date}: ${state.aria}`;
  return `
    <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}">
      <button type="button" class="cg-site-matrix-cell-button" data-matrix-date="${escapeHtml(day.date)}" aria-label="${escapeHtml(aria)}">
        ${escapeHtml(state.label)}
      </button>
    </td>
  `;
}

function cellState(row, day, availableIds) {
  if (availableIds?.has(row.rid)) {
    return { kind: 'available', label: 'Open', aria: 'open' };
  }
  const total = numeric(day.total ?? day.totalAtPoi);
  const status = String(day.status || '').toLowerCase();
  if (status === 'closed' || total === 0) {
    return { kind: 'closed', label: 'Closed', aria: 'closed' };
  }
  return { kind: 'booked', label: 'Full', aria: 'full' };
}

function availableReservableIds(day) {
  const ids = day?.available_reservable_ids ?? day?.availableReservableIds;
  return Array.isArray(ids) ? ids : [];
}

function sortedReservables(reservables) {
  return [...(Array.isArray(reservables) ? reservables : [])].sort(compareReservable);
}

function siteName(row) {
  if (row.name) return row.name;
  if (row.vendor_id) return `Site #${row.vendor_id}`;
  return row.rid || '(unknown)';
}

function compareReservable(a, b) {
  const al = a.loop || '\uffff';
  const bl = b.loop || '\uffff';
  if (al !== bl) return al.localeCompare(bl);
  const an = a.name || a.vendor_id || '';
  const bn = b.name || b.vendor_id || '';
  return an.localeCompare(bn, undefined, { numeric: true });
}

function numeric(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
