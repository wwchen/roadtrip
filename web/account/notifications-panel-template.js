// web/account/notifications-panel-template.js
// Pure template — no DOM access, no side effects.

import { escapeHtml } from '../core.js';

/**
 * @returns {string}
 */
export function notificationsPanelTemplate() {
  return `
    <div class="rt-notifications-panel">
      <div data-host="notification-email"></div>
      <div data-host="slack-token"></div>
      <div data-host="slack-channel"></div>
      <div class="rt-notifications-panel-test-row">
        <button
          type="button"
          class="rt-notifications-panel-test-btn"
          data-action="test-slack"
        >Send a test message</button>
      </div>
      <div data-host="banner" class="rt-notifications-panel-banner"></div>
    </div>
  `;
}
