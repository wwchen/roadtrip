import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { BookingGateway } from '../service/booking/ports';
import { RELAY_ANSWERS } from '../testing/bookingRelayAnswers';
import { BOOKING_RELAY_PREFIX, bookingRelayGateway, postRelay, unconfiguredBookingGateway } from './bookingRelay';

const CONFIG = {
  relayUrl: new URL('https://egress.floo.test'),
  relayToken: 'relay-token',
  access: { clientId: 'id.access', clientSecret: 'secret' },
  freshnessMaxAgeMs: 300_000,
  mfaChallengeTtlMs: 300_000,
};
const BODY = { profile_id: 'n:7' };
const OPAQUE_JSON = { any: 'json' };
const TIMEOUT_MS = 50;

const WINDOW = { startDate: '2026-12-10', endDate: '2026-12-11' };

let warn: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

test('sends the bearer, the Access pair and the JSON body to the relay path', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json(OPAQUE_JSON));
  vi.stubGlobal('fetch', fetch);
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'answered', status: 200, body: OPAQUE_JSON });
  const [url, init] = fetch.mock.calls[0]!;
  expect(String(url)).toBe(`https://egress.floo.test${BOOKING_RELAY_PREFIX}/health`);
  expect(init.method).toBe('POST');
  expect(init.headers.authorization).toBe('Bearer relay-token');
  expect(init.headers['cf-access-client-id']).toBe('id.access');
  expect(init.headers['cf-access-client-secret']).toBe('secret');
  expect(init.redirect).toBe('manual');
  expect(init.headers['x-internal-api-version']).toBe('1');
  expect(JSON.parse(init.body)).toEqual(BODY);
});

test('a relay that does not serve our internal API version is a version_mismatch fault, and is logged', async () => {
  const refusal = { error: 'internal_version_unsupported', detail: 'served versions: 2' };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(refusal, { status: 400 })));
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'fault', fault: 'version_mismatch' });
  expect(warn).toHaveBeenCalledWith('booking gateway:', 'version_mismatch');
});

test('the relay timeout envelope is an answer, with its status', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(OPAQUE_JSON, { status: 504 })));
  expect(await postRelay(CONFIG, 'atc', BODY, TIMEOUT_MS)).toEqual({ kind: 'answered', status: 504, body: OPAQUE_JSON });
});

test('a 401 from the relay is unauthorized', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: 'unauthorized' }, { status: 401 })));
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'fault', fault: 'unauthorized' });
});

test("Cloudflare's HTML 524 page is malformed, not a crash", async () => {
  const page = new Response('<!DOCTYPE html><title>524</title>', { status: 524, headers: { 'content-type': 'text/html' } });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(page));
  expect(await postRelay(CONFIG, 'atc', BODY, TIMEOUT_MS)).toEqual({ kind: 'fault', fault: 'malformed' });
});

test('a JSON body of any shape is handed to the decoder', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(OPAQUE_JSON)));
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'answered', status: 200, body: OPAQUE_JSON });
});

test('a redirect is redirected', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 302, headers: { location: 'https://x.test' } })));
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'fault', fault: 'redirected' });
});

test('a timeout and an unreachable relay are told apart', async () => {
  const timeout = Object.assign(new Error('timed out'), { name: 'TimeoutError' });
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(timeout));
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'fault', fault: 'unanswered' });
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
  expect(await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ kind: 'fault', fault: 'unreachable' });
});

test('a fault is logged once with its kind', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
  await postRelay(CONFIG, 'health', BODY, TIMEOUT_MS);
  expect(warn).toHaveBeenCalledTimes(1);
  expect(warn).toHaveBeenCalledWith('booking gateway:', 'unreachable');
});

test('the unconfigured gateway answers not_configured for every operation, health included', async () => {
  const notConfigured = { kind: 'unavailable', fault: 'not_configured' };
  const gateway = unconfiguredBookingGateway;
  expect(await gateway.hold(7n, '300', WINDOW)).toEqual(notConfigured);
  expect(await gateway.signIn(7n, 'camper', 'pw')).toEqual(notConfigured);
  expect(await gateway.submitMfa(7n, 'c-1', '123456')).toEqual(notConfigured);
  expect(await gateway.refresh(7n)).toEqual(notConfigured);
  expect(await gateway.verify(7n)).toEqual(notConfigured);
  expect(await gateway.signOut(7n)).toEqual(notConfigured);
  expect(await gateway.health(7n)).toEqual(notConfigured);
  expect(warn).not.toHaveBeenCalled();
});

