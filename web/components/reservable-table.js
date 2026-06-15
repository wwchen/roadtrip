import { createAvailabilityPanel } from './availability-panel.js';
import { createRow, createTable, dash, linkList, plainLink } from './result-table.js';

export const reservableColumns = [
  {
    label: 'RID',
    colClass: 'col-rid',
    className: 'rid mono',
    render: (row) => plainLink({
      href: reservablePageUrl(row),
      text: row.rid || '',
    }),
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

export function createReservableRow(row, { className = 'result-row' } = {}) {
  return createRow(reservableColumns, row, { className });
}

export function createReservableRowGroup(
  row,
  {
    state = null,
    includeAvailability = true,
  } = {},
) {
  const rows = [
    createReservableRow(row, {
      className: 'result-row has-subrow',
    }),
  ];
  if (includeAvailability) {
    rows.push(createAvailabilityPanel(row.rid, state, { colspan: reservableColumns.length }));
  }
  return rows;
}

export function reservableRowGroupRenderer({
  stateForRow = () => null,
  includeAvailability = true,
} = {}) {
  return (row) => createReservableRowGroup(row, {
    state: stateForRow(row),
    includeAvailability,
  });
}

export function createReservableTable(
  rows,
  {
    rowRenderer = null,
    rowClassName = 'result-row',
  } = {},
) {
  return createTable({
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
  const links = ids
    .map((id) => String(id || '').trim())
    .filter(Boolean)
    .map((id) => plainLink({
      href: `/pois?id=${encodeURIComponent(id)}`,
      text: id,
    }));
  return links.length ? linkList(links, 'poi-id-list') : dash();
}
