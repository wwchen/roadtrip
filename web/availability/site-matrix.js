// Campsite-by-date availability matrix for the campground drawer.

import { escapeHtml } from '../core.js';
import { renderSiteDetail } from './site-detail.js';
import { hasReservationUrlTemplate } from './booking-links.js';
import {
  availabilityStatusMeta,
  normalizeAvailabilityStatus,
} from '../utils/availability-status.js';

const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DEFAULT_FILTERS = {
  query: '',
  loop: '',
  type: '',
  sort: 'available',
};
const SORT_OPTIONS = [
  ['site', 'Site'],
  ['available', 'Available first'],
  ['loop', 'Loop'],
  ['type', 'Type'],
];
// Cell statuses a user can set an availability watch on: occupied now but able
// to open up. Excludes available (already bookable), closed, unknown, and past.
// Note the hyphenated 'first-come' matches STATUS_META.first_come.kind.
const WATCHABLE_KINDS = new Set(['reserved', 'first-come']);
export function renderSiteMatrix({
  state,
  campsites,
  reservationUrlTemplates = {},
  days,
  error,
  selectedDate,
  siteColumnWidth,
  filters = DEFAULT_FILTERS,
  selectedSiteId = null,
  weekStart = null,
  showToday = true,
  armedBook = null,
  watchedDates = null,
  canWatch = false,
}) {
  const visibleDays = Array.isArray(days) ? days.filter((d) => d?.date) : [];
  if (visibleDays.length === 0) return '';

  const nav = renderWeekNav({ weekStart, visibleDays, showToday });

  if (state === 'loading') {
    return renderSiteMatrixSkeleton({
      days: visibleDays,
      siteColumnWidth,
      nav,
    });
  }
  if (state === 'error') {
    return renderSection({
      title: 'Sites by date',
      nav,
      body: `<div class="cg-site-matrix-status cg-site-matrix-error">${escapeHtml(error || "Couldn't load sites")} <a href="#" class="cg-sites-retry">Retry</a></div>`,
    });
  }

  const allRows = sortedCampsites(campsites, visibleDays);
  if (allRows.length === 0) {
    return renderSection({
      title: 'Sites by date',
      nav,
      body: '<div class="cg-site-matrix-status">No reservable sites found for this campground.</div>',
    });
  }

  const activeFilters = normalizeFilters(filters);
  const availabilityByDate = new Map(
    visibleDays.map((day) => [day.date, new Set(availableCampsiteIds(day))]),
  );
  const rows = sortCampsites(filterCampsites(allRows, activeFilters), activeFilters.sort, {
    availabilityByDate,
    selectedDate,
    visibleDays,
  });
  const tools = renderTools({
    filters: activeFilters,
    loopOptions: filterOptions(allRows, 'loop_name'),
    typeOptions: filterOptions(allRows, 'kind'),
  });

  if (rows.length === 0) {
    return renderSection({
      title: `0 of ${allRows.length} Sites by date`,
      tools,
      nav,
      body: '<div class="cg-site-matrix-status">No sites match these filters.</div>',
    });
  }

  const headers = visibleDays.map(dateHeaderHtml).join('');
  const bodyRows = rows
    .map((row) =>
      rowHtml(row, {
        availabilityByDate,
        selectedDate,
        selectedSiteId,
        visibleDays,
        armedBook,
        watchedDates,
        canWatch,
        reservationUrlTemplates,
      }),
    )
    .join('');
  const title =
    rows.length === allRows.length
      ? `${rows.length} Sites by date`
      : `${rows.length} of ${allRows.length} Sites by date`;
  const widthStyle = matrixScrollStyle(siteColumnWidth, visibleDays.length);

  return renderSection({
    title,
    tools,
    nav,
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
    `,
  });
}

export function renderSiteMatrixSkeleton({
  days,
  siteColumnWidth,
  weekStart = null,
  showToday = false,
  rowCount = 6,
} = {}) {
  const visibleDays = Array.isArray(days) ? days.filter((d) => d?.date) : [];
  const dateCount = visibleDays.length || 7;
  const nav = renderWeekNav({ weekStart, visibleDays, showToday });
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
    title: 'Sites by date',
    tools: renderSkeletonTools(),
    nav,
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
  title,
  body,
  tools = '',
  nav = '',
}) {
  return `
    <section class="cg-site-matrix" aria-label="Sites by date">
      <div class="cg-site-matrix-head">
        <div>
          <div class="cg-site-matrix-title">${escapeHtml(title)}</div>
          <div class="cg-site-matrix-legend">
            <span class="cg-site-matrix-key cg-site-matrix-key-available" title="Available">A</span>
            <span class="cg-site-matrix-key cg-site-matrix-key-first-come" title="First come first served">FF</span>
            <span class="cg-site-matrix-key cg-site-matrix-key-reserved" title="Reserved">R</span>
            <span class="cg-site-matrix-key cg-site-matrix-key-closed" title="Closed">C</span>
            <span class="cg-site-matrix-key cg-site-matrix-key-unknown" title="Unknown">?</span>
          </div>
        </div>
        <div class="cg-site-matrix-actions">
          ${nav}
        </div>
      </div>
      ${tools}
      ${body}
    </section>
  `;
}

function renderWeekNav({ weekStart, visibleDays, showToday }) {
  const startIso = typeof weekStart === 'string' && weekStart ? weekStart : visibleDays[0]?.date || '';
  const endIso = visibleDays[visibleDays.length - 1]?.date || startIso;
  const todayBtn = showToday
    ? '<button type="button" class="cg-week-today" aria-label="Jump to earliest date">Earliest</button>'
    : '';
  const todayClass = showToday ? ' has-today' : ' no-today';
  return `
    <div class="cg-week-nav${todayClass}" role="group" aria-label="Week navigation">
      ${todayBtn}
      <button type="button" class="cg-week-prev" aria-label="Previous week">‹</button>
      <button type="button" class="cg-week-label" aria-label="Pick a date">${escapeHtml(formatWeekLabel(startIso, endIso))}</button>
      <button type="button" class="cg-week-next" aria-label="Next week">›</button>
    </div>
  `;
}

function formatWeekLabel(startIso, endIso) {
  if (!startIso) return '';
  const start = new Date(`${startIso}T00:00:00Z`);
  const end = new Date(`${endIso || startIso}T00:00:00Z`);
  if (Number.isNaN(start.getTime())) return startIso;
  const fmt = (d, opts) => d.toLocaleDateString('en-US', { ...opts, timeZone: 'UTC' });
  const sameMonth = start.getUTCMonth() === end.getUTCMonth() && start.getUTCFullYear() === end.getUTCFullYear();
  if (sameMonth) {
    return `${fmt(start, { month: 'short', day: 'numeric' })} – ${fmt(end, { day: 'numeric' })}, ${start.getUTCFullYear()}`;
  }
  return `${fmt(start, { month: 'short', day: 'numeric' })} – ${fmt(end, { month: 'short', day: 'numeric' })}, ${start.getUTCFullYear()}`;
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
  const props = [`--rt-site-dates-width: ${datesWidth}px;`];
  if (typeof siteColumnWidth === 'number' && Number.isFinite(siteColumnWidth)) {
    props.push(`--rt-site-column-width: ${Math.round(siteColumnWidth)}px;`);
  }
  return ` style="${props.join(' ')}"`;
}

function dateHeaderHtml(day) {
  const date = day.date;
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
  const isSelected = rowId(row) === String(context.selectedSiteId);
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
        watchedDates: context.watchedDates,
        canWatch: context.canWatch,
        reservationUrlTemplates: context.reservationUrlTemplates,
      }),
    )
    .join('');
  const detailRow = isSelected
    ? `
      <tr class="cg-site-matrix-detail-row">
        <td colspan="${1 + context.visibleDays.length}">
          ${renderSiteDetail({
            site: row,
            selectedDate: null,
            selectedEndDate: null,
            reservationUrlTemplates: context.reservationUrlTemplates,
          })}
        </td>
      </tr>
    `
    : '';
  return `
    <tr${rowClass}>
      <th scope="row" class="cg-site-matrix-site" title="${escapeHtml(siteTitle)}">
        ${siteLabelHtml(row, siteLabel, siteTitle, isSelected)}
      </th>
      ${cells}
    </tr>
    ${detailRow}
  `;
}

function siteLabelHtml(row, siteLabel, siteTitle, isSelected) {
  const loop = typeof row.loop_name === 'string' ? row.loop_name.trim() : '';
  const prefix = loop ? `<span class="cg-site-matrix-loop-prefix">${escapeHtml(loop)} / </span>` : '';
  return `
    <button
      type="button"
      class="cg-site-matrix-site-button"
      data-site-header-campsite-id="${escapeHtml(rowId(row))}"
      title="${escapeHtml(siteTitle)}"
      aria-label="View details for ${escapeHtml(siteTitle)}"
      aria-expanded="${isSelected ? 'true' : 'false'}"
    >
      <span class="cg-site-matrix-site-title">${prefix}<span class="cg-site-matrix-name">${escapeHtml(siteLabel)}</span></span>
    </button>
  `;
}

function cellHtml({
  row,
  day,
  availableIds,
  selectedDate,
  siteLabel,
  armedBook,
  watchedDates,
  canWatch,
  reservationUrlTemplates,
}) {
  const state = cellState(row, day, availableIds);
  const isSelected = selectedDate === day.date;
  const selectedClass = isSelected ? ' is-selected' : '';
  const aria = `${siteLabel} ${day.date}: ${state.aria}`;

  if (state.kind !== 'available') {
    // Reserved / first-come cells become watch buttons: tap to set (or manage)
    // an availability watch on that day for this POI.
    const watched = !!watchedDates && watchedDates.has(day.date);
    if (WATCHABLE_KINDS.has(state.kind) && (canWatch || watched)) {
      const watchedClass = watched ? ' is-watched' : '';
      const watchAria = watched
        ? `${aria}; availability watch set, tap to manage`
        : `${aria}; tap to set an availability watch`;
      return `
        <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}">
          <button
            type="button"
            class="cg-site-matrix-cell-button cg-site-matrix-cell-watch${watchedClass}"
            data-watch-date="${escapeHtml(day.date)}"
            aria-label="${escapeHtml(watchAria)}"
          >${escapeHtml(state.label)}</button>
        </td>
      `;
    }
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}" aria-label="${escapeHtml(aria)}">
        <span class="cg-site-matrix-cell-button">${escapeHtml(state.label)}</span>
      </td>
    `;
  }

  if (!hasReservationUrlTemplate(row, reservationUrlTemplates)) {
    return `
      <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}" aria-label="${escapeHtml(aria)}">
        <span class="cg-site-matrix-cell-button">${escapeHtml(state.label)}</span>
      </td>
    `;
  }

  const armed = !!armedBook
    && String(armedBook.campsiteId) === rowId(row)
    && armedBook.date === day.date;
  const label = armed ? 'Book' : state.label;
  const armedClass = armed ? ' is-armed' : '';
  const ariaLabel = armed
    ? `${aria}; ${label}, click to open booking page`
    : `${aria}; click to book`;

  return `
    <td class="cg-site-matrix-cell cg-site-matrix-cell-${state.kind}${selectedClass}">
      <button
        type="button"
        class="cg-site-matrix-cell-button${armedClass}"
        data-book-campsite-id="${escapeHtml(rowId(row))}"
        data-book-date="${escapeHtml(day.date)}"
        aria-label="${escapeHtml(ariaLabel)}"
      >
        ${escapeHtml(label)}
      </button>
    </td>
  `;
}

