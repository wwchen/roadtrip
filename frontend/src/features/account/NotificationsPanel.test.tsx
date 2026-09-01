import { describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SettingsResponse } from '@/api/account-api';
import {
  NotificationsPanel,
  buildNotificationsPayload,
  isNotificationsDirty,
  notificationValuesOf,
  type NotificationValues,
} from './NotificationsPanel';

/** Mirrors NotificationsPanel's SENT_CLEAR_MS. */
const SENT_CLEAR_MS = 4000;

const settings = (over: Partial<SettingsResponse['notifications']> = {}): SettingsResponse => ({
  profile: {
    display_name: 'Ada',
    login_email: 'ada@example.test',
    is_email_verified: true,
    roles: [],
    provider_label: 'Clerk',
    theme: 'system',
  },
  notifications: {
    notification_email: null,
    slack_channel: null,
    slack_configured: false,
    slack_token_hint: null,
    ...over,
  },
  booking: {
    recgov_configured: false,
    recgov_username: null,
    recgov_password_hint: null,
  },
});

/** Renders with the parent's state wired up, and exposes the latest values. */
function renderPanel(
  s: SettingsResponse,
  handlers: {
    onTestSlack?: (channel: string) => Promise<void>;
    onTestEmail?: () => Promise<void>;
  } = {},
) {
  const state = { values: notificationValuesOf(s) };
  const onChange = vi.fn((next: NotificationValues) => {
    state.values = next;
    rerender();
  });
  const ui = () => (
    <NotificationsPanel
      settings={s}
      values={state.values}
      onChange={onChange}
      onTestSlack={handlers.onTestSlack ?? (async () => {})}
      onTestEmail={handlers.onTestEmail ?? (async () => {})}
    />
  );
  const { rerender: doRerender } = render(ui());
  function rerender() {
    doRerender(ui());
  }
  return { state, onChange };
}

