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
  shapeFault,
} from './bookingRelayWire';

const OK_STATUS = 200;
const BAD_REQUEST_STATUS = 400;
const SERVER_ERROR_STATUS = 500;
const TIMEOUT_STATUS = 504;
const CART_URL = 'https://www.recreation.gov/cart';
const CHALLENGE_ID = 'c-1';
const EXPIRES_AT = '2026-12-10T10:00:00Z';
const DETAIL = 'd';
const CALLER_ACTION: RefusalCategory = 'caller_action';
const RETRY_LATER: RefusalCategory = 'retry_later';
const RELAY_TIMEOUT_CODE = 'relay_timeout';

function answered(status: number, body: unknown): RelayExchange {
  return { kind: 'answered', status, body };
}

const failure = { code: 'x', category: CALLER_ACTION, detail: DETAIL };
const refusal = { category: CALLER_ACTION, code: 'x', detail: DETAIL };
const timeoutFailure = { code: RELAY_TIMEOUT_CODE, category: RETRY_LATER, detail: DETAIL };
const timeoutRefusal = { category: RETRY_LATER, code: RELAY_TIMEOUT_CODE, detail: DETAIL };
const MALFORMED = { kind: 'unavailable', fault: 'malformed' };
const REJECTED = { kind: 'unavailable', fault: 'rejected' };
const SIGN_OUT_FLAGS = { signed_out: true, profile_destroyed: false };
const ALL_FLAGS = { signed_out: true, profile_destroyed: true };

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
const wrongBodies: [string, RelayExchange, unknown][] = [
  ['a body that is not an object, 200', answered(OK_STATUS, 'text'), MALFORMED],
  ['null, 200', answered(OK_STATUS, null), MALFORMED],
  ['an array, 200', answered(OK_STATUS, []), MALFORMED],
  ['an empty object, 200', answered(OK_STATUS, {}), MALFORMED],
  ['an empty object, 504', answered(TIMEOUT_STATUS, {}), MALFORMED],
  ['a non-string tag, 200', answered(OK_STATUS, { outcome: 5 }), MALFORMED],
  ['a bad request without a tag, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), REJECTED],
  ['an empty object, 500', answered(SERVER_ERROR_STATUS, {}), REJECTED],
  ['a held body, 500', answered(SERVER_ERROR_STATUS, { outcome: 'held', cart_url: CART_URL }), REJECTED],
  ['a signed-in body, 400', answered(BAD_REQUEST_STATUS, { outcome: 'signed_in' }), REJECTED],
  ['an unknown tag', answered(OK_STATUS, { outcome: 'weird' }), MALFORMED],
  ['a health body', answered(OK_STATUS, { session: 'active' }), MALFORMED],
];

describe('shapeFault', () => {
  test.each([
    ['a fault exchange', unreachable, null],
    ['a body with an outcome', answered(OK_STATUS, { outcome: 'held' }), null],
    ['a body with a session', answered(OK_STATUS, { session: 'active' }), null],
    ['a body with an outcome at 504', answered(TIMEOUT_STATUS, { outcome: 'timed_out' }), null],
    ['a body with an outcome at 500', answered(SERVER_ERROR_STATUS, { outcome: 'held' }), 'rejected'],
    ['a body with a session at 400', answered(BAD_REQUEST_STATUS, { session: 'active' }), 'rejected'],
    ['no tag, 200', answered(OK_STATUS, {}), 'malformed'],
    ['no tag, 504', answered(TIMEOUT_STATUS, {}), 'malformed'],
    ['no tag, 500', answered(SERVER_ERROR_STATUS, {}), 'rejected'],
    ['null, 200', answered(OK_STATUS, null), 'malformed'],
    ['an array, 400', answered(BAD_REQUEST_STATUS, []), 'rejected'],
    ['a string, 200', answered(OK_STATUS, 'text'), 'malformed'],
  ])('%s', (_name, exchange, expected) => {
    expect(shapeFault(exchange)).toBe(expected);
  });
});

describe('decodeHold', () => {
  test.each([
    ['a fault', unreachable, { kind: 'unavailable', fault: 'unreachable' }],
    ...wrongBodies,
    ['held', answered(OK_STATUS, { outcome: 'held', cart_url: CART_URL }), { kind: 'held', cartUrl: CART_URL }],
    ['held without a cart url', answered(OK_STATUS, { outcome: 'held' }), MALFORMED],
    ['held with a non-string cart url', answered(OK_STATUS, { outcome: 'held', cart_url: 5 }), MALFORMED],
    ['blocked', answered(OK_STATUS, { outcome: 'blocked', failure }), { kind: 'blocked', refusal }],
    ['blocked without detail', answered(OK_STATUS, { outcome: 'blocked', failure: { code: 'x', category: CALLER_ACTION } }), { kind: 'blocked', refusal: { category: CALLER_ACTION, code: 'x' } }],
    ['blocked with a non-string detail', answered(OK_STATUS, { outcome: 'blocked', failure: { code: 'x', category: CALLER_ACTION, detail: 5 } }), { kind: 'blocked', refusal: { category: CALLER_ACTION, code: 'x' } }],
    ['blocked without a failure', answered(OK_STATUS, { outcome: 'blocked' }), MALFORMED],
    ['blocked with an unknown category', answered(OK_STATUS, { outcome: 'blocked', failure: { code: 'x', category: 'nonsense' } }), MALFORMED],
    ['blocked with no code', answered(OK_STATUS, { outcome: 'blocked', failure: { category: CALLER_ACTION } }), MALFORMED],
    ['blocked with a non-string code', answered(OK_STATUS, { outcome: 'blocked', failure: { code: 5, category: CALLER_ACTION } }), MALFORMED],
    ['held with a failure that is not an object', answered(OK_STATUS, { outcome: 'held', cart_url: CART_URL, failure: 'x' }), { kind: 'held', cartUrl: CART_URL }],
    ['blocked with a failure that is not an object', answered(OK_STATUS, { outcome: 'blocked', failure: 'x' }), MALFORMED],
    ['timed out with an unknown category', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: { code: 'x', category: 'nonsense' } }), MALFORMED],
    ['timed out with a cart url', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: timeoutFailure, cart_url: 'u' }), { kind: 'timed_out', refusal: timeoutRefusal, cartUrl: 'u' }],
    ['timed out without a cart url', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: timeoutFailure }), { kind: 'timed_out', refusal: timeoutRefusal }],
    ['timed out at 200', answered(OK_STATUS, { outcome: 'timed_out', failure: timeoutFailure }), { kind: 'timed_out', refusal: timeoutRefusal }],
    ['timed out without a failure', answered(TIMEOUT_STATUS, { outcome: 'timed_out' }), MALFORMED],
    ['a login body', answered(OK_STATUS, { outcome: 'signed_in' }), MALFORMED],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeHold(exchange)).toStrictEqual(expected);
  });
});

