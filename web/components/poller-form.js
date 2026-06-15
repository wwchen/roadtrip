import { apiCallLabel, replaceChildren } from './result-table.js';

export function mountPollerGetForm(root, { onSubmit, onReset } = {}) {
  const form = root.querySelector('form');
  if (!form) throw new Error('Poller GET form markup is missing');
  const apiEl = root.querySelector('[data-role="poller-get-api"]');
  const resetBtn = root.querySelector('[data-action="reset-poller-get"]');

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    syncApiLabel();
    onSubmit?.();
  });

  form.addEventListener('input', syncApiLabel);
  form.addEventListener('change', syncApiLabel);

  resetBtn.addEventListener('click', () => {
    form.reset();
    form.elements.limit.value = '100';
    form.elements.offset.value = '0';
    syncApiLabel();
    onReset?.();
  });

  function params() {
    return {
      id: clean(form.elements.id.value),
      limit: clean(form.elements.limit.value) || '100',
      offset: clean(form.elements.offset.value) || '0',
    };
  }

  function applyParamsFromUrl(search = window.location.search) {
    const qs = new URLSearchParams(search);
    form.elements.id.value = qs.get('id') || '';
    form.elements.limit.value = qs.get('limit') || '100';
    form.elements.offset.value = qs.get('offset') || '0';
    syncApiLabel();
  }

  function syncApiLabel() {
    const values = params();
    const path = values.id
      ? `/api/reservables/availability/pollers/${encodeURIComponent(values.id)}`
      : pollerListApiPath(values);
    replaceChildren(apiEl, apiCallLabel({ method: 'GET', path }));
  }

  return {
    form,
    params,
    applyParamsFromUrl,
    setBusy: (busy) => setFormBusy(form, busy),
  };
}

export function mountPollerMutationForm(root, { mode, onSubmit, onReset, onError } = {}) {
  const form = root.querySelector('form');
  if (!form) throw new Error(`Poller ${mode} form markup is missing`);

  const apiEl = root.querySelector('[data-role="poller-mutation-api"]');
  const addDateEl = root.querySelector('[data-action="add-target-date"]');
  const resetBtn = root.querySelector('[data-action="reset-poller-form"]');
  const dateInputEl = form.elements.target_date;
  const datesEl = root.querySelector('[data-role="target-dates"]');
  const detailsEl = root.closest('details');
  let targetDates = [];

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    try {
      onSubmit?.(submitPayload());
    } catch (err) {
      onError?.(err);
    }
  });

  form.addEventListener('input', syncApiLabel);
  form.addEventListener('change', syncApiLabel);

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

  resetBtn.addEventListener('click', () => {
    reset();
    onReset?.();
  });

  function submitPayload() {
    if (mode === 'update') {
      const id = clean(form.elements.poller_id.value);
      if (!id) throw new Error('Poller ID is required');
      return { id, body: bodyFromForm(form, { mode, targetDates }) };
    }
    return { body: bodyFromForm(form, { mode, targetDates }) };
  }

  function reset() {
    fillPollerForm(form, defaultPollerValues(mode));
    setTargetDates(mode === 'create' ? [utcYmd(new Date())] : []);
    syncApiLabel();
  }

  function edit(poller) {
    if (mode !== 'update') return;
    form.elements.poller_id.value = String(poller.id || '');
    fillPollerForm(form, pollerValues(poller));
    setTargetDates(poller.target_dates || []);
    syncApiLabel();
    if (detailsEl) detailsEl.open = true;
    form.elements.poller_id.focus();
  }

  function applyParamsFromUrl(search = window.location.search) {
    if (mode !== 'create') return false;
    const qs = new URLSearchParams(search);
    if (qs.get('action') !== 'create') return false;
    const values = defaultPollerValues(mode);
    values.scopeType = qs.get('scope_type') || values.scopeType;
    values.scopeValue = qs.get('scope_value') || values.scopeValue;
    values.minNights = qs.get('min_nights') || values.minNights;
    values.cadence = qs.get('cadence') || values.cadence;
    values.triggerActions = qs.get('trigger_actions') || values.triggerActions;
    values.stopWhenTriggered = booleanParam(qs.get('stop_when_triggered'), values.stopWhenTriggered);
    fillPollerForm(form, values);
    setTargetDates(targetDatesFromParams(qs));
    if (form.elements.force) {
      form.elements.force.checked = booleanParam(qs.get('force'), false);
    }
    if (detailsEl) detailsEl.open = true;
    syncApiLabel();
    return true;
  }

  function syncApiLabel() {
    const id = mode === 'update' ? clean(form.elements.poller_id.value) : '';
    const path = mode === 'update'
      ? `/api/reservables/availability/pollers/${id || ':id'}`
      : '/api/reservables/availability/pollers';
    replaceChildren(apiEl, apiCallLabel({ method: mode === 'update' ? 'PATCH' : 'POST', path }));
  }

  function addTargetDate(date) {
    const value = clean(date);
    if (!value) return;
    targetDates = [...new Set([...targetDates, value])].sort();
    dateInputEl.value = value;
    renderTargetDates();
  }

  function removeTargetDate(date) {
    targetDates = targetDates.filter((value) => value !== date);
    if (dateInputEl.value === date) {
      dateInputEl.value = targetDates[0] || '';
    }
    renderTargetDates();
  }

  function setTargetDates(dates) {
    targetDates = [...new Set(dates.filter(Boolean))].sort();
    dateInputEl.value = targetDates[0] || '';
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
    edit,
    applyParamsFromUrl,
    setBusy: (busy) => setFormBusy(form, busy),
  };
}

