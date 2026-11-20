import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type {
  RecgovLoginResponse,
  RecgovStatus,
  RecgovVerifyResponse,
  SettingsResponse,
} from '@/api/account-api';
import { BookingPanel, bookingValuesOf, type BookingValues } from './BookingPanel';

/** The class the production shells put on `<html>`. */
const ZION_THEME_CLASS = 'theme-roadtrip-zion';

const settingsWith = (booking: Partial<SettingsResponse['booking']>): SettingsResponse => ({
  profile: {
    display_name: 'Ada Lovelace',
    login_email: 'ada@example.test',
    is_email_verified: true,
    roles: [],
    provider_label: 'Clerk',
    theme: 'system',
  },
  notifications: {
    notification_email: 'ada@example.test',
    slack_channel: '#trip-alerts',
    slack_configured: true,
    slack_token_hint: '4f2a',
  },
  booking: {
    recgov_configured: false,
    recgov_username: undefined,
    ...booking,
  },
});

const CONFIGURED = settingsWith({
  recgov_configured: true,
  recgov_username: 'ada@example.test',
});

const activeStatus: RecgovStatus = {
  configured: true,
  username: 'ada@example.test',
  session: 'active',
  mfa_pending: false,
};

interface DemoProps {
  settings?: SettingsResponse;
  status?: RecgovStatus;
  statusPending?: boolean;
  onLogin?: () => Promise<RecgovLoginResponse>;
  onVerify?: () => Promise<RecgovVerifyResponse>;
}

/**
 * Holds the values the modal normally owns, so the fields type and the dirty
 * gating on Test login is real rather than described.
 */
function Demo({
  settings = CONFIGURED,
  status = activeStatus,
  statusPending = false,
  onLogin = async () => ({ status: 'ok' }),
  onVerify = async () => ({ ok: true }),
}: DemoProps) {
  const [values, setValues] = useState<BookingValues>(() => bookingValuesOf(settings));
  document.documentElement.classList.add(ZION_THEME_CLASS);
  return (
    <BookingPanel
      settings={settings}
      values={values}
      onChange={setValues}
      status={status}
      statusPending={statusPending}
      onRemoveRecgov={() => {}}
      onLogin={onLogin}
      onSubmitMfa={async () => ({ status: 'ok' })}
      onVerify={onVerify}
    />
  );
}

const meta = {
  title: 'Account/BookingPanel',
  parameters: {
    docs: {
      description: {
        component:
          'The Booking section of account settings: Recreation.gov credentials ' +
          'plus the session they open. The credential half is a savable slice ' +
          'the modal Save writes; the session half is actions reporting into ' +
          'one shared status slot. Test login uses the SAVED credentials, so it ' +
          'is disabled while the form is dirty.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing stored: an empty form, and both session actions disabled. */
export const Unconfigured: Story = {
  render: () => (
    <Demo
      settings={settingsWith({})}
      status={{ configured: false, username: undefined, session: 'not_configured', mfa_pending: false }}
    />
  ),
};

/** The steady state: credentials saved and the browser profile signed in. */
export const ConfiguredAndActive: Story = { render: () => <Demo /> };

/** Signed out at Recreation.gov's end — the row points at Test login. */
export const SessionExpired: Story = {
  render: () => <Demo status={{ ...activeStatus, session: 'expired' }} />,
};

/** A login that hit an MFA prompt: the code step appears inline below the
 *  buttons. Click Test login to reach it. */
export const MfaStep: Story = {
  render: () => (
    <Demo onLogin={async () => ({ status: 'mfa_required', challenge_id: 'chal-1' })} />
  ),
};

/** The blocker no remote user can clear. Click Test login to see the message. */
export const CaptchaRequired: Story = {
  render: () => <Demo onLogin={async () => ({ status: 'failed', error: 'captcha_required' })} />,
};

/** The companion is unreachable: the row says so and nothing errors. */
export const CompanionDown: Story = {
  render: () => (
    <Demo
      status={{ ...activeStatus, session: 'companion_unavailable', detail: 'connection refused' }}
      onLogin={async () => ({ status: 'failed', error: 'companion_unavailable' })}
      onVerify={async () => ({ ok: false, error: 'companion_unavailable' })}
    />
  ),
};

const ERROR_STORY = (story: string) => ({ docs: { description: { story } } });

const UNREPORTED_SESSION_STATE = 'paused_by_vendor' as RecgovStatus['session'];

export const LoginNeverSignedIn: Story = {
  parameters: ERROR_STORY(
    'Click Test login. A code with no wording of its own renders verbatim with its detail, ' +
      'so a timeout never reads as a rejected password.',
  ),
  render: () => (
    <Demo
      onLogin={async () => ({
        status: 'failed',
        error: 'recaccount_not_observed',
        detail: 'Recreation.gov did not show a signed-in account before the wait ran out',
      })}
    />
  ),
};

export const LoginCredentialsRejected: Story = {
  parameters: ERROR_STORY(
    "Click Test login. A known code gets its sentence, and the companion's raw code and " +
      "Recreation.gov's own text follow it in brackets.",
  ),
  render: () => (
    <Demo
      onLogin={async () => ({
        status: 'failed',
        error: 'login_failed',
        detail: 'login_error: The email or password you entered is incorrect.',
      })}
    />
  ),
};

export const VerifyCartRefused: Story = {
  parameters: ERROR_STORY(
    "Click Verify session. A cart check the vendor refused names its reason and HTTP status instead of 'someone took it'.",
  ),
  render: () => (
    <Demo
      onVerify={async () => ({
        ok: false,
        error: 'cart_http_error',
        detail: 'cart verification failed: reason=cart_http_error status=401 reservations=0',
      })}
    />
  ),
};

export const UnreportedSessionState: Story = {
  parameters: ERROR_STORY(
    'A session state this build has no row for shows its raw value and detail, not "unavailable".',
  ),
  render: () => (
    <Demo status={{ ...activeStatus, session: UNREPORTED_SESSION_STATE, detail: 'store_corrupt: profile store failed its checksum' }} />
  ),
};
