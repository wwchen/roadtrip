// Landing: the first screen. Asks where the drive starts and ends, and hands
// a resolved origin/destination pair back to its caller.
//
// It owns no map and imports nothing from the map stack — that is the whole
// point of it existing. Geocoding goes through the same /api/geocode client
// the trip planner uses, so there is one geocoder in the app.

import { geocode } from '../api/geocode-api.js';
import { landingTemplate, suggestionsTemplate } from './landing-template.js';

const STYLE_ID = 'rt-landing-styles';
const STYLE_HREF = '/web/landing/landing.css';
// Shared sheet, injected id-guarded by whichever component needs it first —
// the same contract login-card and settings-modal use.
const BUTTONS_STYLE_ID = 'rt-buttons-styles';
const BUTTONS_STYLE_HREF = '/web/design-system/buttons.css';

const SUGGEST_DEBOUNCE_MS = 220;
const SUGGEST_LIMIT = 5;
const MIN_QUERY_LENGTH = 2;

const COPY = {
  title: 'Find a place to sleep along your route.',
  subtitle: 'Chargers, campgrounds, showers and cell coverage on one map — narrowed to what your car can reach.',
  originLabel: 'From',
  destinationLabel: 'To',
  submitLabel: 'Plan the route',
  browseLabel: 'Or browse the map',
};

const ERRORS = {
  origin: 'Pick a starting point from the list.',
  destination: 'Pick a destination from the list.',
  lookup: 'Could not reach the search service. Check your connection and try again.',
};

function injectStyles() {
  injectSheet(BUTTONS_STYLE_ID, BUTTONS_STYLE_HREF);
  injectSheet(STYLE_ID, STYLE_HREF);
}

function injectSheet(id, href) {
  if (document.getElementById(id)) return;
  const link = document.createElement('link');
  link.id = id;
  link.rel = 'stylesheet';
  link.href = href;
  document.head.appendChild(link);
}

/**
 * @param {HTMLElement} container
 * @param {{ onPlan: (stops: Array<{name,lng,lat,kind}>) => void, onBrowse: () => void }} config
 * @returns {{ dispose: () => void, focusOrigin: () => void }}
 */
