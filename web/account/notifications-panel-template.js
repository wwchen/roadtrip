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
      <div class="rt-notif-test-row">
        <button
          type="button"
          class="rt-btn rt-btn--secondary"
          data-action="test-email"
        >Send a test email</button>
        <span data-host="email-status" class="rt-notif-status"></span>
      </div>
      <div data-host="slack-token"></div>
      <div data-host="slack-channel"></div>
      <div class="rt-notif-test-row">
        <button
          type="button"
          class="rt-btn rt-btn--secondary"
          data-action="test-slack"
        >Send a test message</button>
        <span data-host="slack-status" class="rt-notif-status"></span>
      </div>
    </div>
  `;
}
