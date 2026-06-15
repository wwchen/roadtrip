import { searchReservables } from './api/reservable-api.js';

const form = document.getElementById('reservable-form');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const statusEl = document.getElementById('status');
const queryUrlEl = document.getElementById('query-url');
const prevBtn = document.getElementById('prev-btn');
const nextBtn = document.getElementById('next-btn');
const resetBtn = document.getElementById('reset-btn');

let offset = 0;
let total = 0;
let activeAbort = null;

function formParams() {
  const data = new FormData(form);
  const params = {};
  for (const [key, value] of data.entries()) {
    const text = String(value).trim();
    if (!text) continue;
    params[key] = text;
  }
  params.offset = String(offset);
  return params;
}

function applyParamsFromUrl() {
  const qs = new URLSearchParams(window.location.search);
  for (const el of form.elements) {
    if (!el.name || !qs.has(el.name)) continue;
    el.value = qs.get(el.name) || '';
  }
  offset = Math.max(0, parseInt(qs.get('offset') || '0', 10) || 0);
}

function syncUrl(params) {
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value == null || value === '') continue;
    if (key === 'offset' && String(value) === '0') continue;
    qs.set(key, String(value));
  }
  const suffix = qs.toString();
  const next = suffix ? `/reservables?${suffix}` : '/reservables';
  window.history.replaceState(null, '', next);
  queryUrlEl.textContent = `/api/reservables${suffix ? `?${suffix}` : ''}`;
}

function setBusy(busy) {
  form.querySelectorAll('input, select, textarea, button').forEach((el) => {
    el.disabled = busy;
  });
  prevBtn.disabled = busy || offset <= 0;
  nextBtn.disabled = busy || offset + limitValue() >= total;
}

function limitValue() {
  return Math.max(1, parseInt(form.elements.limit.value || '100', 10) || 100);
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
  setBusy(true);
  setStatus('Loading...');

  try {
    const body = await searchReservables({ ...params, signal: activeAbort.signal });
    total = body.total || 0;
    offset = body.offset || 0;
    renderResults(body.reservables || []);
    const first = total === 0 ? 0 : offset + 1;
    const last = Math.min(offset + (body.reservables || []).length, total);
    setStatus(`<strong>${formatNumber(total)}</strong> matches · ${formatNumber(first)}-${formatNumber(last)}`);
  } catch (err) {
    if (err.name === 'AbortError') return;
    total = 0;
    renderResults([]);
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
  const raw = row.raw == null ? '' : JSON.stringify(row.raw, null, 2);
  const rawCell = raw
    ? `<details><summary>JSON</summary><pre>${escapeHtml(raw)}</pre></details>`
    : '<span class="muted">none</span>';
  const detailUrl = `/api/reservable/${encodeURIComponent(row.rid)}`;
  const availabilityUrl = `/api/reservable/${encodeURIComponent(row.rid)}/availability?days=7`;
  return `
    <tr>
      <td class="rid mono">
        <a href="${detailUrl}" target="_blank" rel="noreferrer">${escapeHtml(row.rid)}</a>
        <div class="muted">${escapeHtml(row.vendor || '')} · ${escapeHtml(row.type || '')}</div>
      </td>
      <td class="name">${dash(row.name)}</td>
      <td>${dash(row.loop)}</td>
      <td>${dash(row.site_type)}</td>
      <td>${rawCell}</td>
      <td>
        <a href="${availabilityUrl}" target="_blank" rel="noreferrer">Availability</a>
      </td>
    </tr>
  `;
}

function dash(value) {
  const text = value == null || value === '' ? '—' : String(value);
  return `<span${text === '—' ? ' class="muted"' : ''}>${escapeHtml(text)}</span>`;
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
  offset = 0;
  runSearch();
});

resetBtn.addEventListener('click', () => {
  form.reset();
  form.elements.type.value = 'site';
  offset = 0;
  total = 0;
  runSearch();
});

prevBtn.addEventListener('click', () => {
  offset = Math.max(0, offset - limitValue());
  runSearch();
});

nextBtn.addEventListener('click', () => {
  if (offset + limitValue() >= total) return;
  offset += limitValue();
  runSearch();
});

applyParamsFromUrl();
runSearch();
