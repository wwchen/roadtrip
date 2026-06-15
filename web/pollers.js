import {
  createReservableAvailabilityPoller,
  deleteReservableAvailabilityPoller,
  fetchReservableAvailabilityPollers,
  patchReservableAvailabilityPoller,
} from './api/reservable-api.js';
import { mountPollerForm } from './components/poller-form.js';

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
  onCancel: () => setStatusText(''),
  onError: (err) => setStatusText(errorMessage(err), 'error'),
});

const pollerColumns = [
  { label: 'ID', className: 'mono', render: renderPollerId },
  { label: 'Scope', className: 'rid mono', render: renderScope },
  { label: 'Filters', render: renderFilters },
  { label: 'Dates', render: renderDates },
  { label: 'Nights', render: renderNights },
  { label: 'Cadence', render: renderCadence },
  { label: 'Triggers', render: renderTriggers },
  { label: 'Status', render: renderPollerStatus },
  { label: 'Last Checked', render: renderLastChecked },
  { label: 'Next Poll', render: renderNextPoll },
  { label: 'Logs', render: renderLogLinks },
  { label: 'Manage', render: renderManage },
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
  rowsEl.replaceChildren(...pollers.map(rowElement));
}

function rowElement(poller) {
  const row = document.createElement('tr');
  row.className = 'result-row';
  pollerColumns.forEach((column) => {
    const cell = document.createElement('td');
    cell.dataset.label = column.label;
    if (column.className) cell.className = column.className;
    column.render(cell, poller);
    row.append(cell);
  });
  return row;
}

function renderPollerId(cell, poller) {
  cell.textContent = String(poller.id || '');
}

function renderScope(cell, poller) {
  const scope = poller.scope || {};
  if (scope.rid) {
    appendLink(cell, {
      href: `/reservables?id=${encodeURIComponent(scope.rid)}`,
      text: scope.rid,
    });
    return;
  }
  if (scope.poi_id != null) {
    const id = String(scope.poi_id);
    appendLink(cell, {
      href: `/pois?id=${encodeURIComponent(id)}`,
      text: `poi:${id}`,
    });
    return;
  }
  appendDash(cell);
}

function renderFilters(cell, poller) {
  const filters = poller.reservable_filters || {};
  const entries = Object.entries(filters).filter(([, value]) => {
    if (Array.isArray(value)) return value.length > 0;
    return value != null && value !== '';
  });
  if (!entries.length) {
    appendDash(cell);
    return;
  }
  entries.forEach(([key, value]) => {
    const renderedValue = Array.isArray(value)
      ? value.join(', ')
      : typeof value === 'object'
        ? JSON.stringify(value)
        : String(value);
    const row = document.createElement('div');
    row.append(textSpan('muted', `${key}:`), document.createTextNode(` ${renderedValue}`));
    cell.append(row);
  });
}

function renderDates(cell, poller) {
  const dates = poller.target_dates || [];
  if (!dates.length) {
    appendDash(cell);
    return;
  }
  const list = document.createElement('div');
  list.className = 'date-list';
  dates.forEach((date) => {
    list.append(textSpan('', date));
  });
  cell.append(list);
}

function renderNights(cell, poller) {
  cell.textContent = String(poller.min_nights || 1);
}

function renderCadence(cell, poller) {
  cell.textContent = formatCadence(poller.cadence);
}

function renderTriggers(cell, poller) {
  const actions = poller.trigger_actions || [];
  if (!actions.length) {
    appendDash(cell);
    return;
  }
  actions.forEach((action, index) => {
    if (index > 0) cell.append(document.createTextNode(' '));
    cell.append(pill(action));
  });
}

function renderPollerStatus(cell, poller) {
  const status = poller.status || '';
  cell.append(pill(status || 'unknown', statusClass(status)));
}

function renderLastChecked(cell, poller) {
  cell.textContent = formatTime(poller.last_checked_at);
}

function renderNextPoll(cell, poller) {
  cell.textContent = formatTime(poller.next_poll_after);
}

function renderLogLinks(cell, poller) {
  const id = String(poller.id || '');
  const dates = poller.target_dates || [];
  const wrapper = document.createElement('div');
  wrapper.className = 'links';
  appendLinkChip(wrapper, {
    href: `/logs?poller_id=${encodeURIComponent(id)}&limit=100`,
    text: 'All logs',
    kind: 'Page',
  });
  dates.slice(0, 3).forEach((date) => {
    appendLinkChip(wrapper, {
      href: `/logs?poller_id=${encodeURIComponent(id)}&target_date=${encodeURIComponent(date)}&limit=100`,
      text: date,
      kind: 'Page',
    });
  });
  if (dates.length > 3) {
    wrapper.append(textSpan('muted', `+${dates.length - 3} dates`));
  }
  cell.append(wrapper);
}

function renderManage(cell, poller) {
  const id = String(poller.id || '');
  const status = String(poller.status || '').toLowerCase();
  const nextStatus = status === 'active' ? 'paused' : 'active';
  const label = status === 'active' ? 'Pause' : 'Resume';
  const actions = document.createElement('div');
  actions.className = 'actions';
  actions.append(actionButton('Edit', 'edit-poller', { id }));
  if (status !== 'done') {
    actions.append(actionButton(label, 'set-poller-status', { id, status: nextStatus }));
  }
  actions.append(actionButton('Delete', 'delete-poller', { id }));
  cell.append(actions);
}

function actionButton(label, action, dataset) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  button.dataset.action = action;
  Object.entries(dataset).forEach(([key, value]) => {
    button.dataset[key] = value;
  });
  return button;
}

function appendLink(parent, { href, text }) {
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.textContent = text;
  parent.append(anchor);
}

function appendLinkChip(parent, { href, text, kind }) {
  const anchor = document.createElement('a');
  anchor.className = 'link-chip';
  anchor.href = href;
  anchor.append(textSpan(`chip-kind ${kind.toLowerCase()}`, kind), textSpan('link-text', text));
  parent.append(anchor);
}

function appendDash(parent) {
  parent.append(textSpan('muted', '-'));
}

function pill(text, className = '') {
  const span = textSpan('pill', text);
  if (className) span.classList.add(className);
  return span;
}

function textSpan(className, text) {
  const span = document.createElement('span');
  if (className) span.className = className;
  span.textContent = text;
  return span;
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

rowsEl.addEventListener('click', async (event) => {
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