function siteTitleText(row, siteLabel) {
  const loop = typeof row.loop_name === 'string' ? row.loop_name.trim() : '';
  return loop ? `${loop} / ${siteLabel}` : siteLabel;
}

function cellState(row, day, availableIds) {
  const campsiteId = rowId(row);
  const directStatus = campsiteStatus(day, campsiteId);
  if (directStatus) return availabilityStatusMeta(directStatus);
  if (availableIds?.has(campsiteId)) return availabilityStatusMeta('available');

  const status = normalizeAvailabilityStatus(day.status);
  if (status === 'available' && availableIds) return availabilityStatusMeta('reserved');
  return availabilityStatusMeta(status);
}

function campsiteStatus(day, campsiteId) {
  const statuses = day?.campsite_statuses;
  if (!statuses || typeof statuses !== 'object') return null;
  if (!Object.prototype.hasOwnProperty.call(statuses, campsiteId)) return null;
  return normalizeAvailabilityStatus(statuses[campsiteId]);
}

function availableCampsiteIds(day) {
  const ids = day?.available_campsite_ids;
  return Array.isArray(ids) ? ids.map(String) : [];
}

function sortedCampsites(campsites, days = []) {
  const catalogRows = Array.isArray(campsites) ? campsites : [];
  const rows = catalogRows.length > 0 ? catalogRows : fallbackCampsitesFromDays(days);
  return [...rows].sort(compareCampsite);
}

