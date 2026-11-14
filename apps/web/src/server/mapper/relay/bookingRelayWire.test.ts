import { describe, expect, test } from 'vitest';
import type { RefusalCategory } from '@/server/service/booking/model';
import {
  atcRequest,
  decodeAction,
  decodeHealth,
  decodeHold,
  decodeLogin,
  decodeSignOut,
  loginRequest,
  mfaRequest,
  profileRequest,
  type RelayExchange,
} from './bookingRelayWire';
import { RELAY_ANSWERS } from './bookingRelayWire.fixtures';

const OK_STATUS = 200;
const BAD_REQUEST_STATUS = 400;
const TIMEOUT_STATUS = 504;
const CART_URL = 'https://www.recreation.gov/cart';
const CHALLENGE_ID = 'c-1';
const EXPIRES_AT = '2026-12-10T10:00:00Z';
const DETAIL = 'd';
const UPSTREAM: RefusalCategory = 'upstream';
const RETRY_LATER: RefusalCategory = 'retry_later';

function answered(status: number, body: unknown): RelayExchange {
  return { kind: 'answered', status, body };
}

const refusalEnvelope = { ok: false, code: 'x', category: 'caller_action', detail: DETAIL };
const refusal = { category: 'caller_action', code: 'x', detail: DETAIL };
const timeoutEnvelope = { ok: false, code: 'relay_timeout', category: RETRY_LATER, detail: DETAIL, cart_url: 'u' };
const timeoutRefusal = { category: RETRY_LATER, code: 'relay_timeout', detail: DETAIL };

test('a profile request names the user with the n: prefix', () => {
  expect(profileRequest(7n)).toEqual({ profile_id: 'n:7' });
});

test('an atc request carries the site and the window', () => {
  expect(atcRequest(7n, '300', { startDate: '2026-12-10', endDate: '2026-12-11' })).toEqual({
    profile_id: 'n:7',
    vendor_site_id: '300',
    start_date: '2026-12-10',
    end_date: '2026-12-11',
  });
});

test('a login and an mfa request carry their fields', () => {
  expect(loginRequest(7n, 'camper', 'pw')).toEqual({ profile_id: 'n:7', username: 'camper', password: 'pw' });
  expect(mfaRequest(7n, CHALLENGE_ID, '123456')).toEqual({ profile_id: 'n:7', challenge_id: CHALLENGE_ID, mfa_code: '123456' });
});

const unreachable: RelayExchange = { kind: 'fault', fault: 'unreachable' };

describe('decodeHold', () => {
  test.each([
    ['a fault', unreachable, { kind: 'unavailable', fault: 'unreachable' }],
    ['a body that is not an object, 200', answered(OK_STATUS, 'text'), { kind: 'unavailable', fault: 'malformed' }],
    ['an empty object, 200', answered(OK_STATUS, {}), { kind: 'unavailable', fault: 'malformed' }],
    ['null, 200', answered(OK_STATUS, null), { kind: 'unavailable', fault: 'malformed' }],
    ['a non-boolean ok, 200', answered(OK_STATUS, { ok: 'yes' }), { kind: 'unavailable', fault: 'malformed' }],
    ['a bad request without ok, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), { kind: 'unavailable', fault: 'rejected' }],
    ['ok with a cart url', answered(OK_STATUS, { ok: true, cart_url: CART_URL }), { kind: 'held', cartUrl: CART_URL }],
    ['ok without a cart url', answered(OK_STATUS, { ok: true }), { kind: 'unavailable', fault: 'malformed' }],
    ['a refusal without a code', answered(OK_STATUS, { ok: false }), { kind: 'unavailable', fault: 'malformed' }],
    ['a refusal', answered(OK_STATUS, refusalEnvelope), { kind: 'blocked', refusal }],
    [
      'an unknown category',
      answered(OK_STATUS, { ok: false, code: 'x', category: 'nonsense' }),
      { kind: 'blocked', refusal: { category: UPSTREAM, code: 'x' } },
    ],
    [
      'no category',
      answered(OK_STATUS, { ok: false, code: 'x' }),
      { kind: 'blocked', refusal: { category: UPSTREAM, code: 'x' } },
    ],
    ['a non-string detail is left out', answered(OK_STATUS, { ok: false, code: 'x', detail: 5 }), { kind: 'blocked', refusal: { category: UPSTREAM, code: 'x' } }],
    ['the relay timeout with a cart url', answered(TIMEOUT_STATUS, timeoutEnvelope), { kind: 'timed_out', refusal: timeoutRefusal, cartUrl: 'u' }],
    [
      'the relay timeout without a cart url',
      answered(TIMEOUT_STATUS, { ok: false, code: 'relay_timeout', category: RETRY_LATER }),
      { kind: 'timed_out', refusal: { category: RETRY_LATER, code: 'relay_timeout' } },
    ],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeHold(exchange)).toEqual(expected);
  });
});