export function mountLanding(container, { onPlan, onBrowse }) {
  injectStyles();
  container.innerHTML = landingTemplate(COPY);

  // A slot holds what the user typed and, once they pick a suggestion, the
  // resolved place. Typing again clears the resolution: the text and the
  // coordinates must never disagree.
  const slots = {
    origin: makeSlot('origin'),
    destination: makeSlot('destination'),
  };

  const form = container.querySelector('.rt-landing__form');
  const errorEl = container.querySelector('[data-error]');

  function makeSlot(name) {
    const field = container.querySelector(`[data-field="${name}"]`);
    return {
      name,
      field,
      input: field.querySelector('.rt-landing__input'),
      list: field.querySelector('[data-results]'),
      selected: null,
      results: [],
      activeIndex: -1,
      timer: null,
      controller: null,
    };
  }

  function showError(message) {
    errorEl.textContent = message;
    errorEl.hidden = false;
  }

  function clearError() {
    errorEl.hidden = true;
  }

  function closeList(slot) {
    slot.list.hidden = true;
    slot.list.innerHTML = '';
    slot.results = [];
    slot.activeIndex = -1;
    slot.input.setAttribute('aria-expanded', 'false');
  }

  function renderList(slot) {
    if (!slot.results.length) {
      closeList(slot);
      return;
    }
    slot.list.innerHTML = suggestionsTemplate(slot.results);
    slot.list.hidden = false;
    slot.input.setAttribute('aria-expanded', 'true');
    highlight(slot);
  }

  function highlight(slot) {
    slot.list.querySelectorAll('.rt-landing__result').forEach((el, i) => {
      const active = i === slot.activeIndex;
      el.classList.toggle('is-active', active);
      el.setAttribute('aria-selected', String(active));
    });
  }

  function choose(slot, index) {
    const picked = slot.results[index];
    if (!picked) return;
    slot.selected = picked;
    slot.input.value = picked.name;
    closeList(slot);
    clearError();
    if (slot.name === 'origin') slots.destination.input.focus();
  }

  async function suggest(slot) {
    const query = slot.input.value.trim();
    if (query.length < MIN_QUERY_LENGTH) {
      closeList(slot);
      return;
    }
    slot.controller?.abort();
    slot.controller = new AbortController();
    try {
      const body = await geocode(query, {
        autocomplete: true,
        limit: SUGGEST_LIMIT,
        signal: slot.controller.signal,
      });
      slot.results = (body.results || []).map(r => ({
        name: r.place_name,
        lng: r.lng,
        lat: r.lat,
        kind: r.place_type === 'address' ? 'ADDR' : 'PLACE',
      }));
      slot.activeIndex = -1;
      renderList(slot);
    } catch (err) {
      if (err.name === 'AbortError') return;
      console.warn('[landing] geocode failed', err);
      showError(ERRORS.lookup);
      closeList(slot);
    }
  }

  function onInput(e) {
    const slot = slotFor(e.target);
    if (!slot) return;
    // Typing invalidates a previous pick — otherwise a user could select
    // "Reno, NV", edit the text to "Portland", and submit Reno's coordinates.
    slot.selected = null;
    clearError();
    clearTimeout(slot.timer);
    slot.timer = setTimeout(() => suggest(slot), SUGGEST_DEBOUNCE_MS);
  }

  function onKeyDown(e) {
    const slot = slotFor(e.target);
    if (!slot || slot.list.hidden) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      const count = slot.results.length;
      slot.activeIndex = (slot.activeIndex + step + count) % count;
      highlight(slot);
      return;
    }
    if (e.key === 'Enter' && slot.activeIndex >= 0) {
      e.preventDefault();
      choose(slot, slot.activeIndex);
      return;
    }
    if (e.key === 'Escape') closeList(slot);
  }

  function onPointerDown(e) {
    const option = e.target.closest('.rt-landing__result');
    if (option) {
      // pointerdown, not click: the input's blur would tear the list down
      // before a click ever landed.
      e.preventDefault();
      const slot = slotFor(option.closest('.rt-landing__field').querySelector('.rt-landing__input'));
      choose(slot, Number(option.dataset.index));
      return;
    }
    if (e.target.closest('[data-action="browse"]')) {
      e.preventDefault();
      onBrowse();
      return;
    }
    if (!e.target.closest('.rt-landing__field')) {
      Object.values(slots).forEach(closeList);
    }
  }

  function slotFor(el) {
    return Object.values(slots).find(s => s.input === el) || null;
  }

  function onSubmit(e) {
    e.preventDefault();
    // Resolved coordinates are required, not just text: the route is built
    // from lng/lat, so an unresolved field has nothing to plan with.
    if (!slots.origin.selected) {
      showError(ERRORS.origin);
      slots.origin.input.focus();
      return;
    }
    if (!slots.destination.selected) {
      showError(ERRORS.destination);
      slots.destination.input.focus();
      return;
    }
    onPlan([slots.origin.selected, slots.destination.selected]);
  }

  container.addEventListener('input', onInput);
  container.addEventListener('keydown', onKeyDown);
  container.addEventListener('pointerdown', onPointerDown);
  form.addEventListener('submit', onSubmit);

  return {
    focusOrigin() {
      slots.origin.input.focus();
    },
    dispose() {
      container.removeEventListener('input', onInput);
      container.removeEventListener('keydown', onKeyDown);
      container.removeEventListener('pointerdown', onPointerDown);
      form.removeEventListener('submit', onSubmit);
      Object.values(slots).forEach(slot => {
        clearTimeout(slot.timer);
        slot.controller?.abort();
      });
      container.innerHTML = '';
    },
  };
}
