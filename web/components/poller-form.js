export function mountPollerForm(root, { onCreate, onUpdate, onCancel, onError } = {}) {
  const form = root.querySelector('form');
  if (!form) throw new Error('Poller form markup is missing');

  const titleEl = root.querySelector('[data-role="poller-form-title"]');
  const idEl = root.querySelector('[data-role="poller-form-id"]');
  const apiEl = root.querySelector('[data-role="poller-form-api"]');
  const submitEl = root.querySelector('[data-role="poller-form-submit"]');
  const cancelEl = root.querySelector('[data-action="cancel-poller-edit"]');
  const addDateEl = root.querySelector('[data-action="add-target-date"]');
  const dateInputEl = form.elements.target_date;
  const datesEl = root.querySelector('[data-role="target-dates"]');
  const statusFieldEl = form.elements.status.closest('label');
  const forceFieldEl = form.elements.force.closest('label');
  let mode = 'create';
  let editId = null;
  let targetDates = [];

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    let body;
    try {
      body = bodyFromForm({ includeForce: mode !== 'edit' });
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

  addDateEl.addEventListener('click', () => {
    addTargetDate(dateInputEl.value);
  });

  dateInputEl.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    addTargetDate(dateInputEl.value);
  });

  datesEl.addEventListener('click', (event) => {
    const button = event.target.closest('[data-action="remove-target-date"]');
    if (!button) return;
    removeTargetDate(button.dataset.date || '');
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
    fillPollerForm(defaultPollerValues());
    syncMode();
  }

  function syncMode() {
    const editing = mode === 'edit';
    titleEl.textContent = editing ? 'Update poller' : 'Create poller';
    idEl.textContent = editing ? `#${editId}` : '';
    renderApiLabel(apiEl, {
      method: editing ? 'PATCH' : 'POST',
      path: editing
        ? `/api/reservables/availability/pollers/${editId}`
        : '/api/reservables/availability/pollers',
    });
    submitEl.textContent = editing ? 'Update' : 'Create';
    cancelEl.hidden = !editing;
    statusFieldEl.hidden = !editing;
    forceFieldEl.hidden = editing;
    form.elements.force.disabled = editing;
  }

  function fillPollerForm(values) {
    form.elements.scope_type.value = values.scopeType;
    form.elements.scope_value.value = values.scopeValue;
    form.elements.status.value = values.status;
    form.elements.min_nights.value = values.minNights;
    form.elements.cadence.value = values.cadence;
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
    setTargetDates(values.targetDates);
  }

  function bodyFromForm({ includeForce }) {
    if (!targetDates.length) throw new Error('Add at least one target date');
    const body = {
      scope: scopeFromForm(form),
      reservable_filters: filtersFromForm(form),
      target_dates: targetDates,
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

  function addTargetDate(date) {
    const value = String(date || '').trim();
    if (!value) return;
    targetDates = [...new Set([...targetDates, value])].sort();
    dateInputEl.value = value;
    renderTargetDates();
  }

  function removeTargetDate(date) {
    targetDates = targetDates.filter((value) => value !== date);
    if (dateInputEl.value === date) {
      dateInputEl.value = targetDates[0] || utcYmd(new Date());
    }
    renderTargetDates();
  }

  function setTargetDates(dates) {
    targetDates = [...new Set(dates.filter(Boolean))].sort();
    dateInputEl.value = targetDates[0] || utcYmd(new Date());
    renderTargetDates();
  }

  function renderTargetDates() {
    datesEl.replaceChildren();
    if (!targetDates.length) {
      datesEl.append(textSpan('muted', 'No dates selected.'));
      return;
    }
    targetDates.forEach((date) => {
      const chip = document.createElement('span');
      chip.className = 'date-chip';
      chip.append(textSpan('', date));

      const button = document.createElement('button');
      button.type = 'button';
      button.dataset.action = 'remove-target-date';
      button.dataset.date = date;
      button.setAttribute('aria-label', `Remove ${date}`);
      chip.append(button);
      datesEl.append(chip);
    });
  }

  reset();

  return {
    form,
    reset,
    edit(poller) {
      mode = 'edit';
      editId = String(poller.id || '');
      fillPollerForm(pollerValues(poller));
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

function renderApiLabel(root, { method, path }) {
  const wrapper = document.createElement('span');
  wrapper.className = 'api-call api-call-static';
  wrapper.append(textSpan('api-method', method), textSpan('api-path', path));
  root.replaceChildren(wrapper);
}

function textSpan(className, text) {
  const span = document.createElement('span');
  if (className) span.className = className;
  span.textContent = text;
  return span;
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
    targetDates: poller.target_dates || [],
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
    targetDates: [utcYmd(new Date())],
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
