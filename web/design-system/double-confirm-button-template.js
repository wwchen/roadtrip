import { escapeHtml } from '../core.js';

export function doubleConfirmButtonTemplate({ label, armed, confirmLabel }) {
  const text = armed ? (confirmLabel || 'Confirm?') : label;
  const cls = armed ? 'rt-dbl-btn is-armed' : 'rt-dbl-btn';
  return `<button type="button" class="${cls}">${escapeHtml(text)}</button>`;
}