describe('decodeLogin', () => {
  test.each([
    ['a fault', { kind: 'fault', fault: 'unanswered' } as RelayExchange, { kind: 'unavailable', fault: 'unanswered' }],
    ['an empty object, 200', answered(OK_STATUS, {}), { kind: 'unavailable', fault: 'malformed' }],
    ['a bad request without ok, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), { kind: 'unavailable', fault: 'rejected' }],
    ['ok', answered(OK_STATUS, { ok: true }), { kind: 'signed_in' }],
    [
      'mfa required with a challenge',
      answered(OK_STATUS, { ok: false, code: 'mfa_required', challenge_id: CHALLENGE_ID, expires_at: EXPIRES_AT }),
      { kind: 'mfa_required', challengeId: CHALLENGE_ID, expiresAt: EXPIRES_AT },
    ],
    [
      'mfa required without an expiry',
      answered(OK_STATUS, { ok: false, code: 'mfa_required', challenge_id: CHALLENGE_ID }),
      { kind: 'mfa_required', challengeId: CHALLENGE_ID },
    ],
    ['mfa required without a challenge', answered(OK_STATUS, { ok: false, code: 'mfa_required' }), { kind: 'unavailable', fault: 'malformed' }],
    ['a refusal without a code', answered(OK_STATUS, { ok: false }), { kind: 'unavailable', fault: 'malformed' }],
    ['a refusal', answered(OK_STATUS, refusalEnvelope), { kind: 'failed', refusal }],
    [
      'an unknown category',
      answered(OK_STATUS, { ok: false, code: 'x', category: 'nonsense' }),
      { kind: 'failed', refusal: { category: UPSTREAM, code: 'x' } },
    ],
    ['the relay timeout', answered(TIMEOUT_STATUS, timeoutEnvelope), { kind: 'timed_out', refusal: timeoutRefusal }],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeLogin(exchange)).toEqual(expected);
  });
});

describe('decodeAction', () => {
  test.each([
    ['a fault', { kind: 'fault', fault: 'redirected' } as RelayExchange, { kind: 'unavailable', fault: 'redirected' }],
    ['an empty object, 200', answered(OK_STATUS, {}), { kind: 'unavailable', fault: 'malformed' }],
    ['a bad request without ok, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), { kind: 'unavailable', fault: 'rejected' }],
    ['ok', answered(OK_STATUS, { ok: true }), { kind: 'ok' }],
    ['a refusal without a code', answered(OK_STATUS, { ok: false }), { kind: 'unavailable', fault: 'malformed' }],
    ['a refusal', answered(OK_STATUS, refusalEnvelope), { kind: 'failed', refusal }],
    [
      'an unknown category',
      answered(OK_STATUS, { ok: false, code: 'x', category: 'nonsense' }),
      { kind: 'failed', refusal: { category: UPSTREAM, code: 'x' } },
    ],
    ['the relay timeout', answered(TIMEOUT_STATUS, timeoutEnvelope), { kind: 'timed_out', refusal: timeoutRefusal }],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeAction(exchange)).toEqual(expected);
  });
});

