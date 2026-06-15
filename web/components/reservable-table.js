import { availabilityPanelHtml } from './availability-panel.js';
import { dash, escapeHtml, linkChip, links, renderRow, renderTable } from './result-table.js';

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
    render: (row) => dash(row.name),
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
    linksHtml = reservableDetailLink(row),
    includeAvailability = true,
  } = {},
) {
  return [
    reservableRowHtml(row, {
      className: 'result-row has-subrow',
      linksHtml,
    }),
    includeAvailability ? availabilityPanelHtml(row.rid, state, { colspan: reservableColumns.length }) : '',
  ].join('');
}

export function reservableRowGroupRenderer({
  stateForRow = () => null,
  linksForRow = reservableDetailLink,
  includeAvailability = true,
} = {}) {
  return (row) => reservableRowGroupHtml(row, {
    state: stateForRow(row),
    linksHtml: linksForRow(row),
    includeAvailability,
  });
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
  ]);
}

export function reservableDetailLink(row) {
  return links([
    linkChip({
      href: reservableJsonUrl(row.rid || ''),
      text: 'Reservable',
      kind: 'JSON',
    }),
  ]);
}

export function reservablePageUrl(row) {
  return `/reservables?id=${encodeURIComponent(row.rid || '')}`;
}

export function reservableJsonUrl(rid) {
  return `/api/reservable/${encodeURIComponent(rid)}`;
}

export function reservableAvailabilityJsonUrl(rid, { days = 7, start = utcYmd(new Date()), minNights = 1 } = {}) {
  const params = new URLSearchParams({
    days: String(days),
    start,
    min_nights: String(minNights),
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
