import { escapeHtml } from '../core.js';

export function dataTableTemplate({ columns, rows, emptyMessage, rowClass }) {
  if (rows.length === 0) {
    return `<div class="rt-data-table-empty">${escapeHtml(emptyMessage || 'No data')}</div>`;
  }

  const headCells = columns.map((col) => {
    const style = col.width ? ` style="width:${escapeHtml(col.width)}"` : '';
    const cls = col.class ? ` class="${escapeHtml(col.class)}"` : '';
    return `<th${style}${cls}>${escapeHtml(col.label || '')}</th>`;
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
