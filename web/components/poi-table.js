import { createTable, dash, linkList, plainLink, rowApiLink } from './result-table.js';

export const poiColumns = [
  {
    label: 'ID',
    colClass: 'col-id',
    className: 'mono',
    render: (row) => plainLink({
      href: poiExplorerUrl(row),
      text: row.id ?? '',
    }),
  },
  {
    label: 'Name',
    colClass: 'col-name',
    className: 'name',
    render: (row) => row.name || 'unknown',
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
    render: (row) => formatCoords(row.lng, row.lat),
  },
  {
    label: 'Links',
    colClass: 'col-links',
    render: (row) => poiLinks(row),
  },
];

export function createPoiTable(rows, { rowRenderer = null } = {}) {
  return createTable({
    columns: poiColumns,
    rows,
    className: 'poi-table',
    wrapClassName: 'poi-table-wrap table-wrap',
    rowClassName: 'result-row',
    rowRenderer,
  });
}

export function poiExplorerUrl(row) {
  return `/pois?id=${encodeURIComponent(row.id ?? '')}`;
}

export function poiProductUrl(row) {
  return `/?poi=${encodeURIComponent(row.id ?? '')}`;
}

function poiLinks(row) {
  const id = row.id == null ? '' : String(row.id);
  if (!id) return dash();
  return linkList([
    rowApiLink({
      href: `/api/pois/${encodeURIComponent(id)}`,
    }),
    plainLink({
      href: poiProductUrl(row),
      text: 'Page',
    }),
    plainLink({
      href: `/reservables?poi_id=${encodeURIComponent(id)}&type=site`,
      text: 'Reservables',
    }),
  ]);
}

function formatCoords(lng, lat) {
  const x = Number(lng);
  const y = Number(lat);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return '-';
  return `${y.toFixed(5)}, ${x.toFixed(5)}`;
}
