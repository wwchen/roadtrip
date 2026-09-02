// The rec.gov test-login flow as a pure state machine.
//
// Lives beside BookingPanel and is unit-tested directly — the `matrix-rows.ts`
// pattern. The panel owns the requests and the DOM; this module owns which of
// the five states the flow is in, so the rules that actually matter (a late
// answer cannot resurrect a cancelled step, a challenge id survives the submit)
// are testable without rendering anything.

/** A challenge id from the server, meaningful only to the backend. */
type ChallengeId = string;

/**
 * `challengeId` is null for a challenge this client did not open — a panel that
 * remounted and learned from the status row that one is still pending. The
 * server holds the id either way; the code step does not need it to submit.
 */
export type BookingLoginState =
  | { kind: 'idle' }
  | { kind: 'logging_in' }
  | { kind: 'mfa_pending'; challengeId: ChallengeId | null }
  | { kind: 'submitting'; challengeId: ChallengeId | null }
  | { kind: 'ok' }
  | { kind: 'failed'; code: string };

export type BookingLoginEvent =
  | { type: 'login_started' }
  // Nullable for the same reason the state is: a `profile_busy` answer tells the
  // panel a challenge is open without naming it.
  | { type: 'mfa_required'; challengeId: ChallengeId | null }
  /** The server says a challenge is open that this client never saw. */
  | { type: 'resumed' }
  | { type: 'code_submitted' }
  | { type: 'cancelled' }
  | { type: 'succeeded' }
  | { type: 'failed'; code: string };

/** The resting state. A shared constant so `=== IDLE` is a valid identity check. */
export const IDLE: BookingLoginState = { kind: 'idle' };

/** True exactly while a request is out, which is what disables the buttons. */
export function isLoginBusy(state: BookingLoginState): boolean {
  return state.kind === 'logging_in' || state.kind === 'submitting';
}

/** The open challenge, or null when there is none. */
export function pendingChallengeId(state: BookingLoginState): ChallengeId | null {
  return state.kind === 'mfa_pending' || state.kind === 'submitting' ? state.challengeId : null;
}

/**
 * The transition table.
 *
 * Unhandled pairs return the state unchanged (by identity, so React skips the
 * render). That is the whole defence against out-of-order answers: a resolve
 * that lands after the user cancelled finds a state that does not accept it.
 */
export function nextLoginState(
  state: BookingLoginState,
  event: BookingLoginEvent,
): BookingLoginState {
  switch (event.type) {
    case 'login_started':
      // Abandons any open challenge: a fresh login makes rec.gov issue a new
      // code, so the one the user was holding is dead either way.
      return isLoginBusy(state) ? state : { kind: 'logging_in' };
    case 'mfa_required':
      return state.kind === 'logging_in'
        ? { kind: 'mfa_pending', challengeId: event.challengeId }
        : state;
    case 'resumed':
      // Only from rest: a flow already under way knows better than the status
      // row, which may have been fetched before it started.
      return state.kind === 'idle' ? { kind: 'mfa_pending', challengeId: null } : state;
    case 'code_submitted':
      return state.kind === 'mfa_pending'
        ? { kind: 'submitting', challengeId: state.challengeId }
        : state;
    case 'cancelled':
      return state.kind === 'mfa_pending' ? IDLE : state;
    case 'succeeded':
      return isLoginBusy(state) ? { kind: 'ok' } : state;
    case 'failed':
      return isLoginBusy(state) ? { kind: 'failed', code: event.code } : state;
  }
}
