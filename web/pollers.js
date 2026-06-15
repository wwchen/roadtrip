import { fetchReservableAvailabilityPollers } from './api/reservable-api.js';
import { dash, escapeHtml, linkChip, links, renderRow } from './components/result-table.js';

const statusEl = document.getElementById('status');
const refreshBtn = document.getElementById('refresh-btn');
const rowsEl = document.getElementById('pollers');
const emptyEl = document.getElementById('empty');

let activeAbort = null;

const pollerColumns = [
  {
    label: 'ID',
    className: 'mono',
    render: (poller) => {
      const id = String(poller.id || '');
      return `<a href="/api/reservables/availability/pollers/${encodeURIComponent(id)}" target="_blank" rel="noreferrer">${escapeHtml(id)}</a>`;
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
    label: 'Actions',
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
    label: 'Links',
    render: (poller) => renderLinks(poller),
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
}

function setStatus(html, className = '') {
  statusEl.className = `status ${className}`.trim();
  statusEl.innerHTML = html;
}

function renderRows(pollers) {
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

function renderLinks(poller) {
  const id = String(poller.id || '');
  const scope = poller.scope || {};
  const items = [
    linkChip({
      href: `/api/reservables/availability/pollers/${encodeURIComponent(id)}`,
      text: 'Poller',
      kind: 'JSON',
    }),
    linkChip({
      href: `/api/reservables/availability/runs?poller_id=${encodeURIComponent(id)}`,
      text: 'Runs',
      kind: 'JSON',
    }),
    linkChip({
      href: `/api/reservables/availability/logs?poller_id=${encodeURIComponent(id)}`,
      text: 'Logs',
      kind: 'JSON',
    }),
  ];

  if (scope.rid) {
    items.push(
      linkChip({
        href: `/api/reservable/${encodeURIComponent(scope.rid)}/availability?days=7`,
        text: 'Availability',
        kind: 'JSON',
      }),
    );
  }
  return links(items);
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
loadPollers();