describe('SecretField — nothing stored', () => {
  test('an untouched token stays null, so it is omitted from the payload', () => {
    const { state } = renderPanel(settings());
    expect(state.values.slack_token).toBeNull();
    expect(buildNotificationsPayload(state.values).slack_token).toBeNull();
  });

  test('an untouched panel is not dirty', () => {
    const s = settings();
    expect(isNotificationsDirty(s, notificationValuesOf(s))).toBe(false);
  });

  test('typing a token makes it the payload value and marks the form dirty', async () => {
    const s = settings();
    const { state } = renderPanel(s);

    await userEvent.type(screen.getByLabelText('Slack bot token'), 'xoxb-secret');

    expect(state.values.slack_token).toBe('xoxb-secret');
    expect(isNotificationsDirty(s, state.values)).toBe(true);
  });

  test('clearing the input returns to null rather than an empty string', async () => {
    const { state } = renderPanel(settings());
    const input = screen.getByLabelText('Slack bot token');

    await userEvent.type(input, 'xoxb');
    await userEvent.clear(input);

    expect(state.values.slack_token).toBeNull();
  });

  test('there is no Replace or Cancel step when nothing is stored', () => {
    renderPanel(settings());
    expect(screen.queryByRole('button', { name: 'Replace' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });
});

describe('SecretField — a token already stored', () => {
  const stored = () => settings({ slack_configured: true, slack_token_hint: 'ab12' });

  test('shows the masked hint and a Replace button, not an input', () => {
    renderPanel(stored());
    expect(screen.getByText('••••ab12')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Slack bot token')).not.toBeInTheDocument();
  });

  test('the masked display reveals nothing but the hint', () => {
    const { container } = render(
      <NotificationsPanel
        settings={stored()}
        values={notificationValuesOf(stored())}
        onChange={vi.fn()}
        onTestSlack={async () => {}}
        onTestEmail={async () => {}}
      />,
    );
    expect(container.textContent).not.toMatch(/xoxb/);
  });

  test('pressing Replace reveals an input and stays unchanged until something is typed', async () => {
    const s = stored();
    const { state } = renderPanel(s);

    await userEvent.click(screen.getByRole('button', { name: 'Replace' }));

    expect(screen.getByLabelText('Slack bot token')).toBeInTheDocument();
    expect(state.values.slack_token).toBeNull();
    expect(isNotificationsDirty(s, state.values)).toBe(false);
  });

  test('cancelling a replacement restores the mask and drops the typed value', async () => {
    const s = stored();
    const { state } = renderPanel(s);

    await userEvent.click(screen.getByRole('button', { name: 'Replace' }));
    await userEvent.type(screen.getByLabelText('Slack bot token'), 'xoxb-new');
    expect(state.values.slack_token).toBe('xoxb-new');

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByText('••••ab12')).toBeInTheDocument();
    expect(state.values.slack_token).toBeNull();
    expect(isNotificationsDirty(s, state.values)).toBe(false);
  });
});

describe('dirty tracking', () => {
  test('a null saved email is equivalent to empty', () => {
    const s = settings();
    expect(notificationValuesOf(s).notification_email).toBe('');
    expect(isNotificationsDirty(s, notificationValuesOf(s))).toBe(false);
  });

  test('changing the email or the channel is dirty', () => {
    const s = settings({ notification_email: 'a@b.test', slack_channel: '#alerts' });
    const base = notificationValuesOf(s);
    expect(isNotificationsDirty(s, { ...base, notification_email: 'c@d.test' })).toBe(true);
    expect(isNotificationsDirty(s, { ...base, slack_channel: '#other' })).toBe(true);
    expect(isNotificationsDirty(s, base)).toBe(false);
  });
});

describe('test buttons', () => {
  test('the Slack test uses the channel currently in the form, not the saved one', async () => {
    const onTestSlack = vi.fn(async () => {});
    renderPanel(settings({ slack_channel: '#saved' }), { onTestSlack });

    const channel = screen.getByLabelText('Slack channel');
    await userEvent.clear(channel);
    await userEvent.type(channel, '#draft');
    await userEvent.click(screen.getByRole('button', { name: 'Send test message' }));

    expect(onTestSlack).toHaveBeenCalledWith('#draft');
  });

  test('a successful test confirms, then clears itself', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderPanel(settings());

      await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));
      expect(await screen.findByText('Sent')).toBeInTheDocument();

      await act(async () => {
        vi.advanceTimersByTime(SENT_CLEAR_MS);
      });
      expect(screen.queryByText('Sent')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  test('a failure maps the backend code to an owned message and persists', async () => {
    const onTestSlack = vi.fn(async () => {
      throw Object.assign(new Error('nope'), { code: 'slack_invalid_auth' });
    });
    renderPanel(settings(), { onTestSlack });

    await userEvent.click(screen.getByRole('button', { name: 'Send test message' }));

    expect(await screen.findByText('Slack rejected this token.')).toBeInTheDocument();
  });

  test('an unknown failure code still says something', async () => {
    const onTestEmail = vi.fn(async () => {
      throw Object.assign(new Error('nope'), { code: 'brand_new' });
    });
    renderPanel(settings(), { onTestEmail });

    await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));

    expect(
      await screen.findByText('Something went wrong. Please try again.'),
    ).toBeInTheDocument();
  });

  test('a new test clears the previous result', async () => {
    const onTestSlack = vi.fn(async () => {
      throw Object.assign(new Error('nope'), { code: 'slack_invalid_auth' });
    });
    renderPanel(settings(), { onTestSlack });

    await userEvent.click(screen.getByRole('button', { name: 'Send test message' }));
    await screen.findByText('Slack rejected this token.');

    await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));

    expect(screen.queryByText('Slack rejected this token.')).not.toBeInTheDocument();
    expect(await screen.findByText('Sent')).toBeInTheDocument();
  });

  test('both buttons are disabled while a test is in flight', async () => {
    let release: () => void = () => {};
    const onTestEmail = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    renderPanel(settings(), { onTestEmail });

    await userEvent.click(screen.getByRole('button', { name: 'Send test email' }));

    expect(screen.getByRole('button', { name: 'Send test email' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Send test message' })).toBeDisabled();

    await act(async () => {
      release();
    });
  });
});
