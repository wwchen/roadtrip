import { apiCallLabel } from './result-table.js';

export function mountPollerForm(root, { onCreate, onUpdate, onCancel, onError } = {}) {
  root.innerHTML = pollerFormHtml();

  const form = root.querySelector('form');
  const titleEl = root.querySelector('[data-role="poller-form-title"]');
  const idEl = root.querySelector('[data-role="poller-form-id"]');
  const apiEl = root.querySelector('[data-role="poller-form-api"]');
  const submitEl = root.querySelector('[data-role="poller-form-submit"]');
  const cancelEl = root.querySelector('[data-action="cancel-poller-edit"]');
  const statusFieldEl = form.elements.status.closest('label');
  const forceFieldEl = form.elements.force.closest('label');
  let mode = 'create';
  let editId = null;

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    let body;
    try {
      body = pollerBodyFromForm(form, { includeForce: mode !== 'edit' });
    } catch (err) {
      onError?.(err);
      return;
    }
    if (mode === 'edit') {
      onUpdate?.(editId, body);
      return;
    }
    onCreate?.(body);
  });

  form.querySelector('[data-action="reset-poller-form"]').addEventListener('click', () => {
    reset();
  });
  cancelEl.addEventListener('click', () => {
    reset();
    onCancel?.();
  });

  function reset() {
    mode = 'create';
    editId = null;
    fillPollerForm(form, defaultPollerValues());
    syncMode();
  }

  function syncMode() {
    const editing = mode === 'edit';
    titleEl.textContent = editing ? 'Update poller' : 'Create poller';
    idEl.textContent = editing ? `#${editId}` : '';
    apiEl.innerHTML = editing
      ? apiCallLabel({ method: 'PATCH', path: `/api/reservables/availability/pollers/${editId}` })
      : apiCallLabel({ method: 'POST', path: '/api/reservables/availability/pollers' });
    submitEl.textContent = editing ? 'Update' : 'Create';
    cancelEl.hidden = !editing;
    statusFieldEl.hidden = !editing;
    forceFieldEl.hidden = editing;
    form.elements.force.disabled = editing;
  }

  reset();

  return {
    form,
    reset,
    edit(poller) {
      mode = 'edit';
      editId = String(poller.id || '');
      fillPollerForm(form, pollerValues(poller));
      syncMode();
      form.elements.scope_value.focus();
    },
    setBusy(busy) {
      form.querySelectorAll('input, select, textarea, button').forEach((el) => {
        el.disabled = busy;
      });
      if (!busy) syncMode();
    },
  };
}

function pollerFormHtml() {
  return `
    <form id="poller-form" class="panel poller-form" autocomplete="off">
      <div class="poller-form-header">
        <div>
          <strong data-role="poller-form-title"></strong>
          <span class="mono muted" data-role="poller-form-id"></span>
        </div>
        <div data-role="poller-form-api"></div>
      </div>
      <div class="filters poller-fields">
        <label>
          Scope
          <select name="scope_type">
            <option value="rid">Reservable</option>
            <option value="poi_id">POI</option>
          </select>
        </label>
        <label class="wide">
          Scope ID
          <input name="scope_value" placeholder="site:recgov:100">
        </label>
        <label>
          Status
          <select name="status">
            <option value="active">active</option>
            <option value="paused">paused</option>
            <option value="done">done</option>
          </select>
        </label>
        <label>
          Min nights
          <input name="min_nights" type="number" min="1" max="31" value="1">
        </label>
        <label>
          Cadence
          <input name="cadence" type="number" min="5" value="300">
        </label>
        <label class="wide">
          Target dates
          <textarea name="target_dates" spellcheck="false"></textarea>
        </label>
        <label>
          Type
          <input name="filter_type" placeholder="site">
        </label>
        <label>
          Vendor
          <input name="filter_vendor" placeholder="recgov">
        </label>
        <label>
          Vendor ID
          <input name="filter_vendor_id" placeholder="330257">
        </label>
        <label>
          Name
          <input name="filter_name">
        </label>
        <label>
          Loop
          <input name="filter_loop">
        </label>
        <label>
          Site type
          <input name="filter_site_type">
        </label>
        <label class="wide">
          Raw contains
          <textarea name="filter_raw" spellcheck="false"></textarea>
        </label>
        <label>
          Trigger actions
          <input name="trigger_actions" value="notify_slack">
        </label>
        <label class="poller-check">
          <input name="stop_when_triggered" type="checkbox" checked>
          Stop when triggered
        </label>
        <label class="poller-check">
          <input name="force" type="checkbox">
          Force
        </label>
        <div class="actions">
          <button class="primary" type="submit" data-role="poller-form-submit"></button>
          <button data-action="reset-poller-form" type="button">Reset</button>
          <button data-action="cancel-poller-edit" type="button" hidden>Cancel</button>
        </div>
      </div>
    </form>
  `;
}

