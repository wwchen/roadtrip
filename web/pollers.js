import {
  createReservableAvailabilityPoller,
  deleteReservableAvailabilityPoller,
  fetchReservableAvailabilityPollers,
  patchReservableAvailabilityPoller,
} from './api/reservable-api.js';
import { mountPollerForm } from './components/poller-form.js';
import {
  actionButton,
  createTable,
  dash,
  element,
  fragment,
  linkList,
  pill,
  plainLink,
  replaceChildren,
} from './components/result-table.js';

const statusEl = document.getElementById('status');
const refreshBtn = document.getElementById('refresh-btn');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const pollerFormEl = document.getElementById('poller-form-root');

let activeAbort = null;
let pollersById = new Map();

const pollerForm = mountPollerForm(pollerFormEl, {
  onCreate: createPoller,
  onUpdate: updatePoller,
  onCancel: () => setStatusText(''),
  onError: (err) => setStatusText(errorMessage(err), 'error'),
});

const pollerColumns = [
  { label: 'ID', colClass: 'col-poller-id', className: 'mono', render: renderPollerId },
  { label: 'Scope', colClass: 'col-poller-scope', className: 'rid mono', render: renderScope },
  { label: 'Filters', colClass: 'col-poller-filters', render: renderFilters },
  { label: 'Dates', colClass: 'col-poller-dates', render: renderDates },
  { label: 'Nights', colClass: 'col-poller-nights', render: renderNights },
  { label: 'Cadence', colClass: 'col-poller-cadence', render: renderCadence },
  { label: 'Triggers', colClass: 'col-poller-triggers', render: renderTriggers },
  { label: 'Status', colClass: 'col-poller-status', render: renderPollerStatus },
  { label: 'Last Checked', colClass: 'col-poller-last', render: renderLastChecked },
  { label: 'Next Poll', colClass: 'col-poller-next', render: renderNextPoll },
  { label: 'Logs', colClass: 'col-poller-logs', render: renderLogLinks },
  { label: 'Manage', colClass: 'col-poller-manage', render: renderManage },
];

async function loadPollers() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  setBusy(true);
  setStatusText('Loading...');

  try {
    const body = await fetchReservableAvailabilityPollers({ signal: activeAbort.signal });
    const pollers = body.pollers || [];
    renderRows(pollers);
    setStatusCount(pollers.length);
  } catch (err) {
    if (err.name === 'AbortError') return;
    renderRows([]);
    setStatusText(errorMessage(err), 'error');
  } finally {
    setBusy(false);
  }
}

function setBusy(busy) {
  refreshBtn.disabled = busy;
  pollerForm.setBusy(busy);
}

function setStatusText(text, className = '') {
  statusEl.className = `status ${className}`.trim();
  statusEl.replaceChildren();
  if (text) statusEl.append(document.createTextNode(text));
}

function setStatusCount(count) {
  statusEl.className = 'status';
  const strong = document.createElement('strong');
  strong.textContent = formatNumber(count);
  statusEl.replaceChildren(strong, document.createTextNode(' pollers'));
}

function setStatusPoller(id, action) {
  statusEl.className = 'status';
  const strong = document.createElement('strong');
  strong.textContent = `Poller #${id}`;
  statusEl.replaceChildren(strong, document.createTextNode(action));
}

function renderRows(pollers) {
  pollersById = new Map(pollers.map((poller) => [String(poller.id || ''), poller]));
  emptyEl.hidden = pollers.length !== 0;
  replaceChildren(resultsEl, createTable({
    columns: pollerColumns,
    rows: pollers,
    className: 'pollers-table',
    wrapClassName: 'pollers-table-wrap table-wrap',
    rowClassName: 'result-row',
  }));
}

function renderPollerId(poller) {
  return String(poller.id || '');
}

function renderScope(poller) {
  const scope = poller.scope || {};
  if (scope.rid) {
    return plainLink({
      href: `/reservables?id=${encodeURIComponent(scope.rid)}`,
      text: scope.rid,
    });
  }
  if (scope.poi_id != null) {
    const id = String(scope.poi_id);
    return plainLink({
      href: `/pois?id=${encodeURIComponent(id)}`,
      text: `poi:${id}`,
    });
  }
  return dash();
}

function renderFilters(poller) {
  const filters = poller.reservable_filters || {};
  const entries = Object.entries(filters).filter(([, value]) => {
    if (Array.isArray(value)) return value.length > 0;
    return value != null && value !== '';
  });
  if (!entries.length) return dash();
  return fragment(...entries.map(([key, value]) => {
    const renderedValue = Array.isArray(value)
      ? value.join(', ')
      : typeof value === 'object'
        ? JSON.stringify(value)
        : String(value);
    return element(
      'div',
      {},
      element('span', { className: 'muted', text: `${key}:` }),
      document.createTextNode(` ${renderedValue}`),
    );
  }));
}

