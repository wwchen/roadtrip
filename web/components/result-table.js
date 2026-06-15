export function renderTable({
  columns,
  rows,
  className = 'nested-table',
  rowClassName = '',
  wrapClassName = `${className}-wrap`,
  rowRenderer = null,
}) {
  const colgroup = columns.some((column) => column.colClass)
    ? `<colgroup>${columns.map((column) => `<col${column.colClass ? ` class="${escapeHtml(column.colClass)}"` : ''}>`).join('')}</colgroup>`
    : '';
  const body = rows
    .map((row) => (
      typeof rowRenderer === 'function'
        ? rowRenderer(row)
        : renderRow(columns, row, { className: rowClassName })
    ))
    .join('');
  return `
    <div class="${escapeHtml(wrapClassName)}">
      <table class="${escapeHtml(className)}">
        ${colgroup}
        <thead>
          <tr>${columns.map((column) => `<th>${escapeHtml(column.label)}</th>`).join('')}</tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
    </div>
  `;
}

export function renderRow(columns, row, { className = 'result-row', attrs = '' } = {}) {
  const classAttr = className ? ` class="${escapeHtml(className)}"` : '';
  const attrsText = attrs ? ` ${attrs}` : '';
  return `
    <tr${classAttr}${attrsText}>
      ${columns.map((column) => renderCell(column, row)).join('')}
    </tr>
  `;
}

export function renderCell(column, row) {
  const value = typeof column.render === 'function' ? column.render(row) : dash(row[column.key]);
  const className = column.className ? ` class="${escapeHtml(column.className)}"` : '';
  return `<td${className} data-label="${escapeHtml(column.label)}">${value}</td>`;
}

export function linkChip({ href, text, kind = 'JSON', target = '_blank' }) {
  const targetAttrs = target ? ` target="${escapeHtml(target)}" rel="noreferrer"` : '';
  return `
    <a class="link-chip" href="${escapeHtml(href)}"${targetAttrs}>
      <span class="chip-kind ${escapeHtml(kind.toLowerCase())}">${escapeHtml(kind)}</span>
      <span class="link-text">${escapeHtml(text)}</span>
    </a>
  `;
}

export function expanderButton({ action, idName, id, label, expanded }) {
  return `
    <button
      class="link-chip link-button${expanded ? ' active' : ''}"
      type="button"
      data-action="${escapeHtml(action)}"
      data-${escapeHtml(idName)}="${escapeHtml(id)}"
      aria-expanded="${expanded ? 'true' : 'false'}"
    >
      <span class="action-icon inline" aria-hidden="true"></span>
      <span class="link-text">${escapeHtml(label)}</span>
    </button>
  `;
}

export function links(items) {
  return `<div class="links">${items.filter(Boolean).join('')}</div>`;
}

export function dash(value, empty = '-') {
  const text = value == null || value === '' ? empty : String(value);
  return `<span${text === empty ? ' class="muted"' : ''}>${escapeHtml(text)}</span>`;
}

export function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}
