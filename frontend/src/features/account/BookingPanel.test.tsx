import { describe, expect, test, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  RecgovLoginResponse,
  RecgovStatus,
  RecgovVerifyResponse,
  SettingsResponse,
} from '@/api/account-api';
import {
  BookingPanel,
  bookingValuesOf,
  buildBookingPayload,
  isBookingDirty,
  type BookingValues,
} from './BookingPanel';

const settings = (over: Partial<SettingsResponse['booking']> = {}): SettingsResponse => ({
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
  },
  booking: {
    recgov_configured: false,
    recgov_username: null,
    ...over,
  },
});

const CONFIGURED = {
  recgov_configured: true,
  recgov_username: 'ada@example.test',
};

const activeStatus: RecgovStatus = {
  configured: true,
  username: 'ada@example.test',
 
  session: 'active',
};

interface Handlers {
  status?: RecgovStatus;
  statusPending?: boolean;
  onLogin?: () => Promise<RecgovLoginResponse>;
  onSubmitMfa?: (code: string) => Promise<RecgovLoginResponse>;
  onVerify?: () => Promise<RecgovVerifyResponse>;
  onRemoveRecgov?: () => void;
}

/** Renders with the parent's state wired up, and exposes the latest values. */
function renderPanel(s: SettingsResponse, handlers: Handlers = {}) {
  const state = { values: bookingValuesOf(s) };
  const onChange = vi.fn((next: BookingValues) => {
    state.values = next;
    rerender();
  });
  const ui = () => (
    <BookingPanel
      settings={s}
      values={state.values}
      onChange={onChange}
      status={handlers.status}
      statusPending={handlers.statusPending ?? false}
      onLogin={handlers.onLogin ?? (async () => ({ status: 'ok' }))}
      onSubmitMfa={handlers.onSubmitMfa ?? (async () => ({ status: 'ok' }))}
      onRemoveRecgov={handlers.onRemoveRecgov ?? vi.fn()}
      onVerify={handlers.onVerify ?? (async () => ({ ok: true }))}
    />
  );
  const { rerender: doRerender } = render(ui());
  function rerender() {
    doRerender(ui());
  }
  return { state, onChange };
}

const testLogin = () => screen.getByRole('button', { name: 'Test login' });
const verify = () => screen.getByRole('button', { name: 'Verify session' });

