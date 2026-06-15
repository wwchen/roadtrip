import { fetchReservable, searchReservables } from './api/reservable-api.js';
import { createAvailabilityPanels } from './components/availability-controller.js';
import { mountReservableQuery } from './components/reservable-query.js';
import { createReservableTable, reservableRowGroupRenderer } from './components/reservable-table.js';
import { apiCallLink, element, replaceChildren } from './components/result-table.js';

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
  const apiUrl = params.id
    ? `/api/reservable/${encodeURIComponent(params.id)}`
    : `/api/reservables${suffix ? `?${suffix}` : ''}`;
  replaceChildren(queryUrlEl, apiCallLink({ href: apiUrl }));
}

function setBusy(busy) {
  const pager = query.setBusy(busy, { offset, total });
  prevBtn.disabled = pager.prevDisabled;
  nextBtn.disabled = pager.nextDisabled;
}

function setStatus(content, className = '') {
  const statusText = statusEl.firstElementChild;
  statusText.className = className;
  replaceChildren(statusText, content);
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
    setStatus([
      element('strong', { text: formatNumber(total) }),
      document.createTextNode(` matches / ${formatNumber(first)}-${formatNumber(last)}`),
    ]);
  } catch (err) {
    if (err.name === 'AbortError') return;
    total = 0;
    renderResults([]);
    setStatus(errorMessage(err), 'error');
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
    setStatus([element('strong', { text: '1' }), document.createTextNode(' match')]);
  } catch (err) {
    if (err.name === 'AbortError') return;
    total = 0;
    renderResults([]);
    setStatus(errorMessage(err), 'error');
  } finally {
    setBusy(false);
  }
}

function renderResults(rows) {
  lastRows = rows;
  emptyEl.hidden = rows.length !== 0;
  replaceChildren(resultsEl, createReservableTable(rows, {
    rowRenderer: reservableRowGroupRenderer({
      stateForRow: availabilityPanels.stateForRow,
    }),
  }));
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
  const createPoller = event.target.closest('[data-action="create-availability-poller"]');
  if (createPoller) {
    availabilityPanels.createPoller(createPoller.dataset.rid || '', createPoller.dataset.targetDate || '');
    return;
  }

  const toggle = event.target.closest('[data-action="toggle-availability"]');
  if (toggle) {
    availabilityPanels.toggleAvailability(toggle.dataset.rid || '');
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
