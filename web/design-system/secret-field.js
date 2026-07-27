import {
  secretFieldTemplate,
  initialState,
  toReplacing,
  toCancelled,
  withInput,
  valueOf,
} from './secret-field-template.js';

const STYLE_ID = 'rt-secret-field-styles';

/**
 * Mount a write-only secret input into `container`.
 *
 * The component operates in two modes:
 * - `stored`    — a hint (last-4 chars) exists; shows "••••<hint>" + a Replace button.
 * - `replacing` — shows an empty password input; Cancel button if there was a prior secret.
 *
 * `getValue()` returns null in `stored` mode (meaning "leave unchanged" — maps directly
 * to the backend's "null = leave unchanged" contract), or the entered string in
 * `replacing` mode.
 *
 * @param {Element} container - The host element that will receive the widget markup.
 * @param {{
 *   label: string,
 *   hint?: string|null,
 *   help?: string|null,
 * }} config
 * @returns {{
 *   getValue(): string|null,
 *   getMode(): 'stored' | 'replacing',
 *   reset(): void,
 *   dispose(): void,
 * }}
 */
export function mountSecretField(container, config = {}) {
  const { label = '', hint = null, help = null } = config;

  // The original hint is kept immutable so reset() can restore it.
  const originalHint = hint;

  let state = initialState(originalHint);

  injectStyles();

  function render() {
    container.innerHTML = secretFieldTemplate(state, { label, help });
  }

  function onClick(e) {
    const target = e.target && typeof e.target.closest === 'function'
      ? e.target.closest('[data-action]')
      : (e.target && e.target.getAttribute && e.target.getAttribute('data-action')
        ? e.target
        : null);
    if (!target) return;
    const action = target.dataset
      ? target.dataset.action
      : target.getAttribute('data-action');
    if (action === 'replace') {
      state = toReplacing(state);
      render();
    } else if (action === 'cancel') {
      state = toCancelled(state, originalHint);
      render();
    }
  }

  function onInput(e) {
    // Only track input events from the password input within this container.
    if (!e.target) return;
    const tagName = e.target.tagName ? e.target.tagName.toLowerCase() : '';
    if (tagName !== 'input') return;
    state = withInput(state, e.target.value || '');
  }

  render();
  container.addEventListener('click', onClick);
  container.addEventListener('input', onInput);

  return {
    /**
     * Returns null in stored mode ("leave unchanged"), or the entered string
     * in replacing mode.
     * @returns {string|null}
     */
    getValue() {
      return valueOf(state);
    },

    /**
     * Returns the current mode: 'stored' or 'replacing'.
     * @returns {'stored' | 'replacing'}
     */
    getMode() {
      return state.mode;
    },

    /**
     * Reset the widget to its initial state (as if freshly mounted).
     */
    reset() {
      state = initialState(originalHint);
      render();
    },

    /**
     * Remove event listeners and clear the DOM.
     */
    dispose() {
      container.removeEventListener('click', onClick);
      container.removeEventListener('input', onInput);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/secret-field.css';
  document.head.appendChild(link);
}
