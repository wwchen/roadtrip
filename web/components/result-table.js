export function createTable({
  columns,
  rows,
  className = 'nested-table',
  rowClassName = 'result-row',
  wrapClassName = `${className}-wrap`,
  rowRenderer = null,
}) {
  const wrap = element('div', { className: wrapClassName });
  const table = element('table', { className });

  const colgroup = createColgroup(columns);
  if (colgroup) table.append(colgroup);

  const thead = element('thead');
  const headerRow = element('tr');
  columns.forEach((column) => {
    headerRow.append(element('th', { text: column.label }));
  });
  thead.append(headerRow);

  const tbody = element('tbody');
  rows.forEach((row) => {
    const rendered = typeof rowRenderer === 'function'
      ? rowRenderer(row)
      : createRow(columns, row, { className: rowClassName });
    appendContent(tbody, rendered);
  });

  table.append(thead, tbody);
  wrap.append(table);
  return wrap;
}

export function createRow(columns, row, { className = 'result-row', dataset = {} } = {}) {
  const tr = element('tr', { className, dataset });
  columns.forEach((column) => {
    tr.append(createCell(column, row));
  });
  return tr;
}

export function createCell(column, row) {
  const td = element('td', {
    className: column.className || '',
    dataset: { label: column.label },
  });
  const content = typeof column.render === 'function'
    ? column.render(row, td)
    : row[column.key];
  appendContent(td, content == null || content === '' ? dash() : content);
  return td;
}

export function element(tag, options = {}, ...children) {
  const node = document.createElement(tag);
  const {
    className = '',
    text,
    attrs = {},
    dataset = {},
    href,
    target,
    rel,
    type,
    value,
    name,
  } = options;

  if (className) node.className = className;
  if (text != null) node.textContent = String(text);
  if (href != null) node.href = href;
  if (target != null) node.target = target;
  if (rel != null) node.rel = rel;
  if (type != null) node.type = type;
  if (value != null) node.value = value;
  if (name != null) node.name = name;

  Object.entries(attrs).forEach(([key, attrValue]) => {
    if (attrValue == null || attrValue === false) return;
    node.setAttribute(key, attrValue === true ? '' : String(attrValue));
  });
  Object.entries(dataset).forEach(([key, datasetValue]) => {
    if (datasetValue == null) return;
    node.dataset[key] = String(datasetValue);
  });
  appendContent(node, children);
  return node;
}

export function fragment(...children) {
  const node = document.createDocumentFragment();
  appendContent(node, children);
  return node;
}

export function appendContent(parent, content) {
  if (content == null || content === false) return parent;
  if (Array.isArray(content)) {
    content.forEach((child) => appendContent(parent, child));
    return parent;
  }
  if (content instanceof Node) {
    parent.append(content);
    return parent;
  }
  parent.append(document.createTextNode(String(content)));
  return parent;
}

export function replaceChildren(parent, ...children) {
  parent.replaceChildren();
  appendContent(parent, children);
}

export function plainLink({ href, text, target = '', className = '' }) {
  return element('a', {
    className,
    text,
    href,
    target: target || undefined,
    rel: target ? 'noreferrer' : undefined,
  });
}

export function linkList(items, className = 'links') {
  const wrap = element('div', { className });
  items.filter(Boolean).forEach((item) => {
    appendContent(wrap, item);
  });
  return wrap;
}

export function apiCallLink({ method = 'GET', href, target = '_blank' }) {
  return element(
    'a',
    {
      className: 'api-call',
      href,
      target,
      rel: target ? 'noreferrer' : undefined,
    },
    element('span', { className: 'api-method', text: method }),
    element('span', { className: 'api-path', text: href }),
  );
}

export function apiCallLabel({ method, path }) {
  return element(
    'span',
    { className: 'api-call api-call-static' },
    element('span', { className: 'api-method', text: method }),
    element('span', { className: 'api-path', text: path }),
  );
}

export function disclosureButton({ action, idName, id, label, expanded }) {
  return actionButton(label, action, {
    [idName]: id,
  }, {
    className: `disclosure-button${expanded ? ' active' : ''}`,
    attrs: { 'aria-expanded': expanded ? 'true' : 'false' },
    icon: true,
  });
}

export function actionButton(label, action, dataset = {}, options = {}) {
  const button = element('button', {
    className: options.className || '',
    type: 'button',
    dataset: { action, ...dataset },
    attrs: options.attrs || {},
  });
  if (options.icon) {
    button.append(element('span', { className: 'action-icon inline', attrs: { 'aria-hidden': 'true' } }));
  }
  button.append(document.createTextNode(label));
  if (options.disabled) button.disabled = true;
  return button;
}

export function submitButton(label, { primary = false, disabled = false } = {}) {
  const button = element('button', {
    className: primary ? 'primary' : '',
    type: 'submit',
    text: label,
  });
  button.disabled = disabled;
  return button;
}

export function pill(text, tone = '') {
  return element('span', {
    className: `pill${tone ? ` ${tone}` : ''}`,
    text: text || 'unknown',
  });
}

export function dash(value = '', empty = '-') {
  const text = value == null || value === '' ? empty : String(value);
  return element('span', {
    className: text === empty ? 'muted' : '',
    text,
  });
}

function createColgroup(columns) {
  if (!columns.some((column) => column.colClass)) return null;
  const colgroup = element('colgroup');
  columns.forEach((column) => {
    colgroup.append(element('col', { className: column.colClass || '' }));
  });
  return colgroup;
}
