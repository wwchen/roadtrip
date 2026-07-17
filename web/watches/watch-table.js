import { mountDataTable } from '../design-system/data-table.js';
import { mountDoubleConfirmButton } from '../design-system/double-confirm-button.js';
import {
  poiCellHtml,
  dateCellHtml,
  triggerCellHtml,
  statusCellHtml,
  checkedCellHtml,
  actionsCellHtml,
} from './watch-table-template.js';

const STYLE_ID = 'rt-watch-table-styles';

export function mountWatchTable(container, config) {
  injectStyles();
  let state = { watches: config.watches || [], poiNames: config.poiNames || new Map() };
  let tableCtrl = null;
  const deleteButtons = [];

  function columns() {
    return [
      { key: 'poi', label: 'POI', render: (_, row) => poiCellHtml(row, state.poiNames) },
      { key: 'date', label: 'Date', render: (_, row) => dateCellHtml(row.start_date) },
      { key: 'trigger', label: 'Trigger', render: (_, row) => triggerCellHtml(row) },
      { key: 'status', label: 'Status', render: (_, row) => statusCellHtml(row) },
      { key: 'last_checked', label: 'Last checked', render: (_, row) => checkedCellHtml(row) },
      { key: 'actions', label: '', width: '140px', render: (_, row) => actionsCellHtml(row) },
    ];
  }

  function render() {
    container.innerHTML = '<div class="rt-watch-table-wrap" data-table-host></div>';
    const host = container.querySelector('[data-table-host]');
    tableCtrl?.dispose();
    tableCtrl = mountDataTable(host, {
      columns: columns(),
      rows: state.watches,
      emptyMessage: 'No watches yet',
      rowClass: (row) => row.status === 'paused' ? 'is-paused' : row.status === 'done' ? 'is-done' : '',
    });
    mountDeleteButtons();
  }

  function mountDeleteButtons() {
    deleteButtons.forEach((d) => d.dispose());
    deleteButtons.length = 0;
    container.querySelectorAll('[data-delete-host]').forEach((host) => {
      const id = host.dataset.watchId;
      const ctrl = mountDoubleConfirmButton(host, {
        label: '🗑',
        confirmLabel: 'Delete?',
        onConfirm: () => config.onDelete?.(id),
      });
      deleteButtons.push(ctrl);
    });
  }

  function onClick(e) {
    const editBtn = e.target.closest('[data-act="edit"]');
    if (editBtn) {
      config.onEdit?.(editBtn.dataset.id);
      return;
    }
    const pauseBtn = e.target.closest('[data-act="pause"]');
    if (pauseBtn) {
      config.onPauseResume?.(pauseBtn.dataset.id, 'paused');
      return;
    }
    const resumeBtn = e.target.closest('[data-act="resume"]');
    if (resumeBtn) {
      config.onPauseResume?.(resumeBtn.dataset.id, 'active');
      return;
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ watches, poiNames }) {
      if (watches) state.watches = watches;
      if (poiNames) state.poiNames = poiNames;
      render();
    },
    dispose() {
      tableCtrl?.dispose();
      deleteButtons.forEach((d) => d.dispose());
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
  link.href = '/web/watches/watch-table.css';
  document.head.appendChild(link);
}
