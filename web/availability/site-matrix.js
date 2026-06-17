// Reservable-by-date availability matrix for the campground drawer.
// It uses the same POI-scoped availability response as the week strip:
// each day carries available_reservable_ids, and the catalog rows come
// from /api/poi/{id}/reservables.

import { escapeHtml } from '../core.js';

const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DEFAULT_FILTERS = {
  query: '',
  loop: '',
  type: '',
  sort: 'fit',
};
const SORT_OPTIONS = [
  ['fit', 'Fit first'],
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
  minNights = 1,
  filters = DEFAULT_FILTERS,
  selectedSiteRid = null,
  loadingMore = false,
  loadMoreError = null,
  showToday = true,
}) {
  const visibleDays = Array.isArray(days) ? days.filter((d) => d?.date) : [];
  if (visibleDays.length === 0) return '';

  if (state === 'loading') {
    return renderSection({
      meta: `${visibleDays.length} dates`,
      loadingMore,
      loadMoreError,
      showToday,
      body: '<div class="cg-site-matrix-status" aria-busy="true">Loading sites...</div>',
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
  const fitStartsByDate = fitStartIndex(visibleDays, availabilityByDate, minNights);
  const rows = sortReservables(filterReservables(allRows, activeFilters), activeFilters.sort, {
    availabilityByDate,
    fitStartsByDate,
    minNights,
    selectedDate,
    visibleDays,
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
        fitStartsByDate,
        minNights,
        selectedDate,
        selectedSiteRid,
        visibleDays,
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
            <span class="cg-site-matrix-key cg-site-matrix-key-fit">Fits stay</span>
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

function rowHtml(row, context) {
  const siteLabel = siteName(row);
  const rowClass = String(row.rid) === String(context.selectedSiteRid) ? ' class="cg-site-matrix-row-selected"' : '';
  const cells = context.visibleDays
    .map((day) =>
      cellHtml({
        availableIds: context.availabilityByDate.get(day.date),
        day,
        fitIds: context.fitStartsByDate.get(day.date),
        minNights: context.minNights,
        row,
        selectedDate: context.selectedDate,
        siteLabel,
      }),
    )
    .join('');
  return `
    <tr${rowClass}>
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
  return `
    <button
      type="button"
      class="cg-site-matrix-site-button"
      data-site-detail-rid="${escapeHtml(row.rid)}"
      title="${escapeHtml(siteLabel)}"
      aria-label="View details for ${escapeHtml(siteLabel)}"
    >
      <span class="cg-site-matrix-name">${escapeHtml(siteLabel)}</span>
      ${loop}
      ${type}
    </button>
  `;
}

function cellHtml({ row, day, availableIds, fitIds, selectedDate, siteLabel, minNights }) {
  const state = cellState(row, day, availableIds);
  const isSelected = selectedDate === day.date;
  const isFit = minNights > 1 && state.kind === 'available' && fitIds?.has(rowRid(row));
  const selectedClass = isSelected ? ' is-selected' : '';
  const fitClass = isFit ? ' cg-site-matrix-cell-fit' : '';
  const label = isFit ? 'Fits' : state.label;
  const fitAria = isFit ? `; fits ${minNights} nights` : '';
  const aria = `${siteLabel} ${day.date}: ${state.aria}${fitAria}`;
  return `
    <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}${fitClass}">
      <button type="button" class="cg-site-matrix-cell-button" data-matrix-date="${escapeHtml(day.date)}" aria-label="${escapeHtml(aria)}">
        ${escapeHtml(label)}
      </button>
    </td>
  `;
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
    if (sortKey === 'fit') {
      const af = fitSortScore(a, context);
      const bf = fitSortScore(b, context);
      if (af !== bf) return bf - af;
      return compareReservable(a, b);
    }
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

function fitSortScore(row, { availabilityByDate, fitStartsByDate, minNights, selectedDate, visibleDays }) {
  const rid = rowRid(row);
  if (minNights <= 1) {
    const selectedOpen = selectedDate && availabilityByDate.get(selectedDate)?.has(rid) ? 1000 : 0;
    return selectedOpen + openDateCount(row, availabilityByDate);
  }
  const selectedFit = selectedDate && fitStartsByDate.get(selectedDate)?.has(rid) ? 1000 : 0;
  return (
    selectedFit +
    visibleDays.reduce((count, day) => count + (fitStartsByDate.get(day.date)?.has(rid) ? 1 : 0), 0)
  );
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

function fitStartIndex(days, availabilityByDate, minNights) {
  const nights = Math.max(1, Math.floor(numeric(minNights) || 1));
  const out = new Map();
  for (const day of days) {
    const startDate = day.date;
    const startIds = availabilityByDate.get(startDate) || new Set();
    if (nights <= 1) {
      out.set(startDate, new Set(startIds));
      continue;
    }
    const dates = stayDates(startDate, nights);
    if (!dates.every((date) => availabilityByDate.has(date))) {
      out.set(startDate, new Set());
      continue;
    }
    out.set(
      startDate,
      new Set([...startIds].filter((rid) => dates.every((date) => availabilityByDate.get(date)?.has(rid)))),
    );
  }
  return out;
}

function stayDates(startDate, nights) {
  const start = new Date(`${startDate}T00:00:00Z`);
  return Array.from({ length: nights }, (_, i) => {
    const d = new Date(start);
    d.setUTCDate(start.getUTCDate() + i);
    return d.toISOString().slice(0, 10);
  });
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
