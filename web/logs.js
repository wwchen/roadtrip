import { fetchReservableAvailabilityLogs } from './api/reservable-api.js';
import { dash, escapeHtml, renderTable } from './components/result-table.js';

const formEl = document.getElementById('logs-query');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const statusEl = document.getElementById('status');
const queryUrlEl = document.getElementById('query-url');

let activeAbort = null;

const logColumns = [
  {
    label: 'ID',
    colClass: 'col-log-id',
    className: 'mono',
    render: (log) => escapeHtml(log.id || ''),
  },
  {
    label: 'RID',
    colClass: 'col-rid',
    className: 'rid mono',
    render: (log) => ridLink(log.reservable_rid),
  },
  {
    label: 'Target Date',
    colClass: 'col-date',
    className: 'mono',
    render: (log) => escapeHtml(log.target_date || ''),
  },
  {
    label: 'Status',
    colClass: 'col-status',
    render: (log) => statusPill(log.status),
  },
  {
    label: 'Available',
    colClass: 'col-available',
    render: (log) => booleanPill(log.available),
  },
  {
    label: 'Observed',
    colClass: 'col-observed',
    render: (log) => escapeHtml(formatTime(log.observed_at)),
  },
  {
    label: 'Run',
    colClass: 'col-run',
    className: 'mono',
    render: (log) => runLink(log.run_id),
  },
  {
    label: 'Payload',
    colClass: 'col-payload',
    render: (log) => payloadDetails(log.day_payload),
  },
];

function applyParamsFromUrl() {
  const params = new URLSearchParams(window.location.search);
  for (const name of ['run_id', 'poller_id', 'rid', 'target_date', 'limit']) {
    const input = formEl.elements.namedItem(name);
    if (input && params.has(name)) input.value = params.get(name) || '';
  }
}

function paramsFromForm() {
  const data = new FormData(formEl);
  return {
    run_id: clean(data.get('run_id')),
    poller_id: clean(data.get('poller_id')),
    rid: clean(data.get('rid')),
    target_date: clean(data.get('target_date')),
    limit: clean(data.get('limit')) || '100',
  };
}

function syncUrl(params) {
  const qs = queryString(params);
  window.history.replaceState(null, '', qs ? `/logs?${qs}` : '/logs');
  const apiUrl = `/api/reservables/availability/logs${qs ? `?${qs}` : ''}`;
  queryUrlEl.innerHTML = apiLink(apiUrl);
}

async function loadLogs() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  const params = paramsFromForm();
  syncUrl(params);
  setBusy(true);
  setStatus('Loading...');

  try {
    const body = await fetchReservableAvailabilityLogs({ ...params, signal: activeAbort.signal });
    const logs = body.logs || [];
    renderRows(logs);
    setStatus(`<strong>${formatNumber(logs.length)}</strong> logs`);
  } catch (err) {
    if (err.name === 'AbortError') return;
    renderRows([]);
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

function setBusy(busy) {
  formEl.querySelectorAll('input, select, button').forEach((el) => {
    el.disabled = busy;
  });
}

function setStatus(html, className = '') {
  statusEl.firstElementChild.className = className;
  statusEl.firstElementChild.innerHTML = html;
}

function renderRows(logs) {
  emptyEl.hidden = logs.length !== 0;
  resultsEl.innerHTML = renderTable({
    columns: logColumns,
    rows: logs,
    className: 'logs-table',
    wrapClassName: 'logs-table-wrap table-wrap',
    rowClassName: 'result-row',
  });
}

function ridLink(rid) {
  if (!rid) return dash('');
  return `<a href="/reservables?id=${encodeURIComponent(rid)}">${escapeHtml(rid)}</a>`;
}

function runLink(runId) {
  if (runId == null || Number(runId) <= 0) return dash('');
  const id = String(runId);
  return `<a href="/api/reservables/availability/runs/${encodeURIComponent(id)}" target="_blank" rel="noreferrer">${escapeHtml(id)}</a>`;
}

function statusPill(status) {
  const value = String(status || 'unknown');
  return `<span class="pill ${escapeHtml(statusClass(value))}">${escapeHtml(value)}</span>`;
}

function booleanPill(value) {
  return `<span class="pill ${value ? 'available' : 'booked'}">${value ? 'yes' : 'no'}</span>`;
}

function payloadDetails(payload) {
  if (!payload || typeof payload !== 'object') return dash('');
  const count = payload.available_count ?? 0;
  const total = payload.total ?? 0;
  return `
    <details class="json-details">
      <summary><span class="action-icon inline" aria-hidden="true"></span><span>${escapeHtml(count)} of ${escapeHtml(total)}</span></summary>
      <pre>${escapeHtml(JSON.stringify(payload, null, 2))}</pre>
    </details>
  `;
}

function queryString(params) {
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value == null || value === '') continue;
    qs.set(key, String(value));
  }
  return qs.toString();
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

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}

function statusClass(status) {
  const value = String(status || '').toLowerCase();
  return ['available', 'partial', 'booked', 'closed', 'unknown'].includes(value) ? value : '';
}

function clean(value) {
  return String(value || '').trim();
}

function apiLink(href) {
  return `<a href="${escapeHtml(href)}" target="_blank" rel="noreferrer">${escapeHtml(href)}</a>`;
}

formEl.addEventListener('submit', (event) => {
  event.preventDefault();
  loadLogs();
});

formEl.addEventListener('click', (event) => {
  const reset = event.target.closest('[data-action="reset-logs-query"]');
  if (!reset) return;
  formEl.reset();
  loadLogs();
});

applyParamsFromUrl();
loadLogs();
