import {
  createReservableAvailabilityPoller,
  deleteReservableAvailabilityPoller,
  fetchReservableAvailabilityPollers,
  patchReservableAvailabilityPoller,
} from './api/reservable-api.js';
import { mountPollerForm } from './components/poller-form.js';
import { dash, escapeHtml, renderRow } from './components/result-table.js';

const statusEl = document.getElementById('status');
const refreshBtn = document.getElementById('refresh-btn');
const rowsEl = document.getElementById('pollers');
const emptyEl = document.getElementById('empty');
const pollerFormEl = document.getElementById('poller-form-root');

let activeAbort = null;
let pollersById = new Map();

const pollerForm = mountPollerForm(pollerFormEl, {
  onCreate: createPoller,
  onUpdate: updatePoller,
  onCancel: () => setStatus(''),
  onError: (err) => setStatus(escapeHtml(errorMessage(err)), 'error'),
});

const pollerColumns = [
  {
    label: 'ID',
    className: 'mono',
    render: (poller) => {
      const id = String(poller.id || '');
      return escapeHtml(id);
    },
  },
  {
    label: 'Scope',
    className: 'rid mono',
    render: (poller) => renderScope(poller.scope || {}),
  },
  {
    label: 'Filters',
    render: (poller) => renderFilters(poller.reservable_filters || {}),
  },
  {
    label: 'Dates',
    render: (poller) => {
      const dates = poller.target_dates || [];
      if (!dates.length) return dash('');
      return `<div class="date-list">${dates.map((date) => `<span>${escapeHtml(date)}</span>`).join('')}</div>`;
    },
  },
  {
    label: 'Nights',
    render: (poller) => escapeHtml(poller.min_nights || 1),
  },
  {
    label: 'Cadence',
    render: (poller) => escapeHtml(formatCadence(poller.cadence)),
  },
  {
    label: 'Triggers',
    render: (poller) => {
      const actions = poller.trigger_actions || [];
      if (!actions.length) return dash('');
      return actions.map((action) => `<span class="pill">${escapeHtml(action)}</span>`).join(' ');
    },
  },
  {
    label: 'Status',
    render: (poller) => {
      const status = poller.status || '';
      return `<span class="pill ${statusClass(status)}">${escapeHtml(status || 'unknown')}</span>`;
    },
  },
  {
    label: 'Last Checked',
    render: (poller) => escapeHtml(formatTime(poller.last_checked_at)),
  },
  {
    label: 'Next Poll',
    render: (poller) => escapeHtml(formatTime(poller.next_poll_after)),
  },
  {
    label: 'Manage',
    render: (poller) => renderManage(poller),
  },
];

