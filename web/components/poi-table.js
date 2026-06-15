import { dash, escapeHtml, renderTable } from './result-table.js';

export const poiColumns = [
  {
    label: 'ID',
    colClass: 'col-id',
    className: 'mono',
    render: (row) => `
      <a class="name-link" href="${escapeHtml(poiPageUrl(row))}">${escapeHtml(row.id ?? '')}</a>
    `,
  },
  {
    label: 'Name',
    colClass: 'col-name',
    className: 'name',
    render: (row) => escapeHtml(row.name || 'unknown'),
  },
  {
    label: 'Category',
    colClass: 'col-category',
    render: (row) => dash(row.category),
  },
  {
    label: 'Region',
    colClass: 'col-region',
    render: (row) => dash(row.region),
  },
  {
    label: 'Coordinates',
    colClass: 'col-coordinates',
    className: 'mono',
    render: (row) => escapeHtml(formatCoords(row.lng, row.lat)),
  },
];

export function poiTableHtml(rows, { rowRenderer = null } = {}) {
  return renderTable({
    columns: poiColumns,
    rows,
    className: 'poi-table',
    wrapClassName: 'poi-table-wrap table-wrap',
    rowClassName: 'result-row',
    rowRenderer,
  });
}

export function poiPageUrl(row) {
  return `/?poi=${encodeURIComponent(row.id ?? '')}`;
}

function formatCoords(lng, lat) {
  const x = Number(lng);
  const y = Number(lat);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return '-';
  return `${y.toFixed(5)}, ${x.toFixed(5)}`;
}
