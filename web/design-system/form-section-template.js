import { escapeHtml } from '../core.js';

export function formSectionTemplate({ label, name, type, placeholder, value, help, required, disabled }) {
  const helpHtml = help
    ? `<span class="rt-form-section-help">${escapeHtml(help)}</span>`
    : '';
  const reqAttr = required ? ' required' : '';
  const disAttr = disabled ? ' disabled' : '';
  return `
    <div class="rt-form-section">
      <span class="rt-form-section-label">${escapeHtml(label)}</span>
      <input
        class="rt-form-section-input"
        type="${escapeHtml(type || 'text')}"
        name="${escapeHtml(name)}"
        value="${escapeHtml(value || '')}"
        placeholder="${escapeHtml(placeholder || '')}"
        ${reqAttr}${disAttr}
      >
      ${helpHtml}
    </div>
  `;
}