function pollerListApiPath({ limit, offset }) {
  const qs = new URLSearchParams();
  if (limit && limit !== '100') qs.set('limit', limit);
  if (offset && offset !== '0') qs.set('offset', offset);
  const suffix = qs.toString();
  return `/api/reservables/availability/pollers${suffix ? `?${suffix}` : ''}`;
}

function bodyFromForm(form, { mode, targetDates }) {
  if (mode === 'create' && !targetDates.length) throw new Error('Add at least one target date');
  const body = {};
  const scope = scopeFromForm(form, { required: mode === 'create' });
  if (scope) body.scope = scope;

  const filters = filtersFromForm(form);
  if (mode === 'create' || Object.keys(filters).length) body.reservable_filters = filters;
  if (targetDates.length) body.target_dates = targetDates;

  setOptionalInt(body, 'min_nights', form.elements.min_nights);
  setOptionalInt(body, 'cadence', form.elements.cadence);

  const triggerActions = listFromText(form.elements.trigger_actions.value);
  if (mode === 'create' || triggerActions.length) body.trigger_actions = triggerActions;
  const includeStopWhenTriggered =
    mode === 'create' || form.dataset.loaded === 'true' || form.elements.stop_when_triggered.checked;
  if (includeStopWhenTriggered) {
    body.stop_when_triggered = form.elements.stop_when_triggered.checked;
  }

  if (mode === 'create') {
    body.force = form.elements.force.checked;
  } else if (form.elements.status.value) {
    body.status = form.elements.status.value;
  }

  return body;
}

function fillPollerForm(form, values) {
  form.dataset.loaded = values.id ? 'true' : 'false';
  if (form.elements.poller_id && values.id != null) form.elements.poller_id.value = values.id;
  form.elements.scope_type.value = values.scopeType;
  form.elements.scope_value.value = values.scopeValue;
  if (form.elements.status) form.elements.status.value = values.status;
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
  if (form.elements.force) form.elements.force.checked = false;
}

function pollerValues(poller) {
  const scope = poller.scope || {};
  const filters = poller.reservable_filters || {};
  return {
    id: String(poller.id || ''),
    scopeType: scope.poi_id != null ? 'poi_id' : 'rid',
    scopeValue: scope.poi_id != null ? String(scope.poi_id) : String(scope.rid || ''),
    status: String(poller.status || 'active'),
    minNights: String(poller.min_nights || ''),
    cadence: String(poller.cadence || ''),
    filters: {
      type: listValue(filters.type),
      vendor: listValue(filters.vendor),
      vendorId: listValue(filters.vendor_id),
      name: listValue(filters.name),
      loop: listValue(filters.loop),
      siteType: listValue(filters.site_type),
      raw: filters.raw ? JSON.stringify(filters.raw) : '',
    },
    triggerActions: listValue(poller.trigger_actions || []),
    stopWhenTriggered: poller.stop_when_triggered !== false,
  };
}

function defaultPollerValues(mode) {
  return {
    id: '',
    scopeType: 'rid',
    scopeValue: '',
    status: mode === 'update' ? '' : 'active',
    minNights: mode === 'create' ? '1' : '',
    cadence: mode === 'create' ? '300' : '',
    filters: {
      type: '',
      vendor: '',
      vendorId: '',
      name: '',
      loop: '',
      siteType: '',
      raw: '',
    },
    triggerActions: mode === 'create' ? 'notify_slack' : '',
    stopWhenTriggered: mode === 'create',
  };
}

function scopeFromForm(form, { required }) {
  const value = clean(form.elements.scope_value.value);
  if (!value) {
    if (required) throw new Error('Scope ID is required');
    return null;
  }
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
  const raw = clean(form.elements.filter_raw.value);
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

function setOptionalInt(target, key, field) {
  const text = clean(field.value);
  if (!text) return;
  const value = Number(text);
  if (!Number.isInteger(value) || value < Number(field.min || 1)) {
    throw new Error(`${key} must be at least ${field.min || 1}`);
  }
  target[key] = value;
}

function listFromText(value) {
  return String(value || '')
    .split(/[\n,]+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

function listValue(value) {
  if (Array.isArray(value)) return value.join(', ');
  return value == null ? '' : String(value);
}

function targetDatesFromParams(qs) {
  const dates = [
    ...qs.getAll('target_date'),
    ...String(qs.get('target_dates') || '').split(','),
  ].map(clean).filter(Boolean);
  return dates.length ? dates : [utcYmd(new Date())];
}

function booleanParam(value, fallback) {
  if (value == null || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function setFormBusy(form, busy) {
  form.querySelectorAll('input, select, textarea, button').forEach((el) => {
    el.disabled = busy;
  });
}

function textSpan(className, text) {
  const span = document.createElement('span');
  if (className) span.className = className;
  span.textContent = text;
  return span;
}

function clean(value) {
  return String(value || '').trim();
}

function utcYmd(date) {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
