import { availabilityPanelHtml } from './availability-panel.js';
import { renderSiteDetail } from '../availability/site-detail.js';
import { dash, escapeHtml, expanderButton, linkChip, links, renderRow, renderTable } from './result-table.js';

export const reservableColumns = [
  {
    label: 'RID',
    colClass: 'col-rid',
    className: 'rid mono',
    render: (row) => `
      <a href="${escapeHtml(reservablePageUrl(row))}">${escapeHtml(row.rid || '')}</a>
    `,
  },
  {
    label: 'Name',
    colClass: 'col-name',
    className: 'name',
    render: (row) => reservableNameButton(row),
  },
  {
    label: 'Loop',
    colClass: 'col-loop',
    render: (row) => dash(row.loop),
  },
  {
    label: 'Site Type',
    colClass: 'col-site-type',
    render: (row) => dash(row.site_type),
  },
  {
    label: 'POIs',
    colClass: 'col-pois',
    className: 'poi-ids mono',
    render: (row) => poiIdLinks(row),
  },
  {
    label: 'Links',
    colClass: 'col-links',
    render: (row) => defaultReservableLinks(row),
  },
];

export function reservableRowHtml(row, { linksHtml = defaultReservableLinks(row), className = 'result-row' } = {}) {
  return renderRow(
    columnsWithLinks(() => linksHtml),
    row,
    { className },
  );
}

export function reservableRowGroupHtml(
  row,
  {
    state = null,
    linksHtml = reservableDetailLink(row, state),
  } = {},
) {
  const mode = rowPanelMode(state);
  return [
    reservableRowHtml(row, {
      className: `result-row${mode ? ' has-subrow is-expanded' : ''}`,
      linksHtml,
    }),
    reservablePanelRowHtml(row, state, mode),
  ].join('');
}

export function reservableRowGroupRenderer({
  stateForRow = () => null,
  linksForRow = reservableDetailLink,
} = {}) {
  return (row) => {
    const state = stateForRow(row);
    return reservableRowGroupHtml(row, {
      state,
      linksHtml: linksForRow(row, state),
    });
  };
}

export function reservableTableHtml(
  rows,
  {
    linksForRow = defaultReservableLinks,
    rowRenderer = null,
    rowClassName = 'result-row',
  } = {},
) {
  const columns = columnsWithLinks(linksForRow);
  return renderTable({
    columns,
    rows,
    className: 'reservables-table',
    wrapClassName: 'reservables-table-wrap table-wrap',
    rowClassName,
    rowRenderer,
  });
}

export function defaultReservableLinks(row) {
  return links([
    linkChip({
      href: reservableJsonUrl(row.rid || ''),
      text: 'Reservable',
      kind: 'JSON',
    }),
    linkChip({
      href: reservableAvailabilityJsonUrl(row.rid || ''),
      text: 'Availability',
      kind: 'JSON',
    }),
    linkChip({
      href: snapshotHistoryUrl(row.rid || ''),
      text: 'History',
      kind: 'PAGE',
      target: '_self',
    }),
  ]);
}

export function snapshotHistoryUrl(rid) {
  const qs = new URLSearchParams({ tab: 'snapshots', reservable_rid: rid });
  return `/availability?${qs}`;
}

export function reservableDetailLink(row, state = null) {
  return links([
    linkChip({
      href: reservableJsonUrl(row.rid || ''),
      text: 'Reservable',
      kind: 'JSON',
    }),
    expanderButton({
      action: 'toggle-availability',
      idName: 'rid',
      id: row.rid || '',
      label: 'Availability',
      expanded: rowPanelMode(state) === 'availability',
    }),
    linkChip({
      href: snapshotHistoryUrl(row.rid || ''),
      text: 'History',
      kind: 'PAGE',
      target: '_self',
    }),
  ]);
}

function reservableNameButton(row) {
  const label = row.name || row.rid || '';
  if (!label) return dash(null);
  return `
    <button
      class="text-link-button name-link"
      type="button"
      data-action="toggle-reservable-detail"
      data-rid="${escapeHtml(row.rid || '')}"
    >${escapeHtml(label)}</button>
  `;
}

function rowPanelMode(state) {
  if (state?.mode === 'details' || state?.mode === 'availability') return state.mode;
  if (state?.expanded) return 'availability';
  return null;
}

function reservablePanelRowHtml(row, state, mode) {
  if (!mode) return '';
  const rid = row.rid || '';
  const body = mode === 'details'
    ? renderSiteDetail({ site: row })
    : availabilityPanelHtml(rid, state, { colspan: reservableColumns.length, row });
  if (mode === 'availability') return body;
  return `
    <tr class="reservable-panel-row is-expanded" data-panel-rid="${escapeHtml(rid)}">
      <td colspan="${reservableColumns.length}">
        <div class="reservable-inline-panel">
          ${body}
        </div>
      </td>
    </tr>
  `;
}

export function reservablePageUrl(row) {
  return `/reservables?id=${encodeURIComponent(row.rid || '')}`;
}

export function reservableJsonUrl(rid) {
  return `/api/reservable/${encodeURIComponent(rid)}`;
}

export function reservableAvailabilityJsonUrl(rid, { startDate = utcYmd(new Date()), endDate } = {}) {
  const resolvedEndDate = endDate || utcYmd(addUtcDays(parseUtcYmd(startDate), 7));
  const params = new URLSearchParams({
    start_date: startDate,
    end_date: resolvedEndDate,
  });
  return `/api/reservable/${encodeURIComponent(rid)}/availability?${params}`;
}

function poiIdLinks(row) {
  const ids = Array.isArray(row.poi_ids) ? row.poi_ids : (Array.isArray(row.poiIds) ? row.poiIds : []);
  const filtered = ids.map((id) => String(id || '').trim()).filter(Boolean);
  if (filtered.length === 0) return dash(null);
  return `
    <div class="poi-id-list">
      ${filtered.map((id) => `<a href="/pois?id=${encodeURIComponent(id)}">${escapeHtml(id)}</a>`).join('')}
    </div>
  `;
}

function columnsWithLinks(linksForRow) {
  return reservableColumns.map((column) => {
    if (column.label !== 'Links') return column;
    return { ...column, render: linksForRow };
  });
}

function utcYmd(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function parseUtcYmd(value) {
  return new Date(`${value}T00:00:00Z`);
}

function addUtcDays(date, days) {
  const next = new Date(date);
  next.setUTCDate(date.getUTCDate() + days);
  return next;
}
