import {
  fetchReservableAvailability,
  fetchReservable,
  reservableAvailabilityUrl,
  searchReservables,
} from './api/reservable-api.js';

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
let lastRows = [];
const availabilityPanels = new Map();

function formParams() {
  const data = new FormData(form);
  const params = {};
  for (const [key, value] of data.entries()) {
    const text = String(value).trim();
    if (!text) continue;
    params[key === 'id' ? 'id' : key] = text;
  }
  params.offset = String(offset);
  return params;
}

function applyParamsFromUrl() {
  const qs = new URLSearchParams(window.location.search);
  for (const el of form.elements) {
    if (!el.name) continue;
    if (qs.has(el.name)) {
      el.value = qs.get(el.name) || '';
    } else if (el.name === 'id' && qs.has('rid')) {
      el.value = qs.get('rid') || '';
    }
  }
  offset = Math.max(0, parseInt(qs.get('offset') || '0', 10) || 0);
}

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
  resultsEl.innerHTML = rows.map(rowGroupHtml).join('');
}

function rowGroupHtml(row) {
  const state = availabilityPanels.get(row.rid);
  return [
    rowHtml(row, state),
    state?.rawExpanded ? rawPanelHtml(row) : '',
    state?.expanded ? availabilityPanelHtml(row.rid, state) : '',
  ].join('');
}

function rowHtml(row, state) {
  const detailUrl = `/api/reservable/${encodeURIComponent(row.rid)}`;
  const viewUrl = reservablePageUrl(row);
  const expanded = !!state?.expanded;
  const rawExpanded = !!state?.rawExpanded;
  const anyExpanded = expanded || rawExpanded;
  return `
    <tr class="result-row${anyExpanded ? ' is-expanded' : ''}">
      <td class="rid mono" data-label="RID">
        <a href="${viewUrl}">${escapeHtml(row.rid)}</a>
        <div class="muted">${escapeHtml(row.vendor || '')} · ${escapeHtml(row.type || '')}</div>
      </td>
      <td class="name" data-label="Name">
        ${dash(row.name)}
      </td>
      <td data-label="Loop">${dash(row.loop)}</td>
      <td data-label="Site Type">${dash(row.site_type)}</td>
      <td data-label="Links">
        <div class="row-links">
          <button
            class="link-chip link-button${expanded ? ' active' : ''}"
            type="button"
            data-action="toggle-availability"
            data-rid="${escapeHtml(row.rid)}"
            aria-expanded="${expanded ? 'true' : 'false'}"
          >
            <span class="action-icon inline" aria-hidden="true"></span>
            <span class="link-text">${expanded ? 'Hide availability' : 'Availability'}</span>
          </button>
          ${row.raw == null ? '' : `
            <button
              class="link-chip link-button${rawExpanded ? ' active' : ''}"
              type="button"
              data-action="toggle-raw"
              data-rid="${escapeHtml(row.rid)}"
              aria-expanded="${rawExpanded ? 'true' : 'false'}"
            >
              <span class="action-icon inline" aria-hidden="true"></span>
              <span class="link-text">${rawExpanded ? 'Hide raw JSON' : 'Raw JSON'}</span>
            </button>
          `}
          <a class="link-chip" href="${detailUrl}" target="_blank" rel="noreferrer">
            <span class="chip-kind json">JSON</span>
            <span class="link-text">Reservable</span>
          </a>
        </div>
      </td>
    </tr>
  `;
}

function rawPanelHtml(row) {
  const raw = JSON.stringify(row.raw, null, 2);
  return `
    <tr class="sub-row raw-row" data-panel-raw-rid="${escapeHtml(row.rid)}">
      <td colspan="5">
        <div class="sub-panel">
          <div class="sub-heading">
            <strong>Raw JSON</strong>
            <span class="mono muted">${escapeHtml(row.rid)}</span>
          </div>
          <pre>${escapeHtml(raw)}</pre>
        </div>
      </td>
    </tr>
  `;
}