function fallbackCampsitesFromDays(days) {
  const ids = new Set();
  for (const day of Array.isArray(days) ? days : []) {
    const statuses = day?.campsite_statuses;
    if (statuses && typeof statuses === 'object') {
      Object.keys(statuses).forEach((id) => ids.add(String(id)));
    }
    availableCampsiteIds(day).forEach((id) => ids.add(String(id)));
  }
  return [...ids].sort().map(fallbackCampsite);
}

function fallbackCampsite(id) {
  return {
    id: String(id),
    data_provider_ref: String(id),
  };
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

function filterCampsites(rows, filters) {
  const query = filters.query.trim().toLowerCase();
  return rows.filter((row) => {
    if (filters.loop && row.loop_name !== filters.loop) return false;
    if (filters.type && row.kind !== filters.type) return false;
    if (!query) return true;
    const haystack = [
      siteTitleText(row, siteName(row)),
      siteName(row),
      row.loop_name,
      row.kind,
      row.kind_listed,
      row.data_provider,
      row.data_provider_ref,
      row.id,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return haystack.includes(query);
  });
}

function sortCampsites(rows, sortKey, context) {
  return [...rows].sort((a, b) => {
    if (sortKey === 'available') {
      const ao = availableDateCount(a, context.availabilityByDate, context.visibleDays);
      const bo = availableDateCount(b, context.availabilityByDate, context.visibleDays);
      if (ao !== bo) return bo - ao;
      return compareCampsite(a, b);
    }
    if (sortKey === 'site') return compareBySite(a, b);
    if (sortKey === 'loop') return compareCampsite(a, b);
    if (sortKey === 'type') return compareByType(a, b);
    return compareCampsite(a, b);
  });
}

function availableDateCount(row, availabilityByDate, days = []) {
  const campsiteId = rowId(row);
  let count = 0;
  for (const day of days) {
    const status = campsiteStatus(day, campsiteId);
    if (status === 'available') count += 1;
    else if (!status && availabilityByDate.get(day.date)?.has(campsiteId)) count += 1;
  }
  return count;
}

function filterOptions(rows, key) {
  return [...new Set(rows.map((row) => row[key]).filter((value) => typeof value === 'string' && value.trim()))]
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

function siteName(row) {
  if (row.name) return row.name;
  if (row.data_provider_ref) return `Site #${row.data_provider_ref}`;
  return row.id != null ? `Site #${row.id}` : '(unknown)';
}

function rowId(row) {
  return String(row.id);
}

function compareCampsite(a, b) {
  const al = a.loop_name || '\uffff';
  const bl = b.loop_name || '\uffff';
  if (al !== bl) return al.localeCompare(bl);
  return compareBySite(a, b);
}

function compareBySite(a, b) {
  const an = a.name || a.data_provider_ref || '';
  const bn = b.name || b.data_provider_ref || '';
  return an.localeCompare(bn, undefined, { numeric: true });
}

function compareByType(a, b) {
  const at = a.kind || '\uffff';
  const bt = b.kind || '\uffff';
  if (at !== bt) return at.localeCompare(bt, undefined, { numeric: true });
  return compareCampsite(a, b);
}
