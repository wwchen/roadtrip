import { escapeHtml } from '../core.js';
import {
  createCampsiteAlert,
  validateCampsiteAlertIntegrations,
} from '../api/campsite-alert-api.js';
import { DRAWER_ROOT_ID } from './chrome.js';

const CAMPSITE_TYPES = [
  ['STANDARD NONELECTRIC', 'Standard'],
  ['STANDARD ELECTRIC', 'Electric'],
  ['TENT ONLY NONELECTRIC', 'Tent only'],
  ['RV NONELECTRIC', 'RV'],
  ['WALK TO NONELECTRIC', 'Walk-in'],
  ['GROUP STANDARD NONELECTRIC', 'Group'],
];

const EQUIPMENT_TYPES = [
  ['Tent', 'Tent'],
  ['Small RV', 'Small RV'],
  ['Large RV', 'Large RV'],
  ['Pickup Camper', 'Pickup camper'],
];

export function openCampsiteBookingPanel(feature, { signal, onBack } = {}) {
  document.getElementById(DRAWER_ROOT_ID)?.classList.add('full');
  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  if (!content) return;
  content.innerHTML = renderPanel(feature, Boolean(onBack));
  wirePanel(content, feature, { signal, onBack });
}

function renderPanel(feature, showBack) {
  const p = feature.properties || {};
  const name = p.name || `Campground ${p.recgov_id || ''}`.trim();
  const parent = p.parent_name || p.typeLabel || '';
  const region = p.state || p.country || '';
  const subline = ['Campsite alert', parent, region].filter(Boolean).map(escapeHtml).join(' &middot; ');
  const defaults = defaultDates();

  return `
    <header class="cg-drawer-head cg-booking-head">
      ${showBack ? '<button type="button" class="cg-icon-btn cg-booking-back" aria-label="Back to campground" title="Back">&larr;</button>' : ''}
      <div class="cg-booking-title">
        <h2>${escapeHtml(name)}</h2>
        <div class="cg-sub">${subline}</div>
      </div>
    </header>

    <form id="campground-booking-panel" class="cg-booking-form">
      <div class="cg-booking-grid">
        <label class="cg-field">
          <span>Arrival</span>
          <input type="date" id="cg-booking-start" required value="${defaults.start}">
        </label>
        <label class="cg-field">
          <span>Departure</span>
          <input type="date" id="cg-booking-end" required value="${defaults.end}">
        </label>
        <label class="cg-field">
          <span>Min nights</span>
          <input type="number" id="cg-booking-min-nights" min="1" max="14" value="1">
        </label>
        <label class="cg-field">
          <span>Party size</span>
          <input type="number" id="cg-booking-max-people" min="1" max="100" placeholder="Any">
        </label>
      </div>

      <div class="cg-booking-switches">
        <label class="cg-check">
          <input type="checkbox" id="cg-booking-auto-cart" checked>
          <span>Auto-add first match to cart</span>
        </label>
        <label class="cg-check">
          <input type="checkbox" id="cg-booking-stop-after-match" checked>
          <span>Stop after first match</span>
        </label>
        <label class="cg-check">
          <input type="checkbox" id="cg-booking-notify-slack">
          <span>Notify via Slack</span>
        </label>
      </div>

      <details class="cg-details cg-booking-options">
        <summary>Site filters</summary>
        <div class="cg-filter-group">
          <div class="cg-filter-label">Site type</div>
          <div class="cg-check-grid" id="cg-booking-campsite-types">
            ${checkboxes(CAMPSITE_TYPES)}
          </div>
        </div>
        <div class="cg-filter-group">
          <div class="cg-filter-label">Equipment</div>
          <div class="cg-check-grid" id="cg-booking-equipment-types">
            ${checkboxes(EQUIPMENT_TYPES)}
          </div>
        </div>
        <label class="cg-field cg-field-wide">
          <span>Specific sites</span>
          <input type="text" id="cg-booking-specific-sites" placeholder="001, 005">
        </label>
      </details>

      <div class="cg-form-status" role="status" aria-live="polite"></div>
      <div class="cg-booking-actions">
        <button type="submit" class="cg-btn cg-btn-primary" id="cg-booking-submit">Create alert</button>
        <a class="cg-btn cg-btn-secondary" href="https://www.recreation.gov/camping/campgrounds/${encodeURIComponent(p.recgov_id)}" target="_blank" rel="noreferrer" data-cta="reserve">Reserve on rec.gov</a>
      </div>
    </form>
  `;
}

