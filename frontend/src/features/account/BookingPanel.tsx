import { useEffect, useState } from 'react';
import { Button, ConfirmButton, SeededTextField } from '@ui';
import type {
  RecgovLoginResponse,
  RecgovStatus,
  RecgovVerifyResponse,
  SettingsResponse,
  UpdateBookingFields,
} from '@/api/account-api';
import { settingsErrorMessage } from '@/lib/settings-errors';
import { AccountStatusText, type StatusTone } from './AccountStatusText';
import {
  IDLE,
  isLoginBusy,
  nextLoginState,
  type BookingLoginState,
} from './booking-login';
import './account.css';

export interface BookingValues {
  recgov_username: string;
  /** A newly typed password, or null for "leave unchanged". */
  recgov_password: string | null;
}

export function bookingValuesOf(settings: SettingsResponse): BookingValues {
  return {
    recgov_username: settings.booking.recgov_username || '',
    recgov_password: null,
  };
}

/** True when the edited values differ from what is saved. */
export function isBookingDirty(settings: SettingsResponse, values: BookingValues): boolean {
  return (
    values.recgov_username !== (settings.booking.recgov_username || '') ||
    values.recgov_password !== null
  );
}

export function buildBookingPayload(values: BookingValues): UpdateBookingFields {
  return {
    recgov_username: values.recgov_username,
    recgov_password: values.recgov_password,
  };
}

/** Copy for the session row, by server-reported state. */
const SESSION_ROW: Record<RecgovStatus['session'], { tone: StatusTone; text: string }> = {
  not_configured: { tone: 'muted', text: 'Not configured' },
  active: { tone: 'ok', text: 'Session active' },
  // Three different not-active answers. Telling someone who has never logged in
  // that their session "expired" sends them hunting for a problem they do not
  // have; telling them so when the booking service itself threw sends them to
  // re-login against something broken.
  not_logged_in: { tone: 'muted', text: 'Not logged in yet — test login below' },
  expired: { tone: 'warn', text: 'Session expired — test login below' },
  check_failed: { tone: 'error', text: 'Booking service error — status unknown' },
  companion_unavailable: { tone: 'warn', text: 'Booking service unavailable — status unknown' },
};

const CHECKING_ROW = { tone: 'muted' as StatusTone, text: 'Checking session…' };
const UNKNOWN_ROW = SESSION_ROW.companion_unavailable;

const SAVE_FIRST_HELP = 'Save your changes first — a test login uses the saved credentials.';
const PASSWORD_HELP =
  'Used only to sign in to recreation.gov on your behalf. Add-to-cart stops at a cart hold; it never checks out.';

/**
 * What a saved password looks like in the untouched field.
 *
 * A **placeholder**, so no real character ever reaches the DOM, and a fixed
 * length, so neither does the real one. Leaving the field alone submits null
 * ("unchanged"); clearing credentials is the explicit button at the bottom.
 */
const SAVED_PASSWORD_PLACEHOLDER = '••••••••••';

export interface BookingPanelProps {
  settings: SettingsResponse;
  values: BookingValues;
  onChange: (values: BookingValues) => void;
  /** The live session row, from its own query. Undefined while it is in flight. */
  status: RecgovStatus | undefined;
  statusPending: boolean;
  onLogin: () => Promise<RecgovLoginResponse>;
  onSubmitMfa: (code: string) => Promise<RecgovLoginResponse>;
  onVerify: () => Promise<RecgovVerifyResponse>;
  /** Clears the stored credentials. Reports through the modal's notice banner. */
  onRemoveRecgov: () => void;
}

/**
 * Rec.gov credentials and the session they open.
 *
 * The credential half is a savable slice like Notifications: the modal owns the
 * values and its Save button writes them. The session half is actions —
 * everything below the password — and reports into one status slot with one
 * shared in-flight guard, so two results can never contradict each other.
 *
 * Test login is disabled while the form is dirty. It logs in with what the
 * *server* has, not with what is in the form, because a login is a side effect
 * on a real browser profile — unlike the Slack test, which can take a form
 * value because sending to a channel commits nothing.
 */
