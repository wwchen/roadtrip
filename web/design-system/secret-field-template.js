import { escapeHtml } from '../core.js';

/**
 * Pure reducer state shape:
 *   { mode: 'stored' | 'replacing', hint: string|null, value: string }
 *
 * In `stored` mode:
 *   - hint is a non-null, non-empty string (last-4 of the secret)
 *   - value is unused; valueOf() returns null ("leave unchanged")
 *
 * In `replacing` mode:
 *   - hint may be null (no prior secret) or a string (replacing an existing one)
 *   - value holds what the user has typed; valueOf() returns it
 */

/**
 * Create the initial state from a hint (last-4 chars of the stored secret).
 * If hint is null/undefined/empty, start in replacing mode (no existing secret).
 *
 * @param {string|null|undefined} hint
 * @returns {{ mode: string, hint: string|null, value: string }}
 */
export function initialState(hint) {
  const hasHint = hint != null && hint !== '';
  return {
    mode: hasHint ? 'stored' : 'replacing',
    hint: hasHint ? hint : null,
    value: '',
  };
}

/**
 * Transition from stored → replacing (user clicked Replace).
 *
 * @param {{ mode: string, hint: string|null, value: string }} state
 * @returns {{ mode: string, hint: string|null, value: string }}
 */
export function toReplacing(state) {
  return { ...state, mode: 'replacing', value: '' };
}

/**
 * Transition from replacing → stored (user clicked Cancel).
 * Restores the given hint.
 *
 * @param {{ mode: string, hint: string|null, value: string }} state
 * @param {string|null} hint - The original hint to restore.
 * @returns {{ mode: string, hint: string|null, value: string }}
 */
export function toCancelled(state, hint) {
  return { ...state, mode: 'stored', hint, value: '' };
}

/**
 * Update the current input value in replacing mode.
 *
 * @param {{ mode: string, hint: string|null, value: string }} state
 * @param {string} value
 * @returns {{ mode: string, hint: string|null, value: string }}
 */
export function withInput(state, value) {
  return { ...state, value };
}

/**
 * Return the effective value of the field:
 * - null in `stored` mode (meaning "leave unchanged" — maps to backend null contract)
 * - the entered string in `replacing` mode (may be empty if user hasn't typed yet)
 *
 * @param {{ mode: string, hint: string|null, value: string }} state
 * @returns {string|null}
 */
export function valueOf(state) {
  return state.mode === 'stored' ? null : state.value;
}

/**
 * Pure function — no DOM access. Returns an HTML string for the SecretField widget.
 *
 * In `stored` mode: renders "••••<hint>" + a Replace button.
 * In `replacing` mode: renders an empty password input + an optional Cancel button.
 *
 * @param {{ mode: string, hint: string|null, value: string }} state
 * @param {{ label: string, help?: string|null }} config
 * @returns {string}
 */
export function secretFieldTemplate(state, { label, help } = {}) {
  const labelHtml = `<span class="rt-secret-field-label">${escapeHtml(label || '')}</span>`;

  const helpHtml = help
    ? `<span class="rt-secret-field-help">${escapeHtml(help)}</span>`
    : '';

  let bodyHtml;

  if (state.mode === 'stored') {
    const maskedValue = `••••${escapeHtml(state.hint || '')}`;
    bodyHtml = `
      <div class="rt-secret-field-stored">
        <span class="rt-secret-field-masked" aria-label="Secret ending in ${escapeHtml(state.hint || '')}">${maskedValue}</span>
        <button
          type="button"
          class="rt-secret-field-replace-btn"
          data-action="replace"
          aria-label="Replace secret"
        >Replace</button>
      </div>`;
  } else {
    // replacing mode
    const cancelHtml = state.hint != null
      ? `<button
          type="button"
          class="rt-secret-field-cancel-btn"
          data-action="cancel"
          aria-label="Cancel replacing secret"
        >Cancel</button>`
      : '';
    bodyHtml = `
      <div class="rt-secret-field-replacing">
        <input
          class="rt-secret-field-input"
          type="password"
          autocomplete="new-password"
          autocorrect="off"
          spellcheck="false"
          placeholder="Enter new value"
          aria-label="${escapeHtml(label || '')}"
        >
        ${cancelHtml}
      </div>`;
  }

  return `
    <div class="rt-secret-field">
      ${labelHtml}
      ${bodyHtml}
      ${helpHtml}
    </div>
  `;
}
