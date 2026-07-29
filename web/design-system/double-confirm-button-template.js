import { escapeHtml } from '../core.js';

// `size` defaults to comfortable (>=44px). Pass 'compact' only where the button
// sits in a dense, fixed-width row — see web/watches/watch-table.js.
export function doubleConfirmButtonTemplate({ label, armed, confirmLabel, size }) {
  const text = armed ? (confirmLabel || 'Confirm?') : label;
  const cls = ['rt-dbl-btn'];
  if (size === 'compact') cls.push('rt-dbl-btn--compact');
  if (armed) cls.push('is-armed');
  return `<button type="button" class="${cls.join(' ')}">${escapeHtml(text)}</button>`;
}
