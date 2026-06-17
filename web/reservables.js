import { fetchPoiReservables, fetchReservable, poiReservablesUrl, searchReservables } from './api/reservable-api.js';
import { availabilityQueryFromForm, createWatchUrlFromQuery, defaultAvailabilityQuery } from './components/availability-panel.js';
import { createAvailabilityPanels } from './components/availability-controller.js';
import { mountReservableQuery } from './components/reservable-query.js';
import { reservableDetailLink, reservableRowGroupRenderer, reservableTableHtml } from './components/reservable-table.js';
import { escapeHtml } from './components/result-table.js';

const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const statusEl = document.getElementById('status');
const queryUrlEl = document.getElementById('query-url');
const prevBtn = document.getElementById('prev-btn');
const nextBtn = document.getElementById('next-btn');

let offset = 0;
let total = 0;
let activeAbort = null;
let lastRows = [];
const availabilityPanels = createAvailabilityPanels({
  render: () => renderResults(lastRows),
});

const query = mountReservableQuery(document.getElementById('reservable-query'), {
  onSubmit: () => {
    offset = 0;
    runSearch();
  },
  onReset: () => {
    offset = 0;
    total = 0;
    runSearch();
  },
});

function syncUrl(params) {
  const qs = new URLSearchParams();
  const urlParams = params.id ? { id: params.id } : params;
  for (const [key, value] of Object.entries(urlParams)) {
    if (value == null || value === '') continue;
    if (key === 'offset' && String(value) === '0') continue;
    qs.set(key, String(value));
  }
  const suffix = qs.toString();
  const next = suffix ? `/reservables?${suffix}` : '/reservables';
  window.history.replaceState(null, '', next);
  queryUrlEl.textContent = apiUrl(params, suffix);
}

function apiUrl(params, suffix) {
  if (params.id) return `/api/reservable/${encodeURIComponent(params.id)}`;
  if (params.poi_id) {
    return poiReservablesUrl(params.poi_id, {
      type: params.type || undefined,
      siteType: params.site_type || undefined,
    });
  }
  return `/api/reservables${suffix ? `?${suffix}` : ''}`;
}

function setBusy(busy) {
  const pager = query.setBusy(busy, { offset, total });
  prevBtn.disabled = pager.prevDisabled;
  nextBtn.disabled = pager.nextDisabled;
}

function setStatus(html, className = '') {
  statusEl.firstElementChild.className = className;
  statusEl.firstElementChild.innerHTML = html;
}

