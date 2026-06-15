import { dash, escapeHtml, linkChip, links, renderRow } from './table-view.js';

export const poiColumns = [
  {
    label: 'ID',
    className: 'mono',
    render: (row) => `
      <a class="name-link" href="${escapeHtml(poiPageUrl(row))}">${escapeHtml(row.id ?? '')}</a>
    `,
  },
  {
    label: 'Name',
    className: 'name',
    render: (row) => escapeHtml(row.name || 'unknown'),
  },
  {
    label: 'Category',
    render: (row) => dash(row.category),
  },
  {
    label: 'Region',
    render: (row) => dash(row.region),
  },
  {
    label: 'Coordinates',
    className: 'mono',
    render: (row) => escapeHtml(formatCoords(row.lng, row.lat)),
  },
  {
    label: 'Links',
    render: (row) => poiLinks(row),
  },
];

export function poiRowHtml(row, { expanded = false } = {}) {
  return renderRow(poiColumns, row, {
    className: `result-row has-subrow${expanded ? ' is-expanded' : ''}`,
  });
}

export function poiLinks(row) {
  return links([
    linkChip({
      href: poiJsonUrl(row.id),
      text: 'POI',
      kind: 'JSON',
    }),
    linkChip({
      href: poiReservablesJsonUrl(row.id),
      text: 'Reservables',
      kind: 'JSON',
    }),
  ]);
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
