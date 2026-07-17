import { escapeHtml } from '../core.js';

export function toggleSwitchTemplate({ name, label, help, checked, disabled }) {
  const helpHtml = help
    ? `<span class="rt-toggle-help">${escapeHtml(help)}</span>`
    : '';
  return `
    <label class="rt-toggle">
      <span class="rt-toggle-text">
        <span class="rt-toggle-label">${escapeHtml(label)}</span>
        ${helpHtml}
      </span>
      <span class="rt-toggle-switch">
        <input type="checkbox" name="${escapeHtml(name)}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}>
        <span class="rt-toggle-track" aria-hidden="true"></span>
      </span>
    </label>
  `;
}