describe('decodeLogin', () => {
  test.each([
    ['a fault', { kind: 'fault', fault: 'unanswered' } as RelayExchange, { kind: 'unavailable', fault: 'unanswered' }],
    ...wrongBodies,
    ['signed in', answered(OK_STATUS, { outcome: 'signed_in' }), { kind: 'signed_in' }],
    ['mfa required with an expiry', answered(OK_STATUS, { outcome: 'mfa_required', challenge_id: CHALLENGE_ID, expires_at: EXPIRES_AT }), { kind: 'mfa_required', challengeId: CHALLENGE_ID, expiresAt: EXPIRES_AT }],
    ['mfa required without an expiry', answered(OK_STATUS, { outcome: 'mfa_required', challenge_id: CHALLENGE_ID }), { kind: 'mfa_required', challengeId: CHALLENGE_ID }],
    ['mfa required with a non-string expiry', answered(OK_STATUS, { outcome: 'mfa_required', challenge_id: CHALLENGE_ID, expires_at: 5 }), { kind: 'mfa_required', challengeId: CHALLENGE_ID }],
    ['mfa required without a challenge', answered(OK_STATUS, { outcome: 'mfa_required' }), MALFORMED],
    ['mfa required with a non-string challenge', answered(OK_STATUS, { outcome: 'mfa_required', challenge_id: 5 }), MALFORMED],
    ['failed', answered(OK_STATUS, { outcome: 'failed', failure }), { kind: 'failed', refusal }],
    ['failed without a failure', answered(OK_STATUS, { outcome: 'failed' }), MALFORMED],
    ['failed with an unknown category', answered(OK_STATUS, { outcome: 'failed', failure: { code: 'x', category: 'nonsense' } }), MALFORMED],
    ['failed with no code', answered(OK_STATUS, { outcome: 'failed', failure: { category: CALLER_ACTION } }), MALFORMED],
    ['failed with a non-string code', answered(OK_STATUS, { outcome: 'failed', failure: { code: 5, category: CALLER_ACTION } }), MALFORMED],
    ['failed with a failure that is not an object', answered(OK_STATUS, { outcome: 'failed', failure: 'x' }), MALFORMED],
    ['timed out', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: timeoutFailure }), { kind: 'timed_out', refusal: timeoutRefusal }],
    ['timed out without a failure', answered(TIMEOUT_STATUS, { outcome: 'timed_out' }), MALFORMED],
    ['timed out with no code', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: { category: RETRY_LATER } }), MALFORMED],
    ['a hold body', answered(OK_STATUS, { outcome: 'held', cart_url: CART_URL }), MALFORMED],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeLogin(exchange)).toStrictEqual(expected);
  });
});

