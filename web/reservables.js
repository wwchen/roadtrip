import {
  fetchReservableAvailability,
  fetchReservable,
  searchReservables,
} from './api/reservable-api.js';
import {
  availabilityPanelHtml,
  availabilityQueryFromForm,
  defaultAvailabilityQuery,
  rawPanelHtml,
} from './availability-components.js';
import { mountReservableQuery } from './reservable-query.js';
import { reservableDetailLink, reservableRowHtml, reservableTableHtml } from './reservable-table.js';
import { escapeHtml } from './table-view.js';

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
const availabilityPanels = new Map();

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
  queryUrlEl.textContent = params.id
    ? `/api/reservable/${encodeURIComponent(params.id)}`
    : `/api/reservables${suffix ? `?${suffix}` : ''}`;
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

async function runIdLookup(id) {
  try {
    const body = await fetchReservable(id, { signal: activeAbort.signal });
    total = 1;
    offset = 0;
    renderResults([body.reservable]);
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

function renderResults(rows) {
  lastRows = rows;
  emptyEl.hidden = rows.length !== 0;
  resultsEl.innerHTML = reservableTableHtml(rows, {
    linksForRow: reservableDetailLink,
    rowRenderer: reservableRowGroupHtml,
  });
}

function reservableRowGroupHtml(row) {
  const state = availabilityPanels.get(row.rid);
  return [
    reservableRowHtml(row, {
      className: 'result-row has-subrow',
      linksHtml: reservableDetailLink(row),
    }),
    row.raw == null ? '' : rawPanelHtml(row, state),
    availabilityPanelHtml(row.rid, state),
  ].join('');
}

function panelState(rid) {
  if (!availabilityPanels.has(rid)) {
    availabilityPanels.set(rid, {
      expanded: false,
      rawExpanded: false,
      query: defaultAvailabilityQuery(),
      loading: false,
      error: '',
      data: null,
      abort: null,
    });
  }
  return availabilityPanels.get(rid);
}

function toggleAvailability(rid) {
  const state = panelState(rid);
  state.expanded = !state.expanded;
  renderResults(lastRows);
}

function toggleRaw(rid) {
  const state = panelState(rid);
  state.rawExpanded = !state.rawExpanded;
  renderResults(lastRows);
}

async function queryAvailability(rid, formEl) {
  const state = panelState(rid);
  state.abort?.abort();
  state.abort = new AbortController();
  state.query = availabilityQueryFromForm(formEl);
  state.loading = true;
  state.error = '';
  state.data = null;
  renderResults(lastRows);

  try {
    state.data = await fetchReservableAvailability(rid, {
      ...state.query,
      signal: state.abort.signal,
    });
  } catch (err) {
    if (err.name === 'AbortError') return;
    state.error = errorMessage(err);
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
  const toggle = event.target.closest('[data-action="toggle-availability"]');
  if (toggle) {
    toggleAvailability(toggle.dataset.rid || '');
    return;
  }

  const raw = event.target.closest('[data-action="toggle-raw"]');
  if (raw) {
    toggleRaw(raw.dataset.rid || '');
  }
});

resultsEl.addEventListener('submit', (event) => {
  const queryForm = event.target.closest('[data-action="availability-query"]');
  if (!queryForm) return;
  event.preventDefault();
  queryAvailability(queryForm.dataset.rid || '', queryForm);
});

query.applyParamsFromUrl();
offset = Math.max(0, parseInt(new URLSearchParams(window.location.search).get('offset') || '0', 10) || 0);
runSearch();