describe('the credential slice', () => {
  test('an untouched panel is not dirty and offers no password', () => {
    const s = settings(CONFIGURED);
    const values = bookingValuesOf(s);

    expect(values.recgov_username).toBe('ada@example.test');
    expect(values.recgov_password).toBeNull();
    expect(isBookingDirty(s, values)).toBe(false);
    expect(buildBookingPayload(values).recgov_password).toBeNull();
  });

  test('typing a username marks the form dirty', async () => {
    const s = settings(CONFIGURED);
    const { state } = renderPanel(s);

    await userEvent.type(screen.getByLabelText('Recreation.gov email'), '!');

    expect(state.values.recgov_username).toBe('ada@example.test!');
    expect(isBookingDirty(s, state.values)).toBe(true);
  });

  test('a stored password shows as placeholder dots, never as characters or real length', () => {
    // The Slack token shows its last 4 because it is machine-generated. A human
    // password's last 4 narrow a guess, and so does its length — so the dots
    // are a fixed-length placeholder and the input itself holds nothing.
    renderPanel(settings(CONFIGURED));

    const password = screen.getByLabelText('Recreation.gov password');
    expect(password).toHaveValue('');
    expect(password).toHaveAttribute('placeholder', '••••••••••');
    expect(password).toHaveAttribute('type', 'password');
  });

  test('an unconfigured account gets a plain empty password field', () => {
    renderPanel(settings());

    expect(screen.getByLabelText('Recreation.gov password')).not.toHaveAttribute('placeholder');
  });

  test('the three not-active session states read differently', () => {
    const row = (session: RecgovStatus['session']) =>
      renderPanel(settings(CONFIGURED), {
        status: { configured: true, username: 'ada@example.test', session },
      });

    row('not_logged_in');
    expect(screen.getByText('Not logged in yet — test login below')).toBeInTheDocument();
    cleanup();

    row('expired');
    expect(screen.getByText('Session expired — test login below')).toBeInTheDocument();
    cleanup();

    row('check_failed');
    expect(screen.getByText('Booking service error — status unknown')).toBeInTheDocument();
  });

  test('removing credentials is offered here, once something is stored', async () => {
    const onRemoveRecgov = vi.fn();
    renderPanel(settings(CONFIGURED), { onRemoveRecgov });

    await userEvent.click(screen.getByRole('button', { name: 'Remove rec.gov credentials' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm removal' }));

    expect(onRemoveRecgov).toHaveBeenCalledTimes(1);
  });

  test('nothing stored means nothing to remove', () => {
    renderPanel(settings());

    expect(screen.queryByRole('button', { name: 'Remove rec.gov credentials' })).not.toBeInTheDocument();
  });

  test('typing a password makes it the payload value', async () => {
    const s = settings();
    const { state } = renderPanel(s);

    await userEvent.type(screen.getByLabelText('Recreation.gov password'), 'hunter2');

    expect(buildBookingPayload(state.values).recgov_password).toBe('hunter2');
    expect(isBookingDirty(s, state.values)).toBe(true);
  });
});

describe('the session row', () => {
  test('unconfigured', () => {
    renderPanel(settings(), {
      status: { configured: false, username: null, session: 'not_configured' },
    });

    expect(screen.getByText('Not configured')).toBeInTheDocument();
  });

  test('configured and active', () => {
    renderPanel(settings(CONFIGURED), { status: activeStatus });

    expect(screen.getByText('Session active')).toBeInTheDocument();
  });

  test('expired', () => {
    renderPanel(settings(CONFIGURED), { status: { ...activeStatus, session: 'expired' } });

    expect(screen.getByText('Session expired — test login below')).toBeInTheDocument();
  });

  test('companion down — the row says so instead of failing', () => {
    renderPanel(settings(CONFIGURED), {
      status: { ...activeStatus, session: 'companion_unavailable' },
    });

    expect(screen.getByText('Booking service unavailable — status unknown')).toBeInTheDocument();
  });

  test('a status request still in flight does not hold up the panel', () => {
    renderPanel(settings(CONFIGURED), { statusPending: true });

    expect(screen.getByText('Checking session…')).toBeInTheDocument();
    expect(screen.getByLabelText('Recreation.gov email')).toBeInTheDocument();
  });
});

describe('test login', () => {
  test('is disabled with nothing stored', () => {
    renderPanel(settings());

    expect(testLogin()).toBeDisabled();
  });

  test('is disabled while the form is dirty, and says why', async () => {
    renderPanel(settings(CONFIGURED), { status: activeStatus });
    expect(testLogin()).toBeEnabled();

    await userEvent.type(screen.getByLabelText('Recreation.gov email'), '!');

    expect(testLogin()).toBeDisabled();
    expect(
      screen.getByText('Save your changes first — a test login uses the saved credentials.'),
    ).toBeInTheDocument();
  });

  test('a successful login reports into the status slot', async () => {
    const onLogin = vi.fn(async (): Promise<RecgovLoginResponse> => ({ status: 'ok' }));
    renderPanel(settings(CONFIGURED), { status: activeStatus, onLogin });

    await userEvent.click(testLogin());

    expect(onLogin).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Signed in to recreation.gov.')).toBeInTheDocument();
  });

  test('a captcha is explained rather than shown as a generic failure', async () => {
    renderPanel(settings(CONFIGURED), {
      status: activeStatus,
      onLogin: async () => ({ status: 'failed', error: 'captcha_required' }),
    });

    await userEvent.click(testLogin());

    expect(
      await screen.findByText(
        'Recreation.gov showed a challenge we cannot solve. Try again in a moment.',
      ),
    ).toBeInTheDocument();
  });

  test('a thrown request error is reported, not swallowed', async () => {
    renderPanel(settings(CONFIGURED), {
      status: activeStatus,
      onLogin: async () => {
        throw Object.assign(new Error('boom'), { code: 'companion_unavailable' });
      },
    });

    await userEvent.click(testLogin());

    expect(
      await screen.findByText("The booking service isn't reachable right now."),
    ).toBeInTheDocument();
  });
});

describe('the MFA step', () => {
  const mfaLogin = async (): Promise<RecgovLoginResponse> => ({
    status: 'mfa_required',
    challenge_id: 'chal-1',
  });

  test('appears only once a login asks for a code', async () => {
    renderPanel(settings(CONFIGURED), { status: activeStatus, onLogin: mfaLogin });
    expect(screen.queryByLabelText('Verification code')).not.toBeInTheDocument();

    await userEvent.click(testLogin());

    expect(await screen.findByLabelText('Verification code')).toBeInTheDocument();
    expect(
      screen.getByText('Recreation.gov sent a verification code. Enter it below.'),
    ).toBeInTheDocument();
  });

  test('the code is submitted and the flow completes', async () => {
    const onSubmitMfa = vi.fn(async (): Promise<RecgovLoginResponse> => ({ status: 'ok' }));
    renderPanel(settings(CONFIGURED), { status: activeStatus, onLogin: mfaLogin, onSubmitMfa });

    await userEvent.click(testLogin());
    await userEvent.type(await screen.findByLabelText('Verification code'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Submit code' }));

    expect(onSubmitMfa).toHaveBeenCalledWith('123456');
    expect(await screen.findByText('Signed in to recreation.gov.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Verification code')).not.toBeInTheDocument();
  });

  test('Test login is disabled while a challenge is open', async () => {
    // Pressing it again would hit the companion's profile lock, which the
    // pending challenge itself holds.
    renderPanel(settings(CONFIGURED), { status: activeStatus, onLogin: mfaLogin });
    await userEvent.click(testLogin());
    await screen.findByLabelText('Verification code');

    expect(testLogin()).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(testLogin()).toBeEnabled();
  });

  test('a remounted panel resumes a challenge the server is still holding', async () => {
    renderPanel(settings(CONFIGURED), {
      status: { ...activeStatus, session: 'expired', mfa_pending: true },
    });

    expect(await screen.findByLabelText('Verification code')).toBeInTheDocument();
    expect(testLogin()).toBeDisabled();
  });

  test('a resumed code is submitted like any other', async () => {
    const onSubmitMfa = vi.fn(async (): Promise<RecgovLoginResponse> => ({ status: 'ok' }));
    renderPanel(settings(CONFIGURED), {
      status: { ...activeStatus, session: 'expired', mfa_pending: true },
      onSubmitMfa,
    });

    await userEvent.type(await screen.findByLabelText('Verification code'), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Submit code' }));

    expect(onSubmitMfa).toHaveBeenCalledWith('654321');
    expect(await screen.findByText('Signed in to recreation.gov.')).toBeInTheDocument();
  });

  test('submit stays disabled until a code is typed', async () => {
    renderPanel(settings(CONFIGURED), { status: activeStatus, onLogin: mfaLogin });
    await userEvent.click(testLogin());
    await screen.findByLabelText('Verification code');

    expect(screen.getByRole('button', { name: 'Submit code' })).toBeDisabled();
  });

  test('cancelling closes the step and asks for nothing', async () => {
    const onSubmitMfa = vi.fn(async (): Promise<RecgovLoginResponse> => ({ status: 'ok' }));
    renderPanel(settings(CONFIGURED), { status: activeStatus, onLogin: mfaLogin, onSubmitMfa });
    await userEvent.click(testLogin());
    await screen.findByLabelText('Verification code');

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByLabelText('Verification code')).not.toBeInTheDocument();
    expect(onSubmitMfa).not.toHaveBeenCalled();
  });

  test('a rejected code closes the step with a reason', async () => {
    renderPanel(settings(CONFIGURED), {
      status: activeStatus,
      onLogin: mfaLogin,
      onSubmitMfa: async () => ({ status: 'failed', error: 'mfa_invalid' }),
    });

    await userEvent.click(testLogin());
    await userEvent.type(await screen.findByLabelText('Verification code'), '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Submit code' }));

    expect(
      await screen.findByText('That code was rejected. Start the login again for a new one.'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Verification code')).not.toBeInTheDocument();
  });
});

describe('verify session', () => {
  test('reports success in the same status slot', async () => {
    renderPanel(settings(CONFIGURED), { status: activeStatus });

    await userEvent.click(verify());

    expect(await screen.findByText('Session verified.')).toBeInTheDocument();
  });

  test('reports the mapped failure code', async () => {
    renderPanel(settings(CONFIGURED), {
      status: activeStatus,
      onVerify: async () => ({ ok: false, error: 'recgov_not_authenticated' }),
    });

    await userEvent.click(verify());

    expect(
      await screen.findByText('The recreation.gov session has expired. Test login again.'),
    ).toBeInTheDocument();
  });

  test('is disabled with nothing stored', () => {
    renderPanel(settings());

    expect(verify()).toBeDisabled();
  });
});