const USER_ID = 7n;
const PROFILE_BODY = { profile_id: 'n:7' };

test.each([
  [
    'hold',
    (gateway: BookingGateway) => gateway.hold(USER_ID, '300', WINDOW),
    'atc',
    { profile_id: 'n:7', vendor_site_id: '300', start_date: '2026-12-10', end_date: '2026-12-11' },
    RELAY_ANSWERS.hold,
  ],
  [
    'signIn',
    (gateway: BookingGateway) => gateway.signIn(USER_ID, 'rec@example.com', 'hunter2'),
    'login',
    { profile_id: 'n:7', username: 'rec@example.com', password: 'hunter2' },
    RELAY_ANSWERS.signIn,
  ],
  [
    'submitMfa',
    (gateway: BookingGateway) => gateway.submitMfa(USER_ID, 'ch-9', '123456'),
    'login/mfa',
    { profile_id: 'n:7', challenge_id: 'ch-9', mfa_code: '123456' },
    RELAY_ANSWERS.submitMfa,
  ],
  ['refresh', (gateway: BookingGateway) => gateway.refresh(USER_ID), 'refresh', PROFILE_BODY, RELAY_ANSWERS.refresh],
  [
    'verify',
    (gateway: BookingGateway) => gateway.verify(USER_ID),
    'verify',
    PROFILE_BODY,
    RELAY_ANSWERS.verify,
  ],
  ['health', (gateway: BookingGateway) => gateway.health(USER_ID), 'health', PROFILE_BODY, RELAY_ANSWERS.health],
  [
    'signOut',
    (gateway: BookingGateway) => gateway.signOut(USER_ID),
    'signout',
    PROFILE_BODY,
    RELAY_ANSWERS.signOut,
  ],
])('each gateway operation posts its own path and body: %s', async (_name, call, path, expectedBody, { answer, result }) => {
  const fetch = vi.fn().mockResolvedValue(Response.json(answer));
  vi.stubGlobal('fetch', fetch);
  expect(await call(bookingRelayGateway(CONFIG))).toEqual(result);
  const [url, init] = fetch.mock.calls[0]!;
  expect(String(url)).toBe(`https://egress.floo.test${BOOKING_RELAY_PREFIX}/${path}`);
  expect(JSON.parse(init.body)).toEqual(expectedBody);
});

test('a decoder-made fault is logged once by the gateway', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({})));
  expect(await bookingRelayGateway(CONFIG).hold(USER_ID, '300', WINDOW)).toEqual({ kind: 'unavailable', fault: 'malformed' });
  expect(warn).toHaveBeenCalledTimes(1);
  expect(warn).toHaveBeenCalledWith('booking gateway:', 'malformed');
});

test('a health answer with an unknown session is logged as malformed', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ session: 'weird' })));
  expect(await bookingRelayGateway(CONFIG).health(USER_ID)).toEqual({ kind: 'unavailable', fault: 'malformed' });
  expect(warn).toHaveBeenCalledWith('booking gateway:', 'malformed');
});

test('a transport fault is logged once, not again by the gateway', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: 'unauthorized' }, { status: 401 })));
  expect(await bookingRelayGateway(CONFIG).hold(USER_ID, '300', WINDOW)).toEqual({ kind: 'unavailable', fault: 'unauthorized' });
  expect(warn).toHaveBeenCalledTimes(1);
});

test('a malformed health answer is logged once', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({})));
  expect(await bookingRelayGateway(CONFIG).health(USER_ID)).toEqual({ kind: 'unavailable', fault: 'malformed' });
  expect(warn).toHaveBeenCalledTimes(1);
  expect(warn).toHaveBeenCalledWith('booking gateway:', 'malformed');
});
