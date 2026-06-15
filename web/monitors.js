import { fetchReservableAvailabilityMonitors } from './api/reservable-api.js';
import { escapeHtml, linkChip, links, renderRow } from './components/result-table.js';

const statusEl = document.getElementById('status');
const refreshBtn = document.getElementById('refresh-btn');
const rowsEl = document.getElementById('monitors');
const emptyEl = document.getElementById('empty');

let activeAbort = null;

const monitorColumns = [
  {
    label: 'ID',
    className: 'mono',
    render: (monitor) => escapeHtml(monitor.id),
  },
  {
    label: 'Reservable',
    className: 'rid mono',
    render: (monitor) => {
      const reservable = monitor.reservable || {};
      const rid = reservable.rid || '';
      const meta = [reservable.vendor, reservable.type].filter(Boolean).join(' / ');
      const pageUrl = `/reservables?id=${encodeURIComponent(rid)}`;
      return `
        <a href="${pageUrl}">${escapeHtml(rid || 'unknown')}</a>
        <div class="muted">${escapeHtml(meta)}</div>
      `;
    },
  },
  {
    label: 'Cadence',
    render: (monitor) => escapeHtml(formatCadence(monitor.cadence)),
  },
  {
    label: 'Action',
    render: (monitor) => escapeHtml(monitor.trigger_action || '-'),
  },
  {
    label: 'Stop',
    render: (monitor) => (monitor.stop_when_triggered ? 'yes' : 'no'),
  },
  {
    label: 'Status',
    render: (monitor) => {
      const status = monitor.status || '';
      return `<span class="pill ${statusClass(status)}">${escapeHtml(status || 'unknown')}</span>`;
    },
  },
  {
    label: 'Last Checked',
    render: (monitor) => escapeHtml(formatTime(monitor.last_checked_at)),
  },
  {
    label: 'Last Triggered',
    render: (monitor) => escapeHtml(formatTime(monitor.last_triggered_at)),
  },
  {
    label: 'Created',
    render: (monitor) => escapeHtml(formatTime(monitor.created_at)),
  },
  {
    label: 'Links',
    render: (monitor) => {
      const reservable = monitor.reservable || {};
      const rid = reservable.rid || '';
      return links([
        linkChip({
          href: `/api/reservable/${encodeURIComponent(rid)}/availability?days=7`,
          text: 'Availability',
          kind: 'JSON',
        }),
        linkChip({
          href: `/api/reservable/${encodeURIComponent(rid)}`,
          text: 'Reservable',
          kind: 'JSON',
        }),
      ]);
    },
  },
];

async function loadMonitors() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  setBusy(true);
  setStatus('Loading...');

  try {
    const body = await fetchReservableAvailabilityMonitors({ signal: activeAbort.signal });
    const monitors = body.monitors || [];
    renderRows(monitors);
    setStatus(`<strong>${formatNumber(monitors.length)}</strong> monitors`);
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

function renderRows(monitors) {
  emptyEl.hidden = monitors.length !== 0;
  rowsEl.innerHTML = monitors.map(rowHtml).join('');
}

function rowHtml(monitor) {
  return renderRow(monitorColumns, monitor, { className: '' });
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

refreshBtn.addEventListener('click', loadMonitors);
loadMonitors();