export function BookingPanel({
  settings,
  values,
  onChange,
  status,
  statusPending,
  onLogin,
  onSubmitMfa,
  onVerify,
  onRemoveRecgov,
}: BookingPanelProps) {
  const [login, setLogin] = useState<BookingLoginState>(IDLE);
  const [verifying, setVerifying] = useState(false);
  const [result, setResult] = useState<{ tone: StatusTone; message: string } | null>(null);

  const dirty = isBookingDirty(settings, values);
  const busy = isLoginBusy(login) || verifying;
  const configured = settings.booking.recgov_configured;
  const awaitingCode = login.kind === 'mfa_pending' || login.kind === 'submitting';

  // A challenge the server is still holding outlives this component: a reload or
  // a reopened modal has to find the code step, not a Test login button that
  // would collide with the profile lock that challenge holds.
  const serverHasChallenge = status?.mfa_pending === true;
  useEffect(() => {
    if (serverHasChallenge) setLogin((current) => nextLoginState(current, { type: 'resumed' }));
  }, [serverHasChallenge]);

  // An unrecognised state reads as "unknown" rather than blanking the row: a
  // server that grows a state must not leave the user with no session line.
  const sessionRow = statusPending
    ? CHECKING_ROW
    : (status && SESSION_ROW[status.session]) || UNKNOWN_ROW;

  /** Applies a login answer to the machine and to the one status slot. */
  const applyLogin = (answer: RecgovLoginResponse, from: BookingLoginState) => {
    if (answer.status === 'mfa_required' && answer.challenge_id) {
      setLogin(nextLoginState(from, { type: 'mfa_required', challengeId: answer.challenge_id }));
      setResult({ tone: 'warn', message: settingsErrorMessage('mfa_required') });
      return;
    }
    if (answer.status === 'ok') {
      setLogin(nextLoginState(from, { type: 'succeeded' }));
      setResult({ tone: 'ok', message: 'Signed in to recreation.gov.' });
      return;
    }
    setLogin(nextLoginState(from, { type: 'failed', code: answer.error ?? '' }));
    setResult({ tone: 'error', message: settingsErrorMessage(answer.error) });
  };

  const failFrom = (from: BookingLoginState, err: unknown) => {
    const code = (err as { code?: string } | null)?.code;
    setLogin(nextLoginState(from, { type: 'failed', code: code ?? '' }));
    setResult({ tone: 'error', message: settingsErrorMessage(code) });
  };

  const startLogin = async () => {
    if (busy || dirty || awaitingCode) return;
    const started = nextLoginState(login, { type: 'login_started' });
    setLogin(started);
    setResult(null);
    try {
      applyLogin(await onLogin(), started);
    } catch (err) {
      failFrom(started, err);
    }
  };

  const submitCode = async (code: string) => {
    if (busy) return;
    const submitting = nextLoginState(login, { type: 'code_submitted' });
    if (submitting === login) return;
    setLogin(submitting);
    setResult(null);
    try {
      applyLogin(await onSubmitMfa(code), submitting);
    } catch (err) {
      failFrom(submitting, err);
    }
  };

  const runVerify = async () => {
    if (busy) return;
    setVerifying(true);
    setResult(null);
    try {
      const answer = await onVerify();
      setResult(
        answer.ok
          ? { tone: 'ok', message: 'Session verified.' }
          : { tone: 'error', message: settingsErrorMessage(answer.error) },
      );
    } catch (err) {
      setResult({ tone: 'error', message: settingsErrorMessage((err as { code?: string } | null)?.code) });
    } finally {
      setVerifying(false);
    }
  };

  return (
    <div className="rt-account-panel">
      <div className="rt-account-field">
        <SeededTextField
          id="settings-recgov-username"
          name="recgov_username"
          label="Recreation.gov email"
          type="email"
          seed={values.recgov_username}
          onChange={(e) =>
            onChange({ ...values, recgov_username: (e.target as HTMLInputElement).value })
          }
        />
      </div>

      <div className="rt-account-field">
        {/* Two plain stacked fields rather than the SecretField mask-and-Replace
            row: this is a password a person types, not a token they paste once.
            Seeded empty and never with the stored value — the dots are a
            placeholder, so nothing real is ever in the DOM. An untouched field
            submits null, meaning unchanged. */}
        <SeededTextField
          id="settings-recgov-password"
          name="recgov_password"
          label="Recreation.gov password"
          type="password"
          autoComplete="off"
          placeholder={configured ? SAVED_PASSWORD_PLACEHOLDER : undefined}
          help={PASSWORD_HELP}
          seed=""
          onChange={(e) => {
            const typed = (e.target as HTMLInputElement).value;
            onChange({ ...values, recgov_password: typed === '' ? null : typed });
          }}
        />
      </div>

      <div className="rt-account-row">
        <span className="rt-account-row-label">Session</span>
        <AccountStatusText tone={sessionRow.tone}>{sessionRow.text}</AccountStatusText>
      </div>

      <div className="rt-notif-field">
        <Button
          size="sm"
          variant="secondary"
          // Not while a challenge is open: the companion answers profile_busy,
          // because that very challenge holds the profile's lock.
          disabled={busy || dirty || !configured || awaitingCode}
          onClick={() => void startLogin()}
        >
          Test login
        </Button>
        <Button size="sm" variant="secondary" disabled={busy || !configured} onClick={() => void runVerify()}>
          Verify session
        </Button>
        {result && <AccountStatusText tone={result.tone}>{result.message}</AccountStatusText>}
      </div>

      {dirty && <span className="rt-secret-field-help">{SAVE_FIRST_HELP}</span>}

      {awaitingCode && (
        <MfaStep
          busy={busy}
          onSubmit={(code) => void submitCode(code)}
          onCancel={() => {
            setLogin(nextLoginState(login, { type: 'cancelled' }));
            setResult(null);
          }}
        />
      )}

      {configured && (
        <section className="rt-account-danger">
          <h3 className="rt-account-danger-title">Danger zone</h3>
          {/* The destructive action for this page, on this page. It was in
              Account, which meant removing a credential you were looking at
              required leaving the tab that shows it. */}
          <ConfirmButton
            variant="tertiary"
            hue="red"
            label="Remove rec.gov credentials"
            confirmLabel="Confirm removal"
            onConfirm={onRemoveRecgov}
          />
        </section>
      )}
    </div>
  );
}

/**
 * The inline code step.
 *
 * Conditionally rendered, so the field is `SeededTextField` rather than a
 * `defaultValue` from the parent's snapshot: it seeds at its own mount, which
 * is the only way an appearing-and-disappearing field shows what it submits.
 */
function MfaStep({
  busy,
  onSubmit,
  onCancel,
}: {
  busy: boolean;
  onSubmit: (code: string) => void;
  onCancel: () => void;
}) {
  const [code, setCode] = useState('');
  return (
    <div className="rt-notif-field">
      <SeededTextField
        id="settings-recgov-mfa"
        name="recgov_mfa_code"
        label="Verification code"
        type="text"
        autoComplete="one-time-code"
        seed={code}
        onChange={(e) => setCode((e.target as HTMLInputElement).value)}
      />
      <Button size="sm" variant="primary" disabled={busy || code.trim() === ''} onClick={() => onSubmit(code.trim())}>
        Submit code
      </Button>
      <Button size="sm" variant="tertiary" disabled={busy} onClick={onCancel}>
        Cancel
      </Button>
    </div>
  );
}
