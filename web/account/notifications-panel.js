// web/account/notifications-panel.js
//
// Mounts the Notifications settings panel: notification email, Slack token
// (SecretField), Slack channel, and a "Send a test message" button with Banner.
//
// DS mounts are injectable (via config._mount*) so tests can pass fakes
// without real DOM interaction.

import { mountFormSection as _defaultMountFormSection } from '../design-system/form-section.js';
import { mountSecretField as _defaultMountSecretField } from '../design-system/secret-field.js';
import { mountBanner as _defaultMountBanner } from '../design-system/banner.js';
import { settingsErrorMessage } from './settings-errors.js';
import { notificationsPanelTemplate } from './notifications-panel-template.js';

const STYLE_ID = 'rt-notifications-panel-styles';

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
 *   _mountFormSection?: Function,
 *   _mountSecretField?: Function,
 *   _mountBanner?: Function,
 * }} config
 * @returns {{ getPayload(): object, isDirty(): boolean, dispose(): void }}
 */
export function mountNotificationsPanel(container, config) {
  const {
    settings,
    onDirtyChange,
    onTest,
    _mountFormSection = _defaultMountFormSection,
    _mountSecretField = _defaultMountSecretField,
    _mountBanner = _defaultMountBanner,
  } = config;

  injectStyles();

  const initialEmail = settings.notifications.notification_email || '';
  const initialChannel = settings.notifications.slack_channel || '';
  const loginEmail = settings.profile.login_email || '';
  let currentDirty = false;
  let bannerCtrl = null;
  let testPending = false;

  container.innerHTML = notificationsPanelTemplate({});

  const emailHost = container.querySelector('[data-host="notification-email"]');
  const slackTokenHost = container.querySelector('[data-host="slack-token"]');
  const slackChannelHost = container.querySelector('[data-host="slack-channel"]');
  const bannerHost = container.querySelector('[data-host="banner"]');
  const testBtn = container.querySelector('[data-action="test-slack"]');

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

  function showBanner(type, message) {
    if (bannerCtrl) {
      bannerCtrl.update({ type, message });
    } else {
      bannerCtrl = _mountBanner(bannerHost, { type, message, dismissable: true });
    }
  }

  async function handleTest() {
    if (testPending) return;
    const channel = channelField.getValue();
    testPending = true;
    try {
      await onTest?.(channel);
      showBanner('success', 'Test message sent successfully.');
    } catch (err) {
      const msg = settingsErrorMessage(err && err.code);
      showBanner('error', msg);
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
      container.removeEventListener('input', onInput);
      container.removeEventListener('click', onClick);
      emailField.dispose();
      slackTokenField.dispose();
      channelField.dispose();
      bannerCtrl?.dispose();
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
  link.href = '/web/account/notifications-panel.css';
  document.head.appendChild(link);
}
