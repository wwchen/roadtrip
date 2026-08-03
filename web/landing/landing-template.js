import { escapeHtml } from '../core.js';

// What the map carries, stated as a list rather than as counts. Counts would
// be the stronger line, but only once they are read from what the data
// actually holds — a hardcoded number on the first screen is a claim nobody
// has checked.
const COVERAGE = ['Superchargers', 'Campgrounds', 'Showers', 'Cell coverage'];

export function landingTemplate({ title, subtitle, originLabel, destinationLabel, submitLabel, browseLabel }) {
  return `
    <div class="rt-landing__panel">
      <span class="rt-landing__brand">Roadtrip</span>

      <div class="rt-landing__pitch">
        <h1 class="rt-landing__title">${escapeHtml(title)}</h1>
        <p class="rt-landing__sub">${escapeHtml(subtitle)}</p>
      </div>

      <form class="rt-landing__form" novalidate>
        ${fieldTemplate({ slot: 'origin', label: originLabel, placeholder: 'City, park, or address' })}
        ${fieldTemplate({ slot: 'destination', label: destinationLabel, placeholder: 'Where to?' })}
        <button type="submit" class="rt-btn rt-btn--primary rt-landing__submit">
          ${escapeHtml(submitLabel)}
        </button>
        <p class="rt-landing__error" data-error hidden></p>
      </form>

      <button type="button" class="rt-btn rt-btn--tertiary rt-landing__browse" data-action="browse">
        ${escapeHtml(browseLabel)}
      </button>

      <ul class="rt-landing__coverage">
        ${COVERAGE.map(item => `<li>${escapeHtml(item)}</li>`).join('')}
      </ul>
    </div>
  `;
}

function fieldTemplate({ slot, label, placeholder }) {
  const inputId = `rt-landing-${slot}`;
  return `
    <div class="rt-landing__field" data-field="${escapeHtml(slot)}">
      <label class="rt-landing__label" for="${escapeHtml(inputId)}">${escapeHtml(label)}</label>
      <input
        class="rt-landing__input"
        id="${escapeHtml(inputId)}"
        type="text"
        autocomplete="off"
        spellcheck="false"
        placeholder="${escapeHtml(placeholder)}"
        role="combobox"
        aria-expanded="false"
        aria-autocomplete="list"
        aria-controls="${escapeHtml(inputId)}-results">
      <ul class="rt-landing__results" id="${escapeHtml(inputId)}-results" role="listbox" data-results hidden></ul>
    </div>
  `;
}

export function suggestionsTemplate(items) {
  return items.map((item, i) => `
    <li class="rt-landing__result" role="option" aria-selected="false" data-index="${i}">
      <span class="rt-landing__result-name">${escapeHtml(item.name)}</span>
    </li>
  `).join('');
}
