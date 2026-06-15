import { fetchPoiDetail, poiSearchUrl, searchPoiCatalog } from './api/poi-api.js';
import { fetchPoiReservables } from './api/reservable-api.js';
import { mountPoiQuery } from './poi-query.js';
import { poiReservablesRowHtml, poiRowHtml, poiTableHtml } from './poi-table.js';
import { reservableDetailLink, reservableTableHtml } from './reservable-table.js';
import { escapeHtml } from './table-view.js';

const query = mountPoiQuery(document.getElementById('poi-query'), {
  onSubmit: runSearch,
  onReset: runSearch,
});
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const statusEl = document.getElementById('status');
const queryUrlEl = document.getElementById('query-url');

let activeAbort = null;
let lastRows = [];
const poiReservables = new Map();

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
  query.setBusy(busy);
}

function setStatus(html, className = '') {
  statusEl.firstElementChild.className = className;
  statusEl.firstElementChild.innerHTML = html;
}

async function runSearch() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  const params = query.params();
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
  resultsEl.innerHTML = poiTableHtml(rows, { rowRenderer: rowGroupHtml });
}

function rowGroupHtml(row) {
  const state = poiReservables.get(String(row.id));
  return rowHtml(row, state) +
    poiReservablesRowHtml(row, state, {
      contentHtml: reservablesContentHtml(state),
    });
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

function reservablesContentHtml(state) {
  if (!state?.expanded) return '';
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

resultsEl.addEventListener('click', (event) => {
  const toggle = event.target.closest('[data-action="toggle-reservables"]');
  if (!toggle) return;
  toggleReservables(toggle.dataset.poiId || '');
});

query.applyParamsFromUrl();
runSearch();