async function loadPollers() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  setBusy(true);
  setStatus('Loading...');

  try {
    const body = await fetchReservableAvailabilityPollers({ signal: activeAbort.signal });
    const pollers = body.pollers || [];
    renderRows(pollers);
    setStatus(`<strong>${formatNumber(pollers.length)}</strong> pollers`);
  } catch (err) {
    if (err.name === 'AbortError') return;
    renderRows([]);
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

function setBusy(busy) {
  refreshBtn.disabled = busy;
  pollerForm.setBusy(busy);
}

function setStatus(html, className = '') {
  statusEl.className = `status ${className}`.trim();
  statusEl.innerHTML = html;
}

function renderRows(pollers) {
  pollersById = new Map(pollers.map((poller) => [String(poller.id || ''), poller]));
  emptyEl.hidden = pollers.length !== 0;
  rowsEl.innerHTML = pollers.map(rowHtml).join('');
}

function rowHtml(poller) {
  return renderRow(pollerColumns, poller, { className: '' });
}

function renderScope(scope) {
  if (scope.rid) {
    const rid = scope.rid;
    return `<a href="/reservables?id=${encodeURIComponent(rid)}">${escapeHtml(rid)}</a>`;
  }
  if (scope.poi_id != null) {
    const id = String(scope.poi_id);
    return `<a href="/pois?id=${encodeURIComponent(id)}">poi:${escapeHtml(id)}</a>`;
  }
  return dash('');
}

function renderFilters(filters) {
  const entries = Object.entries(filters).filter(([, value]) => {
    if (Array.isArray(value)) return value.length > 0;
    return value != null && value !== '';
  });
  if (!entries.length) return dash('');
  return entries
    .map(([key, value]) => {
      const renderedValue = Array.isArray(value)
        ? value.join(', ')
        : typeof value === 'object'
          ? JSON.stringify(value)
          : String(value);
      return `<div><span class="muted">${escapeHtml(key)}:</span> ${escapeHtml(renderedValue)}</div>`;
    })
    .join('');
}

function renderManage(poller) {
  const id = String(poller.id || '');
  const status = String(poller.status || '').toLowerCase();
  const nextStatus = status === 'active' ? 'paused' : 'active';
  const label = status === 'active' ? 'Pause' : 'Resume';
  const statusButton =
    status === 'done'
      ? ''
      : `<button type="button" data-action="set-poller-status" data-id="${escapeHtml(id)}" data-status="${escapeHtml(nextStatus)}">${escapeHtml(label)}</button>`;
  return `
    <div class="actions">
      <button type="button" data-action="edit-poller" data-id="${escapeHtml(id)}">Edit</button>
      ${statusButton}
      <button type="button" data-action="delete-poller" data-id="${escapeHtml(id)}">Delete</button>
    </div>
  `;
}

async function createPoller(body) {
  setBusy(true);
  setStatus('Creating...');
  try {
    const result = await createReservableAvailabilityPoller(body);
    pollerForm.reset();
    await loadPollers();
    setStatus(`<strong>Poller #${escapeHtml(result.poller?.id || '')}</strong> saved`);
  } catch (err) {
    setStatus(escapeHtml(errorMessage(err)), 'error');
    setBusy(false);
  }
}

async function updatePoller(id, body) {
  if (!id) return;
  setBusy(true);
  setStatus('Updating...');
  try {
    const result = await patchReservableAvailabilityPoller(id, body);
    pollerForm.reset();
    await loadPollers();
    setStatus(`<strong>Poller #${escapeHtml(result.poller?.id || id)}</strong> updated`);
  } catch (err) {
    setStatus(escapeHtml(errorMessage(err)), 'error');
    setBusy(false);
  }
}

function formatCadence(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value)) return '-';
  if (value < 60) return `${value}s`;
  if (value % 3600 === 0) return `${value / 3600}h`;
  if (value % 60 === 0) return `${value / 60}m`;
  return `${value}s`;
}

function formatTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function statusClass(status) {
  const value = String(status || '').toLowerCase();
  return ['active', 'paused', 'done'].includes(value) ? value : '';
}

refreshBtn.addEventListener('click', loadPollers);

rowsEl.addEventListener('click', async (event) => {
  const editButton = event.target.closest('[data-action="edit-poller"]');
  if (editButton) {
    const id = editButton.dataset.id || '';
    const poller = pollersById.get(id);
    if (!poller) return;
    pollerForm.edit(poller);
    pollerFormEl.scrollIntoView({ block: 'start' });
    setStatus(`Editing poller #${escapeHtml(id)}`);
    return;
  }

  const statusButton = event.target.closest('[data-action="set-poller-status"]');
  if (statusButton) {
    const id = statusButton.dataset.id || '';
    const status = statusButton.dataset.status || '';
    if (!id || !status) return;
    setBusy(true);
    setStatus('Updating...');
    try {
      await patchReservableAvailabilityPoller(id, { status });
      await loadPollers();
    } catch (err) {
      setStatus(escapeHtml(errorMessage(err)), 'error');
      setBusy(false);
    }
    return;
  }

  const deleteButton = event.target.closest('[data-action="delete-poller"]');
  if (deleteButton) {
    const id = deleteButton.dataset.id || '';
    if (!id || !window.confirm(`Delete poller #${id}?`)) return;
    setBusy(true);
    setStatus('Deleting...');
    try {
      await deleteReservableAvailabilityPoller(id);
      await loadPollers();
    } catch (err) {
      setStatus(escapeHtml(errorMessage(err)), 'error');
      setBusy(false);
    }
  }
});

loadPollers();