function renderDates(poller) {
  const dates = poller.target_dates || [];
  if (!dates.length) return dash();
  const list = element('div', { className: 'date-list' });
  dates.forEach((date) => {
    list.append(element('span', { text: date }));
  });
  return list;
}

function renderNights(poller) {
  return String(poller.min_nights || 1);
}

function renderCadence(poller) {
  return formatCadence(poller.cadence);
}

function renderTriggers(poller) {
  const actions = poller.trigger_actions || [];
  if (!actions.length) return dash();
  return element('div', { className: 'pill-list' }, actions.map((action) => pill(action)));
}

function renderPollerStatus(poller) {
  const status = poller.status || '';
  return pill(status || 'unknown', statusClass(status));
}

function renderLastChecked(poller) {
  return formatTime(poller.last_checked_at);
}

function renderNextPoll(poller) {
  return formatTime(poller.next_poll_after);
}

function renderLogLinks(poller) {
  const id = String(poller.id || '');
  const dates = poller.target_dates || [];
  const links = [
    plainLink({
      href: `/logs?poller_id=${encodeURIComponent(id)}&limit=100`,
      text: 'All logs',
    }),
  ];
  dates.slice(0, 3).forEach((date) => {
    links.push(plainLink({
      href: `/logs?poller_id=${encodeURIComponent(id)}&target_date=${encodeURIComponent(date)}&limit=100`,
      text: date,
    }));
  });
  if (dates.length > 3) {
    links.push(element('span', { className: 'muted', text: `+${dates.length - 3} dates` }));
  }
  return linkList(links);
}

function renderManage(poller) {
  const id = String(poller.id || '');
  const status = String(poller.status || '').toLowerCase();
  const nextStatus = status === 'active' ? 'paused' : 'active';
  const label = status === 'active' ? 'Pause' : 'Resume';
  const actions = element('div', { className: 'actions' });
  actions.append(actionButton('Edit', 'edit-poller', { id }));
  if (status !== 'done') {
    actions.append(actionButton(label, 'set-poller-status', { id, status: nextStatus }));
  }
  actions.append(actionButton('Delete', 'delete-poller', { id }));
  return actions;
}

async function createPoller(body) {
  setBusy(true);
  setStatusText('Creating...');
  try {
    const result = await createReservableAvailabilityPoller(body);
    pollerForm.reset();
    await loadPollers();
    setStatusPoller(result.poller?.id || '', ' saved');
  } catch (err) {
    setStatusText(errorMessage(err), 'error');
    setBusy(false);
  }
}

async function updatePoller(id, body) {
  if (!id) return;
  setBusy(true);
  setStatusText('Updating...');
  try {
    const result = await patchReservableAvailabilityPoller(id, body);
    pollerForm.reset();
    await loadPollers();
    setStatusPoller(result.poller?.id || id, ' updated');
  } catch (err) {
    setStatusText(errorMessage(err), 'error');
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

resultsEl.addEventListener('click', async (event) => {
  const editButton = event.target.closest('[data-action="edit-poller"]');
  if (editButton) {
    const id = editButton.dataset.id || '';
    const poller = pollersById.get(id);
    if (!poller) return;
    pollerForm.edit(poller);
    pollerFormEl.scrollIntoView({ block: 'start' });
    setStatusText(`Editing poller #${id}`);
    return;
  }

  const statusButton = event.target.closest('[data-action="set-poller-status"]');
  if (statusButton) {
    const id = statusButton.dataset.id || '';
    const status = statusButton.dataset.status || '';
    if (!id || !status) return;
    setBusy(true);
    setStatusText('Updating...');
    try {
      await patchReservableAvailabilityPoller(id, { status });
      await loadPollers();
    } catch (err) {
      setStatusText(errorMessage(err), 'error');
      setBusy(false);
    }
    return;
  }

  const deleteButton = event.target.closest('[data-action="delete-poller"]');
  if (deleteButton) {
    const id = deleteButton.dataset.id || '';
    if (!id || !window.confirm(`Delete poller #${id}?`)) return;
    setBusy(true);
    setStatusText('Deleting...');
    try {
      await deleteReservableAvailabilityPoller(id);
      await loadPollers();
    } catch (err) {
      setStatusText(errorMessage(err), 'error');
      setBusy(false);
    }
  }
});

loadPollers();