function wirePanel(content, feature, { signal, onBack } = {}) {
  content.querySelector('.cg-booking-back')?.addEventListener('click', () => {
    if (typeof onBack === 'function') onBack();
  });

  content.querySelector('#campground-booking-panel')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const submit = form.querySelector('#cg-booking-submit');
    const status = form.querySelector('.cg-form-status');
    const payload = buildPayload(feature, form);
    const validationError = validatePayload(payload);
    if (validationError) {
      setStatus(status, validationError, 'error');
      return;
    }

    try {
      setBusy(submit, 'Checking');
      setStatus(status, 'Checking integrations', '');
      const errors = await validateCampsiteAlertIntegrations({
        autoCart: payload.auto_cart,
        notifySlack: payload.notify_slack,
        signal,
      });
      if (errors.length) {
        setStatus(status, errors.join(' '), 'error');
        return;
      }

      setBusy(submit, 'Creating');
      const result = await createCampsiteAlert(payload, { signal });
      const id = result?.id ? ` #${result.id}` : '';
      setStatus(status, `Alert${id} created`, 'success');
    } catch (error) {
      if (error.name === 'AbortError') return;
      setStatus(status, `Could not create alert: ${error.message}`, 'error');
    } finally {
      setIdle(submit);
    }
  });
}

function buildPayload(feature, form) {
  const p = feature.properties || {};
  return {
    campground_id: String(p.recgov_id || ''),
    campground_name: p.name || `Campground ${p.recgov_id || ''}`.trim(),
    parent_name: p.parent_name || null,
    parent_id: p.parent_id || null,
    start_date: form.querySelector('#cg-booking-start').value,
    end_date: form.querySelector('#cg-booking-end').value,
    min_nights: numberOrDefault(form.querySelector('#cg-booking-min-nights').value, 1),
    campsite_types: checkedValues(form, '#cg-booking-campsite-types'),
    equipment_types: checkedValues(form, '#cg-booking-equipment-types'),
    max_people: numberOrNull(form.querySelector('#cg-booking-max-people').value),
    specific_sites: specificSites(form.querySelector('#cg-booking-specific-sites').value),
    notify_slack: form.querySelector('#cg-booking-notify-slack').checked,
    auto_cart: form.querySelector('#cg-booking-auto-cart').checked,
    stop_after_match: form.querySelector('#cg-booking-stop-after-match').checked,
  };
}

function validatePayload(payload) {
  if (!payload.campground_id) return 'This campground cannot create rec.gov alerts.';
  if (!payload.start_date || !payload.end_date) return 'Arrival and departure are required.';
  if (payload.start_date >= payload.end_date) return 'Departure must be after arrival.';
  if (payload.min_nights < 1) return 'Min nights must be at least 1.';
  return '';
}

function checkedValues(form, selector) {
  return [...form.querySelectorAll(`${selector} input:checked`)].map((input) => input.value);
}

function specificSites(value) {
  return String(value || '')
    .split(',')
    .map((site) => site.trim())
    .filter(Boolean)
    .map((site) => site.padStart(3, '0'));
}

function numberOrDefault(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function numberOrNull(value) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function checkboxes(options) {
  return options.map(([value, label]) => `
    <label class="cg-check">
      <input type="checkbox" value="${escapeHtml(value)}">
      <span>${escapeHtml(label)}</span>
    </label>
  `).join('');
}

function defaultDates() {
  const start = new Date();
  start.setDate(start.getDate() + 1);
  const end = new Date(start);
  end.setDate(end.getDate() + 2);
  return {
    start: start.toISOString().slice(0, 10),
    end: end.toISOString().slice(0, 10),
  };
}

function setStatus(element, message, tone) {
  if (!element) return;
  element.textContent = message;
  element.classList.toggle('error', tone === 'error');
  element.classList.toggle('success', tone === 'success');
}

function setBusy(button, text) {
  if (!button) return;
  if (!button.dataset.idleText) button.dataset.idleText = button.textContent;
  button.disabled = true;
  button.textContent = text;
}

function setIdle(button) {
  if (!button) return;
  button.disabled = false;
  button.textContent = button.dataset.idleText || button.textContent;
  delete button.dataset.idleText;
}
