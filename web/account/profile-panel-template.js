// web/account/profile-panel-template.js
// Pure template — no DOM access, no side effects.

import { escapeHtml } from '../core.js';

/**
 * @param {{
 *   loginEmail: string,
 *   isEmailVerified: boolean,
 * }} params
 * @returns {string}
 */
export function profilePanelTemplate({ loginEmail, isEmailVerified }) {
  const verifiedBadge = isEmailVerified
    ? `<span class="rt-profile-panel-verified" aria-label="Verified">&#10003; verified</span>`
    : '';

  return `
    <div class="rt-profile-panel">
      <div data-host="display-name"></div>
      <div class="rt-profile-panel-email-row">
        <span class="rt-profile-panel-email-label">Login email</span>
        <span class="rt-profile-panel-email-value">
          ${escapeHtml(loginEmail || '')}${verifiedBadge ? ' ' + verifiedBadge : ''}
        </span>
      </div>
    </div>
  `;
}