describe('decodeHealth', () => {
  test.each([
    ['a fault', unreachable, { kind: 'unavailable' }],
    ['an empty object, 200', answered(OK_STATUS, {}), { kind: 'unavailable' }],
    ['a bad request without ok, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), { kind: 'unavailable' }],
    ['active', answered(OK_STATUS, { ok: true, health: 'active' }), { kind: 'active' }],
    ['never logged in', answered(OK_STATUS, { ok: true, health: 'never_logged_in' }), { kind: 'never_logged_in' }],
    ['inactive with a code', answered(OK_STATUS, { ok: true, health: 'inactive', code: 'session_expired' }), { kind: 'inactive', code: 'session_expired' }],
    ['inactive without a code', answered(OK_STATUS, { ok: true, health: 'inactive' }), { kind: 'inactive' }],
    [
      'check failed',
      answered(OK_STATUS, { ok: true, health: 'check_failed', code: 'c', detail: DETAIL }),
      { kind: 'check_failed', code: 'c', detail: DETAIL },
    ],
    ['check failed bare', answered(OK_STATUS, { ok: true, health: 'check_failed' }), { kind: 'check_failed' }],
    [
      'health unavailable',
      answered(OK_STATUS, { ok: true, health: 'unavailable', code: 'c', detail: DETAIL }),
      { kind: 'unavailable', code: 'c', detail: DETAIL },
    ],
    ['an unknown health', answered(OK_STATUS, { ok: false, health: 'weird', code: 'c', detail: DETAIL }), { kind: 'unavailable', code: 'c', detail: DETAIL }],
    ['a missing health', answered(OK_STATUS, { ok: false, code: 'c' }), { kind: 'unavailable', code: 'c' }],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeHealth(exchange)).toEqual(expected);
  });
});

describe('decodeSignOut', () => {
  test.each([
    ['a fault', unreachable, { kind: 'unavailable', fault: 'unreachable' }],
    ['an empty object, 200', answered(OK_STATUS, {}), { kind: 'unavailable', fault: 'malformed' }],
    ['a bad request without ok, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), { kind: 'unavailable', fault: 'rejected' }],
    ['wiped and signed out', answered(OK_STATUS, { ok: true, profile_destroyed: true, signed_out: true }), { kind: 'wiped', signedOut: true }],
    ['wiped, not signed out', answered(OK_STATUS, { ok: true, profile_destroyed: true }), { kind: 'wiped', signedOut: false }],
    ['a refusal without a code', answered(OK_STATUS, { ok: false }), { kind: 'unavailable', fault: 'malformed' }],
    ['ok without profile_destroyed', answered(OK_STATUS, { ok: true }), { kind: 'unavailable', fault: 'malformed' }],
    ['a refusal, signed out', answered(OK_STATUS, { ...refusalEnvelope, signed_out: true }), { kind: 'failed', signedOut: true, refusal }],
    ['a refusal, not signed out', answered(OK_STATUS, refusalEnvelope), { kind: 'failed', signedOut: false, refusal }],
    [
      'an unknown category',
      answered(OK_STATUS, { ok: false, code: 'x', category: 'nonsense' }),
      { kind: 'failed', signedOut: false, refusal: { category: UPSTREAM, code: 'x' } },
    ],
    ['the relay timeout', answered(TIMEOUT_STATUS, timeoutEnvelope), { kind: 'timed_out', refusal: timeoutRefusal }],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeSignOut(exchange)).toEqual(expected);
  });
});

const DECODERS = {
  hold: decodeHold,
  signIn: decodeLogin,
  submitMfa: decodeLogin,
  refresh: decodeAction,
  verify: decodeAction,
  health: decodeHealth,
  signOut: decodeSignOut,
};

test.each(Object.entries(RELAY_ANSWERS))('each fixture answer decodes to its fixture result', (name, { answer, result }) => {
  const decode: (exchange: RelayExchange) => unknown = DECODERS[name as keyof typeof DECODERS];
  expect(decode(answered(OK_STATUS, answer))).toEqual(result);
});
