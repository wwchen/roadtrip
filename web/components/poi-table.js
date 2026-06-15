import { dash, escapeHtml, expanderButton, renderRow, renderTable } from './result-table.js';

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
    rowRenderer,
  });
}

export function poiRowHtml(row, { expanded = false } = {}) {
  return renderRow(poiColumns, row, {
    className: `result-row has-subrow${expanded ? ' is-expanded' : ''}`,
  });
}

export function poiReservablesRowHtml(row, state, { contentHtml = '' } = {}) {
  const id = String(row.id);
  const expanded = !!state?.expanded;
  return `
    <tr class="reservables-row${expanded ? ' is-expanded' : ''}" data-panel-poi-id="${escapeHtml(id)}">
      <td colspan="${poiColumns.length}">
        <div class="reservables-panel">
          <div class="reservables-heading">
            <div class="reservables-title">
              ${expanderButton({
                action: 'toggle-reservables',
                idName: 'poi-id',
                id,
                label: 'Reservables',
                expanded,
              })}
            </div>
            <a class="mono" href="${escapeHtml(poiReservablesJsonUrl(id))}" target="_blank" rel="noreferrer">${escapeHtml(poiReservablesJsonUrl(id))}</a>
          </div>
          ${expanded ? contentHtml : ''}
        </div>
      </td>
    </tr>
  `;
}

export function poiPageUrl(row) {
  return `/?poi=${encodeURIComponent(row.id ?? '')}`;
}

export function poiJsonUrl(id) {
  return `/api/pois/${encodeURIComponent(id ?? '')}`;
}

export function poiReservablesJsonUrl(id) {
  return `/api/poi/${encodeURIComponent(id ?? '')}/reservables`;
}

function formatCoords(lng, lat) {
  const x = Number(lng);
  const y = Number(lat);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return '-';
  return `${y.toFixed(5)}, ${x.toFixed(5)}`;
}