function availabilityPanelHtml(rid, state) {
  const query = state.query || defaultAvailabilityQuery();
  const url = reservableAvailabilityUrl(rid, query);
  const result = availabilityResultHtml(state);
  return `
    <tr class="availability-row" data-panel-rid="${escapeHtml(rid)}">
      <td colspan="5">
        <div class="availability-panel">
          <form class="availability-controls" data-action="availability-query" data-rid="${escapeHtml(rid)}">
            <label>
              Start
              <input name="start" type="date" value="${escapeHtml(query.start)}">
            </label>
            <label>
              Days
              <input name="days" type="number" min="1" max="60" value="${escapeHtml(query.days)}">
            </label>
            <label>
              Min nights
              <input name="min_nights" type="number" min="1" max="31" value="${escapeHtml(query.minNights)}">
            </label>
            <label class="availability-force">
              <input name="force" type="checkbox"${query.force ? ' checked' : ''}>
              Force refresh
            </label>
            <div class="actions">
              <button class="primary" type="submit"${state.loading ? ' disabled' : ''}>Query</button>
            </div>
          </form>
          <div class="mono muted availability-url">${escapeHtml(url)}</div>
          ${result}
          <div class="availability-footer">
            <button type="button" data-action="close-availability" data-rid="${escapeHtml(rid)}">Close</button>
          </div>
        </div>
      </td>
    </tr>
  `;
}

function availabilityResultHtml(state) {
  if (state.loading) {
    return '<div class="availability-summary">Loading availability...</div>';
  }
  if (state.error) {
    return `<div class="availability-summary error">${escapeHtml(state.error)}</div>`;
  }
  if (!state.data) {
    return '<div class="availability-summary">Edit query parameters, then run the request.</div>';
  }
  const body = state.data;
  const days = Array.isArray(body.availability) ? body.availability : [];
  const pills = days.slice(0, 14).map(dayPillHtml).join('');
  const remainder = days.length > 14 ? `<span class="muted">+${days.length - 14} more</span>` : '';
  return `
    <div class="availability-result">
      <div class="availability-summary">
        <strong>${escapeHtml(body.summary || body.state || 'Availability response')}</strong>
        ${body.provider ? ` · ${escapeHtml(body.provider)}` : ''}
      </div>
      <div class="availability-days">${pills}${remainder}</div>
      <details class="json-details">
        <summary><span class="action-icon inline" aria-hidden="true"></span><span>JSON</span></summary>
        <pre>${escapeHtml(JSON.stringify(body, null, 2))}</pre>
      </details>
    </div>
  `;
}

function dayPillHtml(day) {
  const status = String(day.status || '').toLowerCase();
  const cls = ['available', 'partial'].includes(status) ? status : '';
  const count = `${Number(day.available_count || 0)} of ${Number(day.total || 0)}`;
  return `
    <span class="day-pill ${cls}">
      <span>${escapeHtml(day.date || '')}</span>
      <span>${escapeHtml(status || 'unknown')}</span>
      <span>${escapeHtml(count)}</span>
    </span>
  `;
}

function defaultAvailabilityQuery() {
  return {
    start: utcYmd(new Date()),
    days: '7',
    minNights: '1',
    force: false,
  };
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

function closeAvailability(rid) {
  const state = panelState(rid);
  state.expanded = false;
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

function availabilityQueryFromForm(formEl) {
  const data = new FormData(formEl);
  return {
    start: String(data.get('start') || '').trim(),
    days: String(data.get('days') || '7').trim() || '7',
    minNights: String(data.get('min_nights') || '1').trim() || '1',
    force: data.get('force') === 'on',
  };
}

function utcYmd(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function dash(value) {
  const text = value == null || value === '' ? '—' : String(value);
  return `<span${text === '—' ? ' class="muted"' : ''}>${escapeHtml(text)}</span>`;
}

function reservablePageUrl(row) {
  return `/reservables?id=${encodeURIComponent(row.rid)}`;
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

resultsEl.addEventListener('click', (event) => {
  const toggle = event.target.closest('[data-action="toggle-availability"]');
  if (toggle) {
    toggleAvailability(toggle.dataset.rid || '');
    return;
  }

  const raw = event.target.closest('[data-action="toggle-raw"]');
  if (raw) {
    toggleRaw(raw.dataset.rid || '');
    return;
  }

  const close = event.target.closest('[data-action="close-availability"]');
  if (close) closeAvailability(close.dataset.rid || '');
});

resultsEl.addEventListener('submit', (event) => {
  const queryForm = event.target.closest('[data-action="availability-query"]');
  if (!queryForm) return;
  event.preventDefault();
  queryAvailability(queryForm.dataset.rid || '', queryForm);
});

applyParamsFromUrl();
runSearch();