async function runSearch() {
  activeAbort?.abort();
  activeAbort = new AbortController();
  const params = query.params(offset);
  syncUrl(params);
  setBusy(true);
  setStatus('Loading...');

  if ((params.id || '').trim()) {
    await runIdLookup(params.id);
    return;
  }

  if ((params.poi_id || '').trim()) {
    await runPoiLookup(params);
    return;
  }

  try {
    const body = await searchReservables({ ...params, signal: activeAbort.signal });
    total = body.total || 0;
    offset = body.offset || 0;
    renderResults(body.reservables || []);
    const first = total === 0 ? 0 : offset + 1;
    const last = Math.min(offset + (body.reservables || []).length, total);
    setStatus(`<strong>${formatNumber(total)}</strong> matches / ${formatNumber(first)}-${formatNumber(last)}`);
  } catch (err) {
    if (err.name === 'AbortError') return;
    total = 0;
    renderResults([]);
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

async function runPoiLookup(params) {
  try {
    const body = await fetchPoiReservables(params.poi_id, {
      type: params.type || undefined,
      siteType: params.site_type || undefined,
      signal: activeAbort.signal,
    });
    const linkedPoiIds = [body.poi_id ?? params.poi_id].map((id) => String(id || '').trim()).filter(Boolean);
    const rows = (body.reservables || []).map((row) => ({
      ...row,
      poi_ids: Array.isArray(row.poi_ids) && row.poi_ids.length > 0 ? row.poi_ids : linkedPoiIds,
    }));
    const filtered = applyPoiClientFilters(rows, params);
    total = filtered.length;
    offset = Math.max(0, parseInt(params.offset || '0', 10) || 0);
    const limit = query.limitValue();
    const pageRows = filtered.slice(offset, offset + limit);
    renderResults(pageRows);
    const first = total === 0 ? 0 : offset + 1;
    const last = Math.min(offset + pageRows.length, total);
    setStatus(
      `<strong>${formatNumber(total)}</strong> matches at POI ${escapeHtml(params.poi_id)} / ${formatNumber(first)}-${formatNumber(last)}`,
    );
  } catch (err) {
    if (err.name === 'AbortError') return;
    total = 0;
    renderResults([]);
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

async function runIdLookup(id) {
  try {
    const body = await fetchReservable(id, { signal: activeAbort.signal });
    total = 1;
    offset = 0;
    renderResults([{ ...(body.reservable || {}), poi_ids: body.reservable?.poi_ids || body.poi_ids || [] }]);
    setStatus('<strong>1</strong> match');
  } catch (err) {
    if (err.name === 'AbortError') return;
    total = 0;
    renderResults([]);
    setStatus(escapeHtml(errorMessage(err)), 'error');
  } finally {
    setBusy(false);
  }
}

function applyPoiClientFilters(rows, params) {
  return rows.filter((row) => {
    if (!matchesCsv(row.vendor, params.vendor)) return false;
    if (!matchesCsv(row.vendor_id ?? row.vendorId, params.vendor_id)) return false;
    if (!matchesCsv(row.name, params.name)) return false;
    if (!matchesCsv(row.loop, params.loop)) return false;
    if (!matchesCsv(row.site_type ?? row.siteType, params.site_type)) return false;
    if (!rawContains(row.raw, params.raw)) return false;
    if (!jsonContainsFilter(row.tags, params.tags)) return false;
    return true;
  });
}

function matchesCsv(value, filter) {
  const values = csvValues(filter);
  if (values.length === 0) return true;
  const text = String(value ?? '');
  return values.some((candidate) => text === candidate);
}

function rawContains(raw, filter) {
  const needle = String(filter || '').trim();
  if (!needle) return true;
  return JSON.stringify(raw ?? {}).toLowerCase().includes(needle.toLowerCase());
}

function jsonContainsFilter(value, filter) {
  const text = String(filter || '').trim();
  if (!text) return true;
  try {
    return jsonContains(value ?? {}, JSON.parse(text));
  } catch {
    return JSON.stringify(value ?? {}).toLowerCase().includes(text.toLowerCase());
  }
}

function jsonContains(actual, expected) {
  if (expected == null || typeof expected !== 'object') return Object.is(actual, expected);
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual)) return false;
    return expected.every((expectedItem) => (
      actual.some((actualItem) => jsonContains(actualItem, expectedItem))
    ));
  }
  if (!actual || typeof actual !== 'object' || Array.isArray(actual)) return false;
  return Object.entries(expected).every(([key, expectedValue]) => (
    Object.prototype.hasOwnProperty.call(actual, key) && jsonContains(actual[key], expectedValue)
  ));
}

function csvValues(value) {
  return String(value || '')
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

function renderResults(rows) {
  lastRows = rows;
  emptyEl.hidden = rows.length !== 0;
  resultsEl.innerHTML = reservableTableHtml(rows, {
    linksForRow: reservableDetailLink,
    rowRenderer: reservableRowGroupRenderer({
      stateForRow: availabilityPanels.stateForRow,
      linksForRow: reservableDetailLink,
    }),
  });
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

prevBtn.addEventListener('click', () => {
  offset = Math.max(0, offset - query.limitValue());
  runSearch();
});

nextBtn.addEventListener('click', () => {
  if (offset + query.limitValue() >= total) return;
  offset += query.limitValue();
  runSearch();
});

resultsEl.addEventListener('click', (event) => {
  const detail = event.target.closest('[data-action="toggle-reservable-detail"]');
  if (detail) {
    availabilityPanels.toggleDetails(detail.dataset.rid || '');
    return;
  }

  const detailClose = event.target.closest('[data-site-detail-close]');
  if (detailClose) {
    const panel = detailClose.closest('[data-panel-rid]');
    availabilityPanels.toggleDetails(panel?.dataset.panelRid || '');
    return;
  }

  const toggle = event.target.closest('[data-action="toggle-availability"]');
  if (toggle) {
    event.preventDefault();
    availabilityPanels.toggleAvailability(toggle.dataset.rid || '');
    return;
  }
  const createWatchBtn = event.target.closest('[data-action="create-watch"]');
  if (createWatchBtn) {
    event.preventDefault();
    const form = createWatchBtn.closest('form[data-action="availability-query"]');
    const rid = createWatchBtn.dataset.rid || '';
    const queryState = form ? availabilityQueryFromForm(form) : defaultAvailabilityQuery();
    window.location.href = createWatchUrlFromQuery(rid, queryState);
  }
});

resultsEl.addEventListener('submit', (event) => {
  const queryForm = event.target.closest('[data-action="availability-query"]');
  if (!queryForm) return;
  event.preventDefault();
  availabilityPanels.queryAvailability(queryForm.dataset.rid || '', queryForm);
});

query.applyParamsFromUrl();
offset = Math.max(0, parseInt(new URLSearchParams(window.location.search).get('offset') || '0', 10) || 0);
runSearch();
