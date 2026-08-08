import { useEffect, useRef, useState } from 'react';
import { Button, SecretField, SeededTextField } from '@ui';
import type { SettingsResponse } from '@/api/account-api';
import { settingsErrorMessage } from '@/lib/settings-errors';
import './account.css';

/** How long a "✓ Sent" confirmation stays up. */
const SENT_CLEAR_MS = 4000;

export interface NotificationValues {
  notification_email: string;
  slack_channel: string;
  /** A newly typed token, or null for "leave unchanged". */
  slack_token: string | null;
}

export function notificationValuesOf(settings: SettingsResponse): NotificationValues {
  return {
    notification_email: settings.notifications.notification_email || '',
    slack_channel: settings.notifications.slack_channel || '',
    slack_token: null,
  };
}

/**
 * True when the edited values differ from what is saved.
 *
 * Port of `computeNotificationsDirty`, with the token folded in. The original had to
 * ask the SecretField for its mode; here a pending token IS a non-null
 * `slack_token`, so the whole thing is a value comparison.
 */
export function isNotificationsDirty(
  settings: SettingsResponse,
  values: NotificationValues,
): boolean {
  return (
    values.notification_email !== (settings.notifications.notification_email || '') ||
    values.slack_channel !== (settings.notifications.slack_channel || '') ||
    values.slack_token !== null
  );
}

/** Port of `buildNotificationsPayload`. */
export function buildNotificationsPayload(values: NotificationValues): NotificationValues {
  return {
    notification_email: values.notification_email,
    slack_channel: values.slack_channel,
    slack_token: values.slack_token,
  };
}

/** Which test button produced the current message. */
type TestTarget = 'slack' | 'email';

interface TestStatus {
  target: TestTarget;
  ok: boolean;
  message: string;
}

export interface NotificationsPanelProps {
  settings: SettingsResponse;
  values: NotificationValues;
  onChange: (values: NotificationValues) => void;
  /** Sends a test Slack message to the channel currently in the form. */
  onTestSlack: (channel: string) => Promise<void>;
  onTestEmail: () => Promise<void>;
}

/**
 * Rebuild of web/account/notifications-panel.js.
 *
 * Notification email, the write-only Slack token, the channel, and a test button for
 * each with inline feedback.
 *
 * The two tests share one in-flight guard, as they did originally — they post to the
 * same account and a second press while one is pending tells the user nothing. One
 * status slot for the same reason: the message names which test it came from, so two
 * stale results cannot sit side by side contradicting each other.
 */
export function NotificationsPanel({
  settings,
  values,
  onChange,
  onTestSlack,
  onTestEmail,
}: NotificationsPanelProps) {
  const [pending, setPending] = useState(false);
  const [status, setStatus] = useState<TestStatus | null>(null);
  const clearTimer = useRef<ReturnType<typeof setTimeout>>();

  // A success message is transient; a failure stays until the next attempt, because
  // it is the only place the reason is shown.
  useEffect(() => {
    if (!status?.ok) return;
    clearTimer.current = setTimeout(() => setStatus(null), SENT_CLEAR_MS);
    return () => clearTimeout(clearTimer.current);
  }, [status]);

  const runTest = async (target: TestTarget, send: () => Promise<void>) => {
    if (pending) return;
    setPending(true);
    setStatus(null);
    try {
      await send();
      setStatus({ target, ok: true, message: '✓ Sent' });
    } catch (err) {
      const code = (err as { code?: string } | null)?.code;
      setStatus({ target, ok: false, message: `✕ ${settingsErrorMessage(code)}` });
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="rt-account-panel">
      <div className="rt-notif-field">
        <SeededTextField
          id="settings-notification-email"
          name="notification_email"
          label="Notification email"
          type="email"
          // The login email as placeholder, so leaving it blank visibly means
          // "use the address I sign in with".
          placeholder={settings.profile.login_email}
          seed={values.notification_email}
          onChange={(e) =>
            onChange({
              ...values,
              notification_email: (e.target as HTMLInputElement).value,
            })
          }
        />
        <Button
          size="sm"
          variant="secondary"
          disabled={pending}
          onClick={() => void runTest('email', onTestEmail)}
        >
          Send test email
        </Button>
        <TestStatusText status={status} target="email" />
      </div>

      <SecretField
        id="settings-slack-token"
        name="slack_token"
        label="Slack bot token"
        hint={settings.notifications.slack_token_hint}
        help="Create a bot token in your Slack app settings."
        value={values.slack_token}
        onChange={(slack_token) => onChange({ ...values, slack_token })}
      />

      <div className="rt-notif-field">
        <SeededTextField
          id="settings-slack-channel"
          name="slack_channel"
          label="Slack channel"
          type="text"
          placeholder="#general"
          seed={values.slack_channel}
          onChange={(e) =>
            onChange({ ...values, slack_channel: (e.target as HTMLInputElement).value })
          }
        />
        <Button
          size="sm"
          variant="secondary"
          disabled={pending}
          // Tests the channel currently in the form, not the saved one, so it can be
          // checked before committing.
          onClick={() => void runTest('slack', () => onTestSlack(values.slack_channel))}
        >
          Send test message
        </Button>
        <TestStatusText status={status} target="slack" />
      </div>
    </div>
  );
}

function TestStatusText({ status, target }: { status: TestStatus | null; target: TestTarget }) {
  if (!status || status.target !== target) return null;
  return (
    <span
      className={`rt-notif-status ${status.ok ? 'rt-notif-status--ok' : 'rt-notif-status--err'}`}
      role="status"
    >
      {status.message}
    </span>
  );
}
