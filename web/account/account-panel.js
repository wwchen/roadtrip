// web/account/account-panel.js
//
// Mounts the Account settings panel: signed-in email, Sign out button,
// and a danger-zone Disconnect Slack button.
//
// DS mounts are injectable (via config._mountDoubleConfirmButton) so tests
// can pass fakes without real DOM interaction.

import { mountDoubleConfirmButton as _defaultMountDoubleConfirmButton } from '../design-system/double-confirm-button.js';
import { accountPanelTemplate } from './account-panel-template.js';

const STYLE_ID = 'rt-account-panel-styles';

// ── Mount ────────────────────────────────────────────────────────────────────

/**
 * @param {Element} container
 * @param {{
 *   settings: object,
 *   onDirtyChange?: (dirty: boolean) => void,
 *   onSignOut?: () => void,
 *   onDisconnectSlack?: () => void,
 *   _mountDoubleConfirmButton?: Function,
 * }} config
 * @returns {{ getPayload(): {}, isDirty(): boolean, dispose(): void }}
 */
export function mountAccountPanel(container, config) {
  const {
    settings,
    onSignOut,
    onDisconnectSlack,
    _mountDoubleConfirmButton = _defaultMountDoubleConfirmButton,
  } = config;

  injectStyles();

  const loginEmail = settings.profile.login_email || '';
  const slackConfigured = settings.notifications && settings.notifications.slack_configured;

  container.innerHTML = accountPanelTemplate({ loginEmail, slackConfigured });

  const signOutHost = container.querySelector('[data-host="sign-out"]');
  const disconnectSlackHost = container.querySelector('[data-host="disconnect-slack"]');

  const signOutBtn = _mountDoubleConfirmButton(signOutHost, {
    label: 'Sign out',
    confirmLabel: 'Confirm sign out',
    onConfirm: onSignOut,
  });

  let disconnectSlackBtn = null;
  if (slackConfigured && disconnectSlackHost) {
    disconnectSlackBtn = _mountDoubleConfirmButton(disconnectSlackHost, {
      label: 'Disconnect Slack',
      confirmLabel: 'Confirm disconnect',
      onConfirm: onDisconnectSlack,
    });
  }

  return {
    // AccountPanel has no editable fields — payload is always empty.
    getPayload() {
      return {};
    },
    // Account panel is never "dirty" — it only fires actions.
    isDirty() {
      return false;
    },
    dispose() {
      signOutBtn.dispose();
      disconnectSlackBtn?.dispose();
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/account/account-panel.css';
  document.head.appendChild(link);
}
