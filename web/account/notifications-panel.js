// web/account/notifications-panel.js
//
// Mounts the Notifications settings panel: notification email, Slack token
// (SecretField), Slack channel, and test buttons with inline status feedback.
//
// DS mounts are injectable (via config._mount*) so tests can pass fakes
// without real DOM interaction.

import { mountFormSection as _defaultMountFormSection } from '../design-system/form-section.js';
import { mountSecretField as _defaultMountSecretField } from '../design-system/secret-field.js';
import { settingsErrorMessage } from './settings-errors.js';
import { notificationsPanelTemplate } from './notifications-panel-template.js';

const STYLE_ID = 'rt-notifications-panel-styles';
const BUTTONS_STYLE_ID = 'rt-buttons-styles';

// ── Pure helpers ─────────────────────────────────────────────────────────────

/**
 * Compute whether the current notification field values differ from settings.
 * slack_token is excluded (SecretField handles its own dirty state via mode).
 *
 * @param {{ notifications: { notification_email: string, slack_channel: string } }} settings
 * @param {{ notification_email: string, slack_channel: string }} values
 * @returns {boolean}
 */
export function computeNotificationsDirty(settings, values) {
  return (
    values.notification_email !== (settings.notifications.notification_email || '') ||
    values.slack_channel !== (settings.notifications.slack_channel || '')
  );
}

/**
 * Build the payload to send when saving notification settings.
 * Returns { notification_email, slack_channel, slack_token }. slack_token is the
 * SecretField value: a string when the user entered a new token, or null meaning
 * 'leave unchanged' (the api client omits a null slack_token before sending).
 *
 * @param {{ notification_email: string, slack_channel: string, slack_token: string|null }} values
 * @returns {{ notification_email: string, slack_channel: string, slack_token: string|null }}
 */
export function buildNotificationsPayload(values) {
  return {
    notification_email: values.notification_email,
    slack_channel: values.slack_channel,
    slack_token: values.slack_token,
  };
}

// ── Mount ────────────────────────────────────────────────────────────────────

/**
 * @param {Element} container
 * @param {{
 *   settings: object,
 *   onDirtyChange?: (dirty: boolean) => void,
 *   onTest?: (channel: string) => Promise<void>,
 *   onTestEmail?: () => Promise<void>,
 *   _mountFormSection?: Function,
 *   _mountSecretField?: Function,
 * }} config
 * @returns {{ getPayload(): object, isDirty(): boolean, dispose(): void }}
 */
export function mountNotificationsPanel(container, config) {
  const {
    settings,
    onDirtyChange,
    onTest,
    onTestEmail,
    _mountFormSection = _defaultMountFormSection,
    _mountSecretField = _defaultMountSecretField,
  } = config;

  injectStyles();

  const initialEmail = settings.notifications.notification_email || '';
  const initialChannel = settings.notifications.slack_channel || '';
  const loginEmail = settings.profile.login_email || '';
  let currentDirty = false;
  let testPending = false;

  container.innerHTML = notificationsPanelTemplate({});

  const emailHost = container.querySelector('[data-host="notification-email"]');
  const slackTokenHost = container.querySelector('[data-host="slack-token"]');
  const slackChannelHost = container.querySelector('[data-host="slack-channel"]');
  const emailStatusHost = container.querySelector('[data-host="email-status"]');
  const slackStatusHost = container.querySelector('[data-host="slack-status"]');

  const emailField = _mountFormSection(emailHost, {
    label: 'Notification email',
    name: 'notification_email',
    type: 'email',
    placeholder: loginEmail,
    value: initialEmail,
  });

  const slackTokenField = _mountSecretField(slackTokenHost, {
    label: 'Slack bot token',
    hint: settings.notifications.slack_token_hint || null,
    help: 'Create a bot token in your Slack app settings.',
  });

  const channelField = _mountFormSection(slackChannelHost, {
    label: 'Slack channel',
    name: 'slack_channel',
    type: 'text',
    placeholder: '#general',
    value: initialChannel,
  });

  function checkDirty() {
    const tokenDirty = slackTokenField.getMode() === 'replacing';
    const fieldDirty = computeNotificationsDirty(settings, {
      notification_email: emailField.getValue(),
      slack_channel: channelField.getValue(),
    });
    const dirty = fieldDirty || tokenDirty;
    if (dirty !== currentDirty) {
      currentDirty = dirty;
      onDirtyChange?.(dirty);
    }
  }

  function setStatus(host, text, okClass) {
    if (!host) return;
    host.textContent = text;
    host.className = 'rt-notif-status' + (okClass ? ' ' + okClass : '');
  }

  function clearStatus(host) {
    if (!host) return;
    host.textContent = '';
    host.className = 'rt-notif-status';
  }

  let successClearTimer = null;

  async function handleTest() {
    if (testPending) return;
    const channel = channelField.getValue();
    testPending = true;
    clearStatus(emailStatusHost);
    try {
      await onTest?.(channel);
      setStatus(slackStatusHost, '✓ Sent', 'rt-notif-status--ok');
      if (typeof setTimeout !== 'undefined') {
        clearTimeout(successClearTimer);
        successClearTimer = setTimeout(() => clearStatus(slackStatusHost), 4000);
      }
    } catch (err) {
      const msg = settingsErrorMessage(err && err.code);
      setStatus(slackStatusHost, '✕ ' + msg, 'rt-notif-status--err');
    } finally {
      testPending = false;
    }
  }

  async function handleTestEmail() {
    if (testPending) return;
    testPending = true;
    clearStatus(slackStatusHost);
    try {
      await onTestEmail?.();
      setStatus(emailStatusHost, '✓ Sent', 'rt-notif-status--ok');
      if (typeof setTimeout !== 'undefined') {
        clearTimeout(successClearTimer);
        successClearTimer = setTimeout(() => clearStatus(emailStatusHost), 4000);
      }
    } catch (err) {
      const msg = settingsErrorMessage(err && err.code);
      setStatus(emailStatusHost, '✕ ' + msg, 'rt-notif-status--err');
    } finally {
      testPending = false;
    }
  }

  function onInput() {
    checkDirty();
  }

  function onClick(e) {
    if (e.target && typeof e.target.closest === 'function') {
      if (e.target.closest('[data-action="test-slack"]')) {
        handleTest();
      } else if (e.target.closest('[data-action="test-email"]')) {
        handleTestEmail();
      }
    }
    // Also recheck dirty when secret-field toggle buttons are clicked.
    checkDirty();
  }

  container.addEventListener('input', onInput);
  container.addEventListener('click', onClick);

  return {
    getPayload() {
      return buildNotificationsPayload({
        notification_email: emailField.getValue(),
        slack_channel: channelField.getValue(),
        slack_token: slackTokenField.getValue(),
      });
    },
    isDirty() {
      return currentDirty;
    },
    dispose() {
      if (typeof clearTimeout !== 'undefined') clearTimeout(successClearTimer);
      container.removeEventListener('input', onInput);
      container.removeEventListener('click', onClick);
      emailField.dispose();
      slackTokenField.dispose();
      channelField.dispose();
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (!document.getElementById(BUTTONS_STYLE_ID)) {
    const btnLink = document.createElement('link');
    btnLink.id = BUTTONS_STYLE_ID;
    btnLink.rel = 'stylesheet';
    btnLink.href = '/web/design-system/buttons.css';
    document.head.appendChild(btnLink);
  }
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/account/notifications-panel.css';
  document.head.appendChild(link);
}
