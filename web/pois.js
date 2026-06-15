import { fetchPoiDetail, poiSearchUrl, searchPoiCatalog } from './api/poi-api.js';
import { fetchPoiReservables } from './api/reservable-api.js';
import { poiRowHtml } from './poi-table.js';
import { reservableDetailLink, reservableTableHtml } from './reservable-table.js';
import { escapeHtml } from './table-view.js';

const form = document.getElementById('poi-form');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const statusEl = document.getElementById('status');
const queryUrlEl = document.getElementById('query-url');
const resetBtn = document.getElementById('reset-btn');

let activeAbort = null;
let lastRows = [];
const poiReservables = new Map();

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
  const urlParams = params.id ? { id: params.id } : params;
  for (const [key, value] of Object.entries(urlParams)) {
    if (value == null || value === '') continue;
    qs.set(key, String(value));
  }
  const suffix = qs.toString();
  window.history.replaceState(null, '', suffix ? `/pois?${suffix}` : '/pois');
  queryUrlEl.textContent = apiUrl(params);
}

function apiUrl(params) {
  if (params.id) return `/api/pois/${encodeURIComponent(params.id)}`;
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

  if ((params.id || '').trim()) {
    await runIdLookup(params.id);
    return;
  }

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

async function runIdLookup(id) {
  setBusy(true);
  setStatus('Loading...');

  try {
    const body = await fetchPoiDetail(id, { signal: activeAbort.signal });
    const row = rowFromPoiDetail(body);
    renderResults([row]);
    emptyEl.textContent = 'No POI matches this ID.';
    setStatus('<strong>1</strong> match');
  } catch (err) {
    if (err.name === 'AbortError') return;
    renderResults([]);
    emptyEl.textContent = 'No POI matches this ID.';
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

function renderResults(rows) {
  lastRows = rows;
  emptyEl.hidden = rows.length !== 0;
  resultsEl.innerHTML = rows.map(rowGroupHtml).join('');
}

function rowGroupHtml(row) {
  const state = poiReservables.get(String(row.id));
  return rowHtml(row, state) + reservablesPanelHtml(row, state);
}

function rowFromPoiDetail(feature) {
  const props = feature?.properties || {};
  const coordinates = feature?.geometry?.coordinates || [];
  return {
    id: feature?.id,
    name: props.name || props.unit_name || `POI ${feature?.id || ''}`.trim(),
    category: props.category,
    region: props.region,
    lng: coordinates[0],
    lat: coordinates[1],
  };
}

function rowHtml(row, state) {
  return poiRowHtml(row, { expanded: !!state?.expanded });
}

function reservablesPanelHtml(row, state) {
  const id = String(row.id);
  const expanded = !!state?.expanded;
  const loaded = Array.isArray(state?.rows);
  const count = loaded ? state.totalAtPoi : null;
  const title = loaded
    ? `<strong>${formatNumber(count)} reservables</strong> linked to this POI`
    : '<span class="muted">Reservables linked to this POI</span>';
  return `
    <tr class="reservables-row${expanded ? ' is-expanded' : ''}" data-panel-poi-id="${escapeHtml(id)}">
      <td colspan="6">
        <div class="reservables-panel">
          <div class="reservables-heading">
            <div class="reservables-title">
              <button
                class="link-chip link-button${expanded ? ' active' : ''}"
                type="button"
                data-action="toggle-reservables"
                data-poi-id="${escapeHtml(id)}"
                aria-expanded="${expanded ? 'true' : 'false'}"
              >
                <span class="action-icon inline" aria-hidden="true"></span>
                <span class="link-text">Reservables</span>
              </button>
              ${title}
            </div>
            <div class="mono muted">/api/poi/${escapeHtml(id)}/reservables</div>
          </div>
          ${expanded ? reservablesContentHtml(state) : ''}
        </div>
      </td>
    </tr>
  `;
}

function reservablesContentHtml(state) {
  if (state.loading) return '<div class="muted">Loading reservables...</div>';
  if (state.error) return `<div class="error">${escapeHtml(state.error)}</div>`;
  if (!Array.isArray(state.rows)) return '<div class="muted">Open this panel to load linked reservables.</div>';
  if (state.rows.length === 0) return '<div class="muted">No reservables linked to this POI.</div>';
  return reservableTableHtml(state.rows, { linksForRow: reservableDetailLink });
}

function panelState(poiId) {
  if (!poiReservables.has(poiId)) {
    poiReservables.set(poiId, {
      expanded: false,
      loading: false,
      error: '',
      rows: null,
      totalAtPoi: 0,
      abort: null,
    });
  }
  return poiReservables.get(poiId);
}

async function toggleReservables(poiId) {
  const state = panelState(poiId);
  state.expanded = !state.expanded;
  renderResults(lastRows);
  if (!state.expanded || state.rows) return;

  state.abort?.abort();
  state.abort = new AbortController();
  state.loading = true;
  state.error = '';
  renderResults(lastRows);
  try {
    const body = await fetchPoiReservables(poiId, { type: 'site', signal: state.abort.signal });
    state.rows = body.reservables || [];
    state.totalAtPoi = body.total_at_poi ?? state.rows.length;
  } catch (err) {
    if (err.name === 'AbortError') return;
    state.error = errorMessage(err);
    state.rows = null;
  } finally {
    state.loading = false;
    renderResults(lastRows);
  }
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  runSearch();
});

resetBtn.addEventListener('click', () => {
  form.reset();
  runSearch();
});

resultsEl.addEventListener('click', (event) => {
  const toggle = event.target.closest('[data-action="toggle-reservables"]');
  if (!toggle) return;
  toggleReservables(toggle.dataset.poiId || '');
});

applyParamsFromUrl();
runSearch();
