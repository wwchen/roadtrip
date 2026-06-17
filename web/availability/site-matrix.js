// Reservable-by-date availability matrix for the campground drawer.
// It uses the same POI-scoped availability response as the week strip:
// each day carries available_reservable_ids, and the catalog rows come
// from /api/poi/{id}/reservables.

import { escapeHtml } from '../core.js';
import { renderSiteDetail } from './site-detail.js';
import { bookingLabel } from './booking-links.js';

const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DEFAULT_FILTERS = {
  query: '',
  loop: '',
  type: '',
  sort: 'open',
};
const SORT_OPTIONS = [
  ['open', 'Open first'],
  ['site', 'Site'],
  ['loop', 'Loop'],
  ['type', 'Type'],
];

export function renderSiteMatrix({
  state,
  reservables,
  days,
  error,
  selectedDate,
  siteColumnWidth,
  filters = DEFAULT_FILTERS,
  selectedSiteRid = null,
  loadingMore = false,
  loadMoreError = null,
  showToday = true,
  armedBook = null,
}) {
  const visibleDays = Array.isArray(days) ? days.filter((d) => d?.date) : [];
  if (visibleDays.length === 0) return '';

  if (state === 'loading') {
    return renderSiteMatrixSkeleton({
      days: visibleDays,
      siteColumnWidth,
      showToday,
    });
  }
  if (state === 'error') {
    return renderSection({
      meta: `${visibleDays.length} dates`,
      loadingMore,
      loadMoreError,
      showToday,
      body: `<div class="cg-site-matrix-status cg-site-matrix-error">${escapeHtml(error || "Couldn't load sites")} <a href="#" class="cg-sites-retry">Retry</a></div>`,
    });
  }

  const allRows = sortedReservables(reservables);
  if (allRows.length === 0) {
    return renderSection({
      meta: `${visibleDays.length} dates`,
      loadingMore,
      loadMoreError,
      showToday,
      body: '<div class="cg-site-matrix-status">No reservable sites found for this campground.</div>',
    });
  }

  const activeFilters = normalizeFilters(filters);
  const availabilityByDate = new Map(
    visibleDays.map((day) => [day.date, new Set(availableReservableIds(day))]),
  );
  const rows = sortReservables(filterReservables(allRows, activeFilters), activeFilters.sort, {
    availabilityByDate,
    selectedDate,
  });
  const tools = renderTools({
    filters: activeFilters,
    loopOptions: filterOptions(allRows, 'loop'),
    typeOptions: filterOptions(allRows, 'site_type'),
  });

  if (rows.length === 0) {
    return renderSection({
      meta: `0 of ${allRows.length} sites / ${visibleDays.length} dates`,
      tools,
      loadingMore,
      loadMoreError,
      showToday,
      body: '<div class="cg-site-matrix-status">No sites match these filters.</div>',
    });
  }

  const headers = visibleDays.map(dateHeaderHtml).join('');
  const bodyRows = rows
    .map((row) =>
      rowHtml(row, {
        availabilityByDate,
        selectedDate,
        selectedSiteRid,
        visibleDays,
        armedBook,
      }),
    )
    .join('');
  const siteLabel = rows.length === 1 ? 'site' : 'sites';
  const meta =
    rows.length === allRows.length
      ? `${rows.length} ${siteLabel} / ${visibleDays.length} dates`
      : `${rows.length} of ${allRows.length} sites / ${visibleDays.length} dates`;
  const widthStyle = matrixScrollStyle(siteColumnWidth, visibleDays.length);

  return renderSection({
    meta,
    tools,
    loadingMore,
    loadMoreError,
    showToday,
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

export function renderSiteMatrixSkeleton({
  days,
  siteColumnWidth,
  showToday = false,
  rowCount = 6,
} = {}) {
  const visibleDays = Array.isArray(days) ? days.filter((d) => d?.date) : [];
  const dateCount = visibleDays.length || 7;
  const headers = visibleDays.length > 0
    ? visibleDays.map(dateHeaderHtml).join('')
    : Array.from({ length: dateCount }, () => '<th scope="col" class="cg-site-matrix-date cg-site-matrix-skeleton-cell"></th>').join('');
  const bodyRows = Array.from({ length: rowCount }, () => {
    const cells = Array.from({ length: dateCount }, () => `
      <td class="cg-site-matrix-cell cg-site-matrix-skeleton-cell">
        <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-pill"></span>
      </td>
    `).join('');
    return `
      <tr>
        <th scope="row" class="cg-site-matrix-site cg-site-matrix-skeleton-cell">
          <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-name"></span>
          <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-meta"></span>
        </th>
        ${cells}
      </tr>
    `;
  }).join('');
  const widthStyle = matrixScrollStyle(siteColumnWidth, dateCount);

  return renderSection({
    meta: `${dateCount} dates`,
    tools: renderSkeletonTools(),
    showToday,
    body: `
      <div class="cg-site-matrix-scroll cg-site-matrix-skeleton" aria-busy="true"${widthStyle}>
        <table class="cg-site-matrix-table">
          <thead>
            <tr>
              <th scope="col" class="cg-site-matrix-site cg-site-matrix-site-heading">
                <span>Site</span>
              </th>
              ${headers}
            </tr>
          </thead>
          <tbody>${bodyRows}</tbody>
        </table>
      </div>
    `,
  });
}

function renderSection({
  meta,
  body,
  tools = '',
  loadingMore = false,
  loadMoreError = null,
  showToday = true,
}) {
  const status = loadingMore
    ? '<span class="cg-site-matrix-meta-status">Loading...</span>'
    : loadMoreError
      ? '<span class="cg-site-matrix-meta-status cg-site-matrix-meta-error">Load failed</span>'
      : '';
  const todayButton = showToday
    ? '<button type="button" class="cg-site-matrix-today" data-matrix-today>Today</button>'
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
          ${todayButton}
          <div class="cg-site-matrix-meta">${escapeHtml(meta)}${status ? ` ${status}` : ''}</div>
        </div>
      </div>
      ${tools}
      ${body}
    </section>
  `;
}

function renderSkeletonTools() {
  return `
    <div class="cg-site-matrix-tools cg-site-matrix-skeleton-tools" aria-hidden="true">
      <span class="cg-site-matrix-filter cg-site-matrix-skeleton-control"></span>
      <span class="cg-site-matrix-filter cg-site-matrix-skeleton-control"></span>
      <span class="cg-site-matrix-filter cg-site-matrix-skeleton-control"></span>
      <span class="cg-site-matrix-filter cg-site-matrix-skeleton-control"></span>
    </div>
  `;
}

function renderTools({ filters, loopOptions, typeOptions }) {
  return `
    <div class="cg-site-matrix-tools">
      <input
        type="search"
        class="cg-site-matrix-filter cg-site-matrix-search"
        data-matrix-filter="query"
        value="${escapeHtml(filters.query)}"
        placeholder="Filter sites"
        aria-label="Filter sites"
        autocomplete="off"
      >
      ${renderSelect({
        key: 'loop',
        label: 'All loops',
        value: filters.loop,
        options: loopOptions,
      })}
      ${renderSelect({
        key: 'type',
        label: 'All types',
        value: filters.type,
        options: typeOptions,
      })}
      ${renderSelect({
        key: 'sort',
        label: null,
        value: filters.sort,
        options: SORT_OPTIONS.map(([value, label]) => ({ value, label })),
      })}
    </div>
  `;
}

function renderSelect({ key, label, value, options }) {
  const base = label == null ? '' : `<option value="">${escapeHtml(label)}</option>`;
  const opts = options
    .map((option) => {
      const optionValue = typeof option === 'string' ? option : option.value;
      const optionLabel = typeof option === 'string' ? option : option.label;
      const selected = optionValue === value ? ' selected' : '';
      return `<option value="${escapeHtml(optionValue)}"${selected}>${escapeHtml(optionLabel)}</option>`;
    })
    .join('');
  return `
    <select class="cg-site-matrix-filter" data-matrix-filter="${escapeHtml(key)}" aria-label="${escapeHtml(selectAriaLabel(key))}">
      ${base}${opts}
    </select>
  `;
}

function selectAriaLabel(key) {
  if (key === 'loop') return 'Filter by loop';
  if (key === 'type') return 'Filter by site type';
  return 'Sort sites';
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
  if (loadMoreError) {
    return `<div class="cg-site-matrix-load-status cg-site-matrix-error">${escapeHtml(loadMoreError)}</div>`;
  }
  return '';
}

function dateHeaderHtml(day) {
  const date = day.date;
  if (day.placeholder) {
    return `
      <th scope="col" class="cg-site-matrix-date cg-site-matrix-skeleton-cell">
        <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-meta"></span>
      </th>
    `;
  }
  const parsed = new Date(`${date}T00:00:00Z`);
  const dow = DOW_LABELS[parsed.getUTCDay()] || '';
  const dayNum = parseInt(date.slice(8, 10), 10);
  return `
    <th scope="col" class="cg-site-matrix-date">
      <button type="button" class="cg-site-matrix-date-button" data-matrix-date="${escapeHtml(date)}">
        <span>${escapeHtml(dow)}</span>
        <strong>${Number.isFinite(dayNum) ? dayNum : escapeHtml(date)}</strong>
      </button>
    </th>
  `;
}

function rowHtml(row, context) {
  const siteLabel = siteName(row);
  const siteTitle = siteTitleText(row, siteLabel);
  const isSelected = String(row.rid) === String(context.selectedSiteRid);
  const rowClass = isSelected ? ' class="cg-site-matrix-row-selected"' : '';
  const cells = context.visibleDays
    .map((day) =>
      cellHtml({
        availableIds: context.availabilityByDate.get(day.date),
        day,
        row,
        selectedDate: context.selectedDate,
        siteLabel,
        armedBook: context.armedBook,
      }),
    )
    .join('');
  const detailRow = isSelected
    ? `
      <tr class="cg-site-matrix-detail-row">
        <td colspan="${1 + context.visibleDays.length}">
          ${renderSiteDetail({ site: row, selectedDate: null, selectedEndDate: null })}
        </td>
      </tr>
    `
    : '';
  return `
    <tr${rowClass}>
      <th scope="row" class="cg-site-matrix-site" title="${escapeHtml(siteTitle)}">
        ${siteLabelHtml(row, siteLabel, siteTitle)}
      </th>
      ${cells}
    </tr>
    ${detailRow}
  `;
}

function siteLabelHtml(row, siteLabel, siteTitle) {
  const loop = typeof row.loop === 'string' ? row.loop.trim() : '';
  const prefix = loop ? `<span class="cg-site-matrix-loop-prefix">${escapeHtml(loop)} / </span>` : '';
  return `
    <button
      type="button"
      class="cg-site-matrix-site-button"
      data-site-header-rid="${escapeHtml(row.rid)}"
      title="${escapeHtml(siteTitle)}"
      aria-label="View details for ${escapeHtml(siteTitle)}"
    >
      <span class="cg-site-matrix-site-title">${prefix}<span class="cg-site-matrix-name">${escapeHtml(siteLabel)}</span></span>
    </button>
  `;
}

function cellHtml({ row, day, availableIds, selectedDate, siteLabel, armedBook }) {
  if (day.placeholder) {
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-skeleton-cell">
        <span class="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-pill"></span>
      </td>
    `;
  }
  const state = cellState(row, day, availableIds);
  const isSelected = selectedDate === day.date;
  const selectedClass = isSelected ? ' is-selected' : '';
  const aria = `${siteLabel} ${day.date}: ${state.aria}`;

  if (state.kind !== 'available') {
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}" aria-label="${escapeHtml(aria)}">
        <span class="cg-site-matrix-cell-label">${escapeHtml(state.label)}</span>
      </td>
    `;
  }

  const armed = !!armedBook
    && String(armedBook.rid) === rowRid(row)
    && armedBook.date === day.date;
  const label = armed ? bookingLabel(row) : state.label;
  const armedClass = armed ? ' is-armed' : '';
  const ariaLabel = armed
    ? `${aria}; ${label}, click to open booking page`
    : `${aria}; click to book`;

  return `
    <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}">
      <button
        type="button"
        class="cg-site-matrix-cell-button${armedClass}"
        data-book-rid="${escapeHtml(rowRid(row))}"
        data-book-date="${escapeHtml(day.date)}"
        aria-label="${escapeHtml(ariaLabel)}"
      >
        ${escapeHtml(label)}
      </button>
    </td>
  `;
}

function siteTitleText(row, siteLabel) {
  const loop = typeof row.loop === 'string' ? row.loop.trim() : '';
  return loop ? `${loop} / ${siteLabel}` : siteLabel;
}

function cellState(row, day, availableIds) {
  if (availableIds?.has(rowRid(row))) {
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
  return Array.isArray(ids) ? ids.map(String) : [];
}

function sortedReservables(reservables) {
  return [...(Array.isArray(reservables) ? reservables : [])].sort(compareReservable);
}

function normalizeFilters(filters) {
  const sort = SORT_OPTIONS.some(([key]) => key === filters?.sort) ? filters.sort : DEFAULT_FILTERS.sort;
  return {
    query: typeof filters?.query === 'string' ? filters.query : '',
    loop: typeof filters?.loop === 'string' ? filters.loop : '',
    type: typeof filters?.type === 'string' ? filters.type : '',
    sort,
  };
}

function filterReservables(rows, filters) {
  const query = filters.query.trim().toLowerCase();
  return rows.filter((row) => {
    if (filters.loop && row.loop !== filters.loop) return false;
    if (filters.type && row.site_type !== filters.type) return false;
    if (!query) return true;
    const haystack = [
      siteTitleText(row, siteName(row)),
      siteName(row),
      row.loop,
      row.site_type,
      row.vendor_id,
      row.vendorId,
      row.rid,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return haystack.includes(query);
  });
}

function sortReservables(rows, sortKey, context) {
  return [...rows].sort((a, b) => {
    if (sortKey === 'open') {
      const ao = openDateCount(a, context.availabilityByDate);
      const bo = openDateCount(b, context.availabilityByDate);
      if (ao !== bo) return bo - ao;
      return compareReservable(a, b);
    }
    if (sortKey === 'site') return compareBySite(a, b);
    if (sortKey === 'loop') return compareReservable(a, b);
    if (sortKey === 'type') return compareByType(a, b);
    return compareReservable(a, b);
  });
}

function openDateCount(row, availabilityByDate) {
  const rid = rowRid(row);
  let count = 0;
  for (const ids of availabilityByDate.values()) {
    if (ids.has(rid)) count += 1;
  }
  return count;
}

function filterOptions(rows, key) {
  return [...new Set(rows.map((row) => row[key]).filter((value) => typeof value === 'string' && value.trim()))]
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

function siteName(row) {
  if (row.name) return row.name;
  if (row.vendor_id) return `Site #${row.vendor_id}`;
  return row.rid || '(unknown)';
}

function rowRid(row) {
  return String(row.rid);
}

function compareReservable(a, b) {
  const al = a.loop || '\uffff';
  const bl = b.loop || '\uffff';
  if (al !== bl) return al.localeCompare(bl);
  return compareBySite(a, b);
}

function compareBySite(a, b) {
  const an = a.name || a.vendor_id || '';
  const bn = b.name || b.vendor_id || '';
  return an.localeCompare(bn, undefined, { numeric: true });
}

function compareByType(a, b) {
  const at = a.site_type || '\uffff';
  const bt = b.site_type || '\uffff';
  if (at !== bt) return at.localeCompare(bt, undefined, { numeric: true });
  return compareReservable(a, b);
}

function numeric(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}
