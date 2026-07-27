// web/account/account-panel-template.js
// Pure template — no DOM access, no side effects.

import { escapeHtml } from '../core.js';

/**
 * @param {{ loginEmail: string, slackConfigured: boolean }} params
 * @returns {string}
 */
export function accountPanelTemplate({ loginEmail, slackConfigured }) {
  const dangerZone = slackConfigured
    ? `
      <div class="rt-account-panel-danger-zone">
        <h3 class="rt-account-panel-danger-title">Danger zone</h3>
        <div data-host="disconnect-slack"></div>
      </div>
    `
    : '';

  return `
    <div class="rt-account-panel">
      <div class="rt-account-panel-identity">
        <span class="rt-account-panel-signed-in-label">Signed in as</span>
        <span class="rt-account-panel-email">${escapeHtml(loginEmail)}</span>
      </div>
      <div data-host="sign-out"></div>
      ${dangerZone}
    </div>
  `;
}
