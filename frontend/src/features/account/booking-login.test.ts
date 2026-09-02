import { describe, expect, test } from 'vitest';
import {
  IDLE,
  isLoginBusy,
  nextLoginState,
  pendingChallengeId,
  type BookingLoginState,
} from './booking-login';

const mfaPending: BookingLoginState = { kind: 'mfa_pending', challengeId: 'chal-1' };

describe('starting a login', () => {
  test('idle → logging_in', () => {
    expect(nextLoginState(IDLE, { type: 'login_started' })).toEqual({ kind: 'logging_in' });
  });

  test('a second start while one is in flight changes nothing', () => {
    const inFlight = nextLoginState(IDLE, { type: 'login_started' });
    expect(nextLoginState(inFlight, { type: 'login_started' })).toBe(inFlight);
  });

  test('starting again from a failure clears the old code', () => {
    const failed = nextLoginState({ kind: 'logging_in' }, { type: 'failed', code: 'login_failed' });
    expect(nextLoginState(failed, { type: 'login_started' })).toEqual({ kind: 'logging_in' });
  });

  test('starting again from a pending challenge abandons it', () => {
    expect(nextLoginState(mfaPending, { type: 'login_started' })).toEqual({ kind: 'logging_in' });
  });
});

describe('answers to a login in flight', () => {
  test('logging_in → ok', () => {
    expect(nextLoginState({ kind: 'logging_in' }, { type: 'succeeded' })).toEqual({ kind: 'ok' });
  });

  test('logging_in → mfa_pending carries the challenge id', () => {
    expect(
      nextLoginState({ kind: 'logging_in' }, { type: 'mfa_required', challengeId: 'chal-1' }),
    ).toEqual(mfaPending);
  });

  test('logging_in → failed carries the code', () => {
    expect(
      nextLoginState({ kind: 'logging_in' }, { type: 'failed', code: 'captcha_required' }),
    ).toEqual({ kind: 'failed', code: 'captcha_required' });
  });

  test('an answer that arrives when nothing is in flight is ignored', () => {
    // A late resolve after the user cancelled must not resurrect the code field.
    expect(nextLoginState(IDLE, { type: 'mfa_required', challengeId: 'chal-9' })).toBe(IDLE);
    expect(nextLoginState(IDLE, { type: 'succeeded' })).toBe(IDLE);
  });
});

describe('the code step', () => {
  test('mfa_pending → submitting keeps the challenge id', () => {
    expect(nextLoginState(mfaPending, { type: 'code_submitted' })).toEqual({
      kind: 'submitting',
      challengeId: 'chal-1',
    });
  });

  test('a code cannot be submitted before a challenge exists', () => {
    expect(nextLoginState(IDLE, { type: 'code_submitted' })).toBe(IDLE);
  });

  test('submitting → ok', () => {
    const submitting = nextLoginState(mfaPending, { type: 'code_submitted' });
    expect(nextLoginState(submitting, { type: 'succeeded' })).toEqual({ kind: 'ok' });
  });

  test('a rejected code fails the flow rather than reopening the step', () => {
    const submitting = nextLoginState(mfaPending, { type: 'code_submitted' });
    expect(nextLoginState(submitting, { type: 'failed', code: 'mfa_invalid' })).toEqual({
      kind: 'failed',
      code: 'mfa_invalid',
    });
  });

  test('cancelling a pending challenge returns to idle', () => {
    expect(nextLoginState(mfaPending, { type: 'cancelled' })).toBe(IDLE);
  });

  test('cancelling cannot abort a code already in flight', () => {
    const submitting = nextLoginState(mfaPending, { type: 'code_submitted' });
    expect(nextLoginState(submitting, { type: 'cancelled' })).toBe(submitting);
  });
});

describe('resuming a challenge the server is still holding', () => {
  test('idle → mfa_pending, with no id the client was never told', () => {
    expect(nextLoginState(IDLE, { type: 'resumed' })).toEqual({
      kind: 'mfa_pending',
      challengeId: null,
    });
  });

  test('a resumed step submits and completes like any other', () => {
    const resumed = nextLoginState(IDLE, { type: 'resumed' });
    const submitting = nextLoginState(resumed, { type: 'code_submitted' });

    expect(submitting).toEqual({ kind: 'submitting', challengeId: null });
    expect(nextLoginState(submitting, { type: 'succeeded' })).toEqual({ kind: 'ok' });
  });

  test('resuming never disturbs a flow already under way', () => {
    const inFlight: BookingLoginState = { kind: 'logging_in' };
    expect(nextLoginState(inFlight, { type: 'resumed' })).toBe(inFlight);
    expect(nextLoginState(mfaPending, { type: 'resumed' })).toBe(mfaPending);

    const done: BookingLoginState = { kind: 'ok' };
    expect(nextLoginState(done, { type: 'resumed' })).toBe(done);
  });
});

describe('derived questions the panel asks', () => {
  test('busy exactly while a request is out', () => {
    expect(isLoginBusy(IDLE)).toBe(false);
    expect(isLoginBusy({ kind: 'logging_in' })).toBe(true);
    expect(isLoginBusy(mfaPending)).toBe(false);
    expect(isLoginBusy({ kind: 'submitting', challengeId: 'chal-1' })).toBe(true);
    expect(isLoginBusy({ kind: 'ok' })).toBe(false);
    expect(isLoginBusy({ kind: 'failed', code: 'login_failed' })).toBe(false);
  });

  test('the challenge id is readable while one is open, and only then', () => {
    expect(pendingChallengeId(mfaPending)).toBe('chal-1');
    expect(pendingChallengeId({ kind: 'submitting', challengeId: 'chal-1' })).toBe('chal-1');
    expect(pendingChallengeId(IDLE)).toBeNull();
    expect(pendingChallengeId({ kind: 'mfa_pending', challengeId: null })).toBeNull();
    expect(pendingChallengeId({ kind: 'ok' })).toBeNull();
  });
});
