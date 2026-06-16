import { createWatch, deleteWatch, listWatches, updateWatch } from '/web/api/watches-api.js';

const filterForm = document.getElementById('filter-form');
const createForm = document.getElementById('create-form');
const statusEl = document.getElementById('status');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');

filterForm.addEventListener('submit', (e) => {
  e.preventDefault();
  refresh();
});
filterForm.addEventListener('reset', () => {
  setTimeout(refresh, 0);
});

createForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(createForm);
  const body = buildCreatePayload(fd);
  if (!body) return;
  try {
    await createWatch(body);
    createForm.reset();
    await refresh();
  } catch (err) {
    statusEl.textContent = `Create failed: ${err.message}${err.body ? ` — ${err.body}` : ''}`;
  }
});

function buildCreatePayload(fd) {
  const poiId = (fd.get('poi_id') || '').trim();
  const reservableRid = (fd.get('reservable_rid') || '').trim();
  if (!poiId === !reservableRid) {
    statusEl.textContent = 'Set exactly one of POI ID or Reservable RID.';
    return null;
  }
  let filters = {};
  let triggerConfig = {};
  try {
    filters = JSON.parse(fd.get('reservable_filters') || '{}');
    triggerConfig = JSON.parse(fd.get('trigger_config') || '{}');
  } catch (err) {
    statusEl.textContent = `Invalid JSON: ${err.message}`;
    return null;
  }
  const targetDates = (fd.get('target_dates') || '')
    .split(',').map((s) => s.trim()).filter(Boolean);
  const triggerKinds = (fd.get('trigger_kinds') || '')
    .split(',').map((s) => s.trim()).filter(Boolean);
  return {
    poi_id: poiId ? Number(poiId) : null,
    reservable_rid: reservableRid || null,
    reservable_filters: filters,
    target_dates: targetDates,
    min_nights: Number(fd.get('min_nights') || 1),
    cadence_sec: Number(fd.get('cadence_sec') || 60),
    trigger_kinds: triggerKinds,
    trigger_config: triggerConfig,
    stop_when_triggered: fd.get('stop_when_triggered') === 'on',
  };
}

function prefillCreateFormFromUrl() {
  const params = new URLSearchParams(window.location.search);
  const fields = ['poi_id', 'reservable_rid', 'target_dates', 'min_nights', 'cadence_sec', 'trigger_kinds'];
  let prefilled = false;
  for (const name of fields) {
    const value = params.get(name);
    if (value == null) continue;
    const input = createForm.querySelector(`[name="${name}"]`);
    if (input) {
      input.value = value;
      prefilled = true;
    }
  }
  if (prefilled) {
    document.getElementById('create-panel')?.setAttribute('open', '');
    createForm.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

async function refresh() {
  const fd = new FormData(filterForm);
  const params = {
    status: fd.get('status') || undefined,
    poiId: fd.get('poi_id') || undefined,
    reservableId: fd.get('reservable_id') || undefined,
  };
  statusEl.textContent = 'Loading…';
  try {
    const data = await listWatches(params);
    statusEl.textContent = `${data.total} watch${data.total === 1 ? '' : 'es'}.`;
    render(data.watches);
  } catch (err) {
    statusEl.textContent = `Error: ${err.message}`;
    resultsEl.innerHTML = '';
  }
}

function render(watches) {
  if (watches.length === 0) {
    emptyEl.hidden = false;
    resultsEl.innerHTML = '';
    return;
  }
  emptyEl.hidden = true;
  const rows = watches.map(renderRow).join('');
  resultsEl.innerHTML = `
    <table class="data-table">
      <thead><tr>
        <th>id</th><th>scope</th><th>dates</th>
        <th>cadence</th><th>triggers</th><th>status</th><th>actions</th>
      </tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
  resultsEl.querySelectorAll('[data-action]').forEach((btn) => {
    btn.addEventListener('click', onAction);
  });
}

function renderRow(w) {
  const scope = w.poi_id != null
    ? `poi:${w.poi_id}${Object.keys(w.reservable_filters).length ? ' (filtered)' : ''}`
    : `resv:${w.reservable?.rid ?? w.reservable_id}`;
  const dates = `${w.target_dates.length} dt${w.target_dates.length === 1 ? '' : 's'}`;
  const triggers = w.trigger_kinds.join(', ');
  return `
    <tr>
      <td>
        <a href="/watches/${encodeURIComponent(w.id)}">${escapeHtml(w.id)}</a>
      </td>
      <td>${escapeHtml(scope)}</td>
      <td>${escapeHtml(dates)}</td>
      <td>${escapeHtml(w.cadence_sec)}s</td>
      <td>${escapeHtml(triggers)}</td>
      <td>${escapeHtml(w.status)}</td>
      <td>
        ${w.status === 'active'
          ? `<button data-action="pause" data-id="${escapeHtml(w.id)}">⏸</button>`
          : w.status === 'paused'
            ? `<button data-action="resume" data-id="${escapeHtml(w.id)}">▶</button>`
            : ''}
        <button data-action="delete" data-id="${escapeHtml(w.id)}">✕</button>
      </td>
    </tr>`;
}

async function onAction(e) {
  const btn = e.currentTarget;
  const id = btn.dataset.id;
  const action = btn.dataset.action;
  try {
    if (action === 'pause') await updateWatch(id, { status: 'paused' });
    else if (action === 'resume') await updateWatch(id, { status: 'active' });
    else if (action === 'delete') await deleteWatch(id);
    await refresh();
  } catch (err) {
    statusEl.textContent = `Action failed: ${err.message}`;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

prefillCreateFormFromUrl();
refresh();
