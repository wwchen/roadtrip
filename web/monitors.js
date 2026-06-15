import { fetchReservableAvailabilityMonitors } from './api/reservable-api.js';

const statusEl = document.getElementById('status');
const refreshBtn = document.getElementById('refresh-btn');
const rowsEl = document.getElementById('monitors');
const emptyEl = document.getElementById('empty');

let activeAbort = null;

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
  const reservable = monitor.reservable || {};
  const rid = reservable.rid || '';
  const meta = [reservable.vendor, reservable.type].filter(Boolean).join(' / ');
  const detailUrl = `/api/reservable/${encodeURIComponent(rid)}`;
  const availabilityUrl = `/api/reservable/${encodeURIComponent(rid)}/availability?days=7`;
  const status = monitor.status || '';
  return `
    <tr>
      <td class="mono">${escapeHtml(monitor.id)}</td>
      <td class="rid mono">
        <a href="${detailUrl}" target="_blank" rel="noreferrer">${escapeHtml(rid || 'unknown')}</a>
        <div class="muted">${escapeHtml(meta)}</div>
      </td>
      <td>${escapeHtml(formatCadence(monitor.cadence))}</td>
      <td>${escapeHtml(monitor.trigger_action || '-')}</td>
      <td>${monitor.stop_when_triggered ? 'yes' : 'no'}</td>
      <td><span class="pill ${statusClass(status)}">${escapeHtml(status || 'unknown')}</span></td>
      <td>${escapeHtml(formatTime(monitor.last_checked_at))}</td>
      <td>${escapeHtml(formatTime(monitor.last_triggered_at))}</td>
      <td>${escapeHtml(formatTime(monitor.created_at))}</td>
      <td>
        <div class="links">
          <a href="${availabilityUrl}" target="_blank" rel="noreferrer">Availability</a>
          <a href="${detailUrl}" target="_blank" rel="noreferrer">Detail</a>
        </div>
      </td>
    </tr>
  `;
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

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

refreshBtn.addEventListener('click', loadMonitors);
loadMonitors();
