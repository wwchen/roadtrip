import { availabilityPanelHtml } from './availability-panel.js';
import { dash, escapeHtml, renderRow, renderTable } from './result-table.js';

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
];

export function reservableRowHtml(row, { className = 'result-row' } = {}) {
  return renderRow(
    reservableColumns,
    row,
    { className },
  );
}

export function reservableRowGroupHtml(
  row,
  {
    state = null,
    includeAvailability = true,
  } = {},
) {
  return [
    reservableRowHtml(row, {
      className: 'result-row has-subrow',
    }),
    includeAvailability ? availabilityPanelHtml(row.rid, state, { colspan: reservableColumns.length }) : '',
  ].join('');
}

export function reservableRowGroupRenderer({
  stateForRow = () => null,
  includeAvailability = true,
} = {}) {
  return (row) => reservableRowGroupHtml(row, {
    state: stateForRow(row),
    includeAvailability,
  });
}

export function reservableTableHtml(
  rows,
  {
    rowRenderer = null,
    rowClassName = 'result-row',
  } = {},
) {
  return renderTable({
    columns: reservableColumns,
    rows,
    className: 'reservables-table',
    wrapClassName: 'reservables-table-wrap table-wrap',
    rowClassName,
    rowRenderer,
  });
}

export function reservablePageUrl(row) {
  return `/reservables?id=${encodeURIComponent(row.rid || '')}`;
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