describe('decodeAction', () => {
  test.each([
    ['a fault', { kind: 'fault', fault: 'redirected' } as RelayExchange, { kind: 'unavailable', fault: 'redirected' }],
    ...wrongBodies,
    ['ok', answered(OK_STATUS, { outcome: 'ok' }), { kind: 'ok' }],
    ['failed', answered(OK_STATUS, { outcome: 'failed', failure }), { kind: 'failed', refusal }],
    ['failed without a failure', answered(OK_STATUS, { outcome: 'failed' }), MALFORMED],
    ['failed with an unknown category', answered(OK_STATUS, { outcome: 'failed', failure: { code: 'x', category: 'nonsense' } }), MALFORMED],
    ['failed with no code', answered(OK_STATUS, { outcome: 'failed', failure: { category: CALLER_ACTION } }), MALFORMED],
    ['failed with a non-string code', answered(OK_STATUS, { outcome: 'failed', failure: { code: 5, category: CALLER_ACTION } }), MALFORMED],
    ['failed with a failure that is not an object', answered(OK_STATUS, { outcome: 'failed', failure: 'x' }), MALFORMED],
    ['timed out', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: timeoutFailure }), { kind: 'timed_out', refusal: timeoutRefusal }],
    ['timed out without a failure', answered(TIMEOUT_STATUS, { outcome: 'timed_out' }), MALFORMED],
    ['timed out with an unknown category', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: { code: 'x', category: 'nonsense' } }), MALFORMED],
    ['a login body', answered(OK_STATUS, { outcome: 'signed_in' }), MALFORMED],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeAction(exchange)).toStrictEqual(expected);
  });
});

describe('decodeHealth', () => {
  test.each([
    ['a fault', unreachable, { kind: 'unavailable' }],
    ['a body that is not an object, 200', answered(OK_STATUS, 'text'), { kind: 'unavailable' }],
    ['null, 200', answered(OK_STATUS, null), { kind: 'unavailable' }],
    ['an empty object, 200', answered(OK_STATUS, {}), { kind: 'unavailable' }],
    ['an empty object, 500', answered(SERVER_ERROR_STATUS, {}), { kind: 'unavailable' }],
    ['an active body, 500', answered(SERVER_ERROR_STATUS, { session: 'active' }), { kind: 'unavailable' }],
    ['a bad request without a tag, 400', answered(BAD_REQUEST_STATUS, { error: 'bad_request' }), { kind: 'unavailable' }],
    ['active', answered(OK_STATUS, { session: 'active' }), { kind: 'active' }],
    ['never logged in', answered(OK_STATUS, { session: 'never_logged_in' }), { kind: 'never_logged_in' }],
    ['inactive with a code', answered(OK_STATUS, { session: 'inactive', code: 'session_expired' }), { kind: 'inactive', code: 'session_expired' }],
    ['inactive without a code', answered(OK_STATUS, { session: 'inactive' }), { kind: 'inactive' }],
    ['inactive with a non-string code', answered(OK_STATUS, { session: 'inactive', code: 5 }), { kind: 'inactive' }],
    ['check failed', answered(OK_STATUS, { session: 'check_failed', code: 'c', detail: DETAIL }), { kind: 'check_failed', code: 'c', detail: DETAIL }],
    ['check failed bare', answered(OK_STATUS, { session: 'check_failed' }), { kind: 'check_failed' }],
    ['check failed with non-string fields', answered(OK_STATUS, { session: 'check_failed', code: 5, detail: 5 }), { kind: 'check_failed' }],
    ['unavailable', answered(OK_STATUS, { session: 'unavailable', code: 'c', detail: DETAIL }), { kind: 'unavailable', code: 'c', detail: DETAIL }],
    ['unavailable bare', answered(OK_STATUS, { session: 'unavailable' }), { kind: 'unavailable' }],
    ['an unknown session', answered(OK_STATUS, { session: 'weird', code: 'c', detail: DETAIL }), { kind: 'unavailable', code: 'c', detail: DETAIL }],
    ['a hold body', answered(OK_STATUS, { outcome: 'held', cart_url: CART_URL }), { kind: 'unavailable' }],
    ['an array, 200', answered(OK_STATUS, []), { kind: 'unavailable' }],
    ['a non-string tag, 200', answered(OK_STATUS, { outcome: 5 }), { kind: 'unavailable' }],
    ['the health timeout body, 504', answered(TIMEOUT_STATUS, { session: 'unavailable', code: 'relay_timeout' }), { kind: 'unavailable', code: 'relay_timeout' }],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeHealth(exchange)).toStrictEqual(expected);
  });
});

