import { createWatch, deleteWatch, getWatch, listWatches, updateWatch } from '/web/api/watches-api.js';

const filterForm = document.getElementById('filter-form');
const createForm = document.getElementById('create-form');
const statusEl = document.getElementById('status');
const resultsEl = document.getElementById('results');
const emptyEl = document.getElementById('empty');
const formTitleEl = document.getElementById('create-form-title');
const formSubmitEl = document.getElementById('create-form-submit');
const formCancelEl = document.getElementById('create-form-cancel');

// null = create mode; number = editing this watch id
let editingId = null;

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
  if (editingId != null) {
    const body = buildUpdatePayload(fd);
    if (!body) return;
    try {
      await updateWatch(editingId, body);
      exitEditMode();
      await refresh();
    } catch (err) {
      statusEl.textContent = `Update failed: ${err.message}${err.body ? ` — ${err.body}` : ''}`;
    }
    return;
  }
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

formCancelEl.addEventListener('click', () => {
  exitEditMode();
});

function buildUpdatePayload(fd) {
  // Update path: scope (poi_id / reservable_rid) is locked; we never send it.
  // Everything else is editable.
  let filters = {};
  let triggerConfig = {};
  try {
    filters = JSON.parse(fd.get('reservable_filters') || '{}');
    triggerConfig = JSON.parse(fd.get('trigger_config') || '{}');
  } catch (err) {
    statusEl.textContent = `Invalid JSON: ${err.message}`;
    return null;
  }
  const triggerKinds = (fd.get('trigger_kinds') || '')
    .split(',').map((s) => s.trim()).filter(Boolean);
  return {
    reservable_filters: filters,
    start_date: String(fd.get('start_date') || '').trim(),
    end_date: String(fd.get('end_date') || '').trim(),
    cadence_sec: Number(fd.get('cadence_sec') || 60),
    trigger_kinds: triggerKinds,
    trigger_config: triggerConfig,
    stop_when_triggered: fd.get('stop_when_triggered') === 'on',
  };
}

async function enterEditMode(id) {
  try {
    const data = await getWatch(id);
    const w = data.watch;
    editingId = w.id;
    // Fill the form with the current values.
    const set = (name, value) => {
      const input = createForm.querySelector(`[name="${name}"]`);
      if (input) input.value = value;
    };
    set('poi_id', w.poi_id != null ? String(w.poi_id) : '');
    set('reservable_rid', w.reservable?.rid ?? '');
    set('start_date', w.start_date);
    set('end_date', w.end_date);
    set('cadence_sec', String(w.cadence_sec));
    set('trigger_kinds', w.trigger_kinds.join(', '));
    set('reservable_filters', JSON.stringify(w.reservable_filters));
    set('trigger_config', JSON.stringify(w.trigger_config));
    const checkbox = createForm.querySelector('[name="stop_when_triggered"]');
    if (checkbox) checkbox.checked = !!w.stop_when_triggered;
    // Lock scope inputs; PATCH doesn't accept these.
    createForm.querySelector('[name="poi_id"]').readOnly = true;
    createForm.querySelector('[name="reservable_rid"]').readOnly = true;
    formTitleEl.textContent = `Edit watch #${w.id}`;
    formSubmitEl.textContent = 'Update';
    formCancelEl.hidden = false;
    createForm.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (err) {
    statusEl.textContent = `Load failed: ${err.message}`;
  }
}

function exitEditMode() {
  editingId = null;
  createForm.reset();
  createForm.querySelector('[name="poi_id"]').readOnly = false;
  createForm.querySelector('[name="reservable_rid"]').readOnly = false;
  formTitleEl.textContent = 'Create watch';
  formSubmitEl.textContent = 'Create';
  formCancelEl.hidden = true;
}

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
  const triggerKinds = (fd.get('trigger_kinds') || '')
    .split(',').map((s) => s.trim()).filter(Boolean);
  return {
    poi_id: poiId ? Number(poiId) : null,
    reservable_rid: reservableRid || null,
    reservable_filters: filters,
    start_date: String(fd.get('start_date') || '').trim(),
    end_date: String(fd.get('end_date') || '').trim(),
    cadence_sec: Number(fd.get('cadence_sec') || 60),
    trigger_kinds: triggerKinds,
    trigger_config: triggerConfig,
    stop_when_triggered: fd.get('stop_when_triggered') === 'on',
  };
}

function prefillCreateFormFromUrl() {
  const params = new URLSearchParams(window.location.search);
  const fields = ['poi_id', 'reservable_rid', 'start_date', 'end_date', 'cadence_sec', 'trigger_kinds'];
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
  const dates = `${w.start_date} to ${w.end_date}`;
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
          ? `<button data-action="pause" data-id="${escapeHtml(w.id)}" title="Pause">⏸</button>`
          : w.status === 'paused'
            ? `<button data-action="resume" data-id="${escapeHtml(w.id)}" title="Resume">▶</button>`
            : ''}
        <button data-action="edit" data-id="${escapeHtml(w.id)}" title="Edit">✎</button>
        <button data-action="delete" data-id="${escapeHtml(w.id)}" title="Delete">✕</button>
      </td>
    </tr>`;
}

async function onAction(e) {
  const btn = e.currentTarget;
  const id = btn.dataset.id;
  const action = btn.dataset.action;
  try {
    if (action === 'pause') {
      await updateWatch(id, { status: 'paused' });
      await refresh();
    } else if (action === 'resume') {
      await updateWatch(id, { status: 'active' });
      await refresh();
    } else if (action === 'delete') {
      await deleteWatch(id);
      // If we were editing this row, reset the form.
      if (editingId != null && String(editingId) === String(id)) exitEditMode();
      await refresh();
    } else if (action === 'edit') {
      await enterEditMode(Number(id));
    }
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
