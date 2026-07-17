import { dataTableTemplate } from './data-table-template.js';

const STYLE_ID = 'rt-data-table-styles';

export function mountDataTable(container, config) {
  injectStyles();
  let state = {
    rows: config.rows || [],
    sortKey: config.defaultSort?.key || null,
    sortDir: config.defaultSort?.dir || 'asc',
  };

  function sortedRows() {
    if (!state.sortKey) return state.rows;
    const col = config.columns.find((c) => c.key === state.sortKey);
    if (!col || !col.sortable) return state.rows;
    const sortFn = typeof col.sortable === 'function'
      ? col.sortable
      : (a, b) => {
        const va = a[state.sortKey] ?? '';
        const vb = b[state.sortKey] ?? '';
        if (va < vb) return -1;
        if (va > vb) return 1;
        return 0;
      };
    const sorted = [...state.rows].sort(sortFn);
    return state.sortDir === 'desc' ? sorted.reverse() : sorted;
  }

  function render() {
    container.innerHTML = dataTableTemplate({
      columns: config.columns,
      rows: sortedRows(),
      emptyMessage: config.emptyMessage,
      rowClass: config.rowClass,
      sortKey: state.sortKey,
      sortDir: state.sortDir,
    });
    config.onRender?.();
  }

  function onClick(e) {
    const th = e.target.closest('th[data-sort-key]');
    if (th) {
      const key = th.dataset.sortKey;
      if (state.sortKey === key) {
        state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
      } else {
        state.sortKey = key;
        state.sortDir = 'asc';
      }
      render();
      return;
    }
    if (!config.onRowClick) return;
    const tr = e.target.closest('tbody tr');
    if (!tr) return;
    const idx = [...container.querySelectorAll('tbody tr')].indexOf(tr);
    const rows = sortedRows();
    if (idx >= 0 && idx < rows.length) {
      config.onRowClick(rows[idx], e);
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ rows }) {
      state = { ...state, rows: rows || [] };
      render();
    },
    dispose() {
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/data-table.css';
  document.head.appendChild(link);
}
