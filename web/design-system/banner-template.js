import { escapeHtml } from '../core.js';

export function bannerTemplate({ type, message, dismissable }) {
  const dismissHtml = dismissable !== false
    ? '<button type="button" class="rt-banner-dismiss" aria-label="Dismiss">&times;</button>'
    : '';
  return `
    <div class="rt-banner rt-banner-${escapeHtml(type)}" role="alert">
      <span class="rt-banner-message">${escapeHtml(message)}</span>
      ${dismissHtml}
    </div>
  `;
}
