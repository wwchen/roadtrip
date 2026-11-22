import { readFileSync } from 'node:fs';
import path from 'node:path';
import type { ActionResult, HoldResult, LoginResult, Refusal, SessionHealth, SignOutResult } from '../service/booking/model';

const FIXTURE_FILE = path.resolve(
  __dirname,
  '..', '..', '..', '..', '..', 'backend', 'src', 'test', 'resources', 'booking-relay-answers.json',
);
const DETAIL = 'what the companion said';
const TIMEOUT_DETAIL = 'the hold may still finish; check the cart';

export type RelayCase = { operation: string; status: number; body: unknown };

export const RELAY_CASES = JSON.parse(readFileSync(FIXTURE_FILE, 'utf8')) as Record<string, RelayCase>;

const timeout: Refusal = { category: 'retry_later', code: 'relay_timeout', detail: TIMEOUT_DETAIL };

export const RELAY_DECODED = {
  hold_held: { kind: 'held', cartUrl: 'https://www.recreation.gov/cart/held' },
  hold_blocked: { kind: 'blocked', refusal: { category: 'retry_later', code: 'profile_busy', detail: DETAIL } },
  hold_timed_out: { kind: 'timed_out', refusal: timeout, cartUrl: 'https://www.recreation.gov/cart' },
  sign_in_signed_in: { kind: 'signed_in' },
  sign_in_mfa_required: { kind: 'mfa_required', challengeId: 'challenge-1', expiresAt: '2026-12-10T10:00:00Z' },
  sign_in_rejected: { kind: 'failed', refusal: { category: 'caller_action', code: 'login_failed', detail: DETAIL } },
  sign_in_page_changed: { kind: 'failed', refusal: { category: 'upstream', code: 'login_page_changed' } },
  sign_in_timed_out: { kind: 'timed_out', refusal: timeout },
  submit_mfa_signed_in: { kind: 'signed_in' },
  submit_mfa_invalid: { kind: 'failed', refusal: { category: 'upstream', code: 'mfa_invalid' } },
  refresh_ok: { kind: 'ok' },
  refresh_failed: { kind: 'failed', refusal: { category: 'caller_action', code: 'recgov_refresh_failed', detail: DETAIL } },
  verify_not_authenticated: { kind: 'failed', refusal: { category: 'caller_action', code: 'recgov_not_authenticated' } },
  verify_timed_out: { kind: 'timed_out', refusal: timeout },
  health_active: { kind: 'active' },
  health_never_logged_in: { kind: 'never_logged_in' },
  health_inactive: { kind: 'inactive', code: 'recgov_session_expired' },
  health_check_failed: { kind: 'check_failed', code: 'recgov_auth_check_exception', detail: DETAIL },
  health_unavailable: { kind: 'unavailable', code: 'companion_unavailable', detail: DETAIL },
  health_timed_out: { kind: 'unavailable', code: 'relay_timeout' },
  sign_out_wiped: { kind: 'wiped', signedOut: true },
  sign_out_failed: { kind: 'failed', signedOut: false, refusal: { category: 'retry_later', code: 'profile_busy' } },
  sign_out_timed_out: { kind: 'timed_out', refusal: timeout },
} satisfies Record<string, HoldResult | LoginResult | ActionResult | SessionHealth | SignOutResult>;

type RelayAnswer<R> = { answer: unknown; result: R };

function answerOf<K extends keyof typeof RELAY_DECODED>(name: K): RelayAnswer<(typeof RELAY_DECODED)[K]> {
  return { answer: RELAY_CASES[name].body, result: RELAY_DECODED[name] };
}

export const RELAY_ANSWERS: {
  hold: RelayAnswer<HoldResult>;
  signIn: RelayAnswer<LoginResult>;
  submitMfa: RelayAnswer<LoginResult>;
  refresh: RelayAnswer<ActionResult>;
  verify: RelayAnswer<ActionResult>;
  health: RelayAnswer<SessionHealth>;
  signOut: RelayAnswer<SignOutResult>;
} = {
  hold: answerOf('hold_held'),
  signIn: answerOf('sign_in_signed_in'),
  submitMfa: answerOf('sign_in_mfa_required'),
  refresh: answerOf('refresh_ok'),
  verify: answerOf('verify_not_authenticated'),
  health: answerOf('health_active'),
  signOut: answerOf('sign_out_wiped'),
};
