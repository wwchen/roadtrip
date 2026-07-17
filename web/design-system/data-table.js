import { dataTableTemplate } from './data-table-template.js';

const STYLE_ID = 'rt-data-table-styles';

export function mountDataTable(container, config) {
  injectStyles();
  let state = { rows: config.rows || [] };

  function render() {
    container.innerHTML = dataTableTemplate({
      columns: config.columns,
      rows: state.rows,
      emptyMessage: config.emptyMessage,
      rowClass: config.rowClass,
    });
  }

  function onClick(e) {
    if (!config.onRowClick) return;
    const tr = e.target.closest('tbody tr');
    if (!tr) return;
    const idx = [...container.querySelectorAll('tbody tr')].indexOf(tr);
    if (idx >= 0 && idx < state.rows.length) {
      config.onRowClick(state.rows[idx], e);
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ rows }) {
      state = { rows: rows || [] };
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