function fillPollerForm(form, values) {
  form.elements.scope_type.value = values.scopeType;
  form.elements.scope_value.value = values.scopeValue;
  form.elements.status.value = values.status;
  form.elements.min_nights.value = values.minNights;
  form.elements.cadence.value = values.cadence;
  form.elements.target_dates.value = values.targetDates;
  form.elements.filter_type.value = values.filters.type;
  form.elements.filter_vendor.value = values.filters.vendor;
  form.elements.filter_vendor_id.value = values.filters.vendorId;
  form.elements.filter_name.value = values.filters.name;
  form.elements.filter_loop.value = values.filters.loop;
  form.elements.filter_site_type.value = values.filters.siteType;
  form.elements.filter_raw.value = values.filters.raw;
  form.elements.trigger_actions.value = values.triggerActions;
  form.elements.stop_when_triggered.checked = values.stopWhenTriggered;
  form.elements.force.checked = false;
}

function pollerValues(poller) {
  const scope = poller.scope || {};
  const filters = poller.reservable_filters || {};
  return {
    scopeType: scope.poi_id != null ? 'poi_id' : 'rid',
    scopeValue: scope.poi_id != null ? String(scope.poi_id) : String(scope.rid || ''),
    status: String(poller.status || 'active'),
    minNights: String(poller.min_nights || 1),
    cadence: String(poller.cadence || 300),
    targetDates: (poller.target_dates || []).join('\n'),
    filters: {
      type: listValue(filters.type),
      vendor: listValue(filters.vendor),
      vendorId: listValue(filters.vendor_id),
      name: listValue(filters.name),
      loop: listValue(filters.loop),
      siteType: listValue(filters.site_type),
      raw: filters.raw ? JSON.stringify(filters.raw) : '',
    },
    triggerActions: listValue(poller.trigger_actions || ['notify_slack']),
    stopWhenTriggered: poller.stop_when_triggered !== false,
  };
}

function defaultPollerValues() {
  return {
    scopeType: 'rid',
    scopeValue: '',
    status: 'active',
    minNights: '1',
    cadence: '300',
    targetDates: utcYmd(new Date()),
    filters: {
      type: '',
      vendor: '',
      vendorId: '',
      name: '',
      loop: '',
      siteType: '',
      raw: '',
    },
    triggerActions: 'notify_slack',
    stopWhenTriggered: true,
  };
}

function pollerBodyFromForm(form, { includeForce }) {
  const scope = scopeFromForm(form);
  const body = {
    scope,
    reservable_filters: filtersFromForm(form),
    target_dates: listFromText(form.elements.target_dates.value),
    min_nights: intFromField(form.elements.min_nights, 'min_nights'),
    cadence: intFromField(form.elements.cadence, 'cadence'),
    trigger_actions: listFromText(form.elements.trigger_actions.value),
    stop_when_triggered: form.elements.stop_when_triggered.checked,
    status: form.elements.status.value,
  };
  if (includeForce) {
    delete body.status;
    body.force = form.elements.force.checked;
  }
  return body;
}

function scopeFromForm(form) {
  const value = String(form.elements.scope_value.value || '').trim();
  if (!value) throw new Error('Scope ID is required');
  if (form.elements.scope_type.value === 'poi_id') {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw new Error('POI scope must be a positive integer');
    return { poi_id: id };
  }
  return { rid: value };
}

function filtersFromForm(form) {
  const filters = {};
  setList(filters, 'type', form.elements.filter_type.value);
  setList(filters, 'vendor', form.elements.filter_vendor.value);
  setList(filters, 'vendor_id', form.elements.filter_vendor_id.value);
  setList(filters, 'name', form.elements.filter_name.value);
  setList(filters, 'loop', form.elements.filter_loop.value);
  setList(filters, 'site_type', form.elements.filter_site_type.value);
  const raw = String(form.elements.filter_raw.value || '').trim();
  if (raw) {
    try {
      filters.raw = JSON.parse(raw);
    } catch (err) {
      throw new Error('Raw contains must be valid JSON');
    }
  }
  return filters;
}

function setList(target, key, value) {
  const list = listFromText(value);
  if (list.length) target[key] = list;
}

function listFromText(value) {
  return String(value || '')
    .split(/[\n,]+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

function intFromField(field, name) {
  const value = Number(String(field.value || '').trim());
  if (!Number.isInteger(value) || value < Number(field.min || 1)) {
    throw new Error(`${name} must be at least ${field.min || 1}`);
  }
  return value;
}

function listValue(value) {
  if (Array.isArray(value)) return value.join(', ');
  return value == null ? '' : String(value);
}

function utcYmd(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