describe('decodeSignOut', () => {
  test.each([
    ['a fault', unreachable, { kind: 'unavailable', fault: 'unreachable' }],
    ...wrongBodies,
    ['wiped', answered(OK_STATUS, { outcome: 'wiped', signed_out: true, profile_destroyed: true }), { kind: 'wiped', signedOut: true }],
    ['wiped, not signed out', answered(OK_STATUS, { outcome: 'wiped', signed_out: false, profile_destroyed: true }), { kind: 'wiped', signedOut: false }],
    ['wiped without signed_out', answered(OK_STATUS, { outcome: 'wiped', profile_destroyed: true }), MALFORMED],
    ['wiped without profile_destroyed', answered(OK_STATUS, { outcome: 'wiped', signed_out: true }), MALFORMED],
    ['wiped with a non-boolean signed_out', answered(OK_STATUS, { outcome: 'wiped', signed_out: 'yes', profile_destroyed: true }), MALFORMED],
    ['failed, signed out', answered(OK_STATUS, { outcome: 'failed', ...SIGN_OUT_FLAGS, failure }), { kind: 'failed', signedOut: true, refusal }],
    ['failed, not signed out', answered(OK_STATUS, { outcome: 'failed', signed_out: false, profile_destroyed: false, failure }), { kind: 'failed', signedOut: false, refusal }],
    ['failed without a failure', answered(OK_STATUS, { outcome: 'failed', ...SIGN_OUT_FLAGS }), MALFORMED],
    ['failed with an unknown category', answered(OK_STATUS, { outcome: 'failed', ...SIGN_OUT_FLAGS, failure: { code: 'x', category: 'nonsense' } }), MALFORMED],
    ['failed with no code', answered(OK_STATUS, { outcome: 'failed', ...SIGN_OUT_FLAGS, failure: { category: CALLER_ACTION } }), MALFORMED],
    ['failed without the flags', answered(OK_STATUS, { outcome: 'failed', failure }), MALFORMED],
    ['timed out', answered(TIMEOUT_STATUS, { outcome: 'timed_out', ...SIGN_OUT_FLAGS, failure: timeoutFailure }), { kind: 'timed_out', refusal: timeoutRefusal }],
    ['timed out without a failure', answered(TIMEOUT_STATUS, { outcome: 'timed_out', ...SIGN_OUT_FLAGS }), MALFORMED],
    ['timed out without the flags', answered(TIMEOUT_STATUS, { outcome: 'timed_out', failure: timeoutFailure }), MALFORMED],
    ['a login body', answered(OK_STATUS, { outcome: 'signed_in' }), MALFORMED],
    ['a login tag with valid flags', answered(OK_STATUS, { outcome: 'signed_in', ...ALL_FLAGS }), MALFORMED],
    ['an unknown tag with valid flags', answered(OK_STATUS, { outcome: 'weird', ...ALL_FLAGS }), MALFORMED],
    ['a hold body with valid flags', answered(OK_STATUS, { outcome: 'held', cart_url: CART_URL, ...ALL_FLAGS }), MALFORMED],
    ['wiped with a non-boolean profile_destroyed', answered(OK_STATUS, { outcome: 'wiped', signed_out: true, profile_destroyed: 'yes' }), MALFORMED],
  ])('%s', (_name, exchange, expected) => {
    expect(decodeSignOut(exchange)).toStrictEqual(expected);
  });
});
