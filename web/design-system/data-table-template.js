import { escapeHtml } from '../core.js';

export function dataTableTemplate({ columns, rows, emptyMessage, rowClass, sortKey, sortDir }) {
  if (rows.length === 0) {
    return `<div class="rt-data-table-empty">${escapeHtml(emptyMessage || 'No data')}</div>`;
  }

  const headCells = columns.map((col) => {
    const style = col.width ? ` style="width:${escapeHtml(col.width)}"` : '';
    const sortable = col.sortable ? ' data-sort-key="' + escapeHtml(col.key) + '"' : '';
    const sortCls = col.sortable ? 'rt-data-table-sortable' : '';
    const activeCls = sortKey === col.key ? ` rt-data-table-sorted-${sortDir}` : '';
    const cls = [col.class, sortCls, activeCls].filter(Boolean).join(' ');
    const clsAttr = cls ? ` class="${escapeHtml(cls)}"` : '';
    const indicator = sortKey === col.key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '';
    return `<th${style}${clsAttr}${sortable}>${escapeHtml(col.label || '')}${indicator}</th>`;
  }).join('');

  const bodyRows = rows.map((row) => {
    const cls = rowClass ? rowClass(row) : '';
    const cells = columns.map((col) => {
      const value = row[col.key];
      const cellClass = col.class ? ` class="${escapeHtml(col.class)}"` : '';
      const html = col.render ? col.render(value, row) : escapeHtml(String(value ?? ''));
      return `<td${cellClass}>${html}</td>`;
    }).join('');
    return `<tr${cls ? ` class="${escapeHtml(cls)}"` : ''}>${cells}</tr>`;
  }).join('');

  return `
    <table class="rt-data-table">
      <thead><tr>${headCells}</tr></thead>
      <tbody>${bodyRows}</tbody>
    </table>
  `;
}
