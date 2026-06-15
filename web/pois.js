import { poiSearchUrl, searchPoiCatalog } from './api/poi-api.js';

const form = document.getElementById('poi-form');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const statusEl = document.getElementById('status');
const queryUrlEl = document.getElementById('query-url');
const resetBtn = document.getElementById('reset-btn');

let activeAbort = null;

function formParams() {
  const data = new FormData(form);
  const params = {};
  for (const [key, value] of data.entries()) {
    const text = String(value).trim();
    if (!text) continue;
    params[key] = text;
  }
  return params;
}

function applyParamsFromUrl() {
  const qs = new URLSearchParams(window.location.search);
  for (const el of form.elements) {
    if (!el.name || !qs.has(el.name)) continue;
    el.value = qs.get(el.name) || '';
  }
}

function syncUrl(params) {
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value == null || value === '') continue;
    qs.set(key, String(value));
  }
  const suffix = qs.toString();
  window.history.replaceState(null, '', suffix ? `/pois?${suffix}` : '/pois');
  queryUrlEl.textContent = apiUrl(params);
}

function apiUrl(params) {
  return poiSearchUrl({
    q: params.q || '',
    limit: params.limit || '25',
    categories: params.categories || '',
  });
}

function setBusy(busy) {
  form.querySelectorAll('input, select, button').forEach((el) => {
    el.disabled = busy;
  });
}

function setStatus(html, className = '') {
  statusEl.firstElementChild.className = className;
  statusEl.firstElementChild.innerHTML = html;
}

async function runSearch() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  const params = formParams();
  syncUrl(params);

  if ((params.q || '').trim().length < 2) {
    renderResults([]);
    emptyEl.textContent = 'Enter at least 2 characters to search.';
    setStatus('Enter at least 2 characters.');
    return;
  }

  setBusy(true);
  setStatus('Loading...');

  try {
    const body = await searchPoiCatalog({ ...params, signal: activeAbort.signal });
    const rows = body.results || [];
    renderResults(rows);
    emptyEl.textContent = 'No POIs match this search.';
    setStatus(`<strong>${formatNumber(rows.length)}</strong> matches`);
  } catch (err) {
    if (err.name === 'AbortError') return;
    renderResults([]);
    emptyEl.textContent = 'No POIs match this search.';
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

function renderResults(rows) {
  emptyEl.hidden = rows.length !== 0;
  resultsEl.innerHTML = rows.map(rowHtml).join('');
}

function rowHtml(row) {
  const id = row.id == null ? '' : String(row.id);
  const detailUrl = `/api/pois/${encodeURIComponent(id)}`;
  const mapUrl = `/?poi=${encodeURIComponent(id)}`;
  const reservablesUrl = `/api/poi/${encodeURIComponent(id)}/reservables`;
  const availabilityUrl = `/api/poi/${encodeURIComponent(id)}/availability?days=7`;
  const coords = formatCoords(row.lng, row.lat);
  return `
    <tr>
      <td class="mono">${escapeHtml(id)}</td>
      <td class="name">
        <a href="${detailUrl}" target="_blank" rel="noreferrer">${escapeHtml(row.name || 'unknown')}</a>
      </td>
      <td>
        ${dash(row.category)}
      </td>
      <td>${dash(row.region)}</td>
      <td class="mono">${escapeHtml(coords)}</td>
      <td>
        <div class="links">
          <a href="${mapUrl}">Map</a>
          <a href="${detailUrl}" target="_blank" rel="noreferrer">Detail</a>
          <a href="${reservablesUrl}" target="_blank" rel="noreferrer">Reservables</a>
          <a href="${availabilityUrl}" target="_blank" rel="noreferrer">Availability</a>
        </div>
      </td>
    </tr>
  `;
}

function formatCoords(lng, lat) {
  const x = Number(lng);
  const y = Number(lat);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return '-';
  return `${y.toFixed(5)}, ${x.toFixed(5)}`;
}

function dash(value) {
  const text = value == null || value === '' ? '-' : String(value);
  return `<span${text === '-' ? ' class="muted"' : ''}>${escapeHtml(text)}</span>`;
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  runSearch();
});

resetBtn.addEventListener('click', () => {
  form.reset();
  runSearch();
});

applyParamsFromUrl();
runSearch();
