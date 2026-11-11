import { afterEach, expect, test, vi } from 'vitest';
import { BOOKING_RELAY_PREFIX, relay } from './bookingRelay';

const CONFIG = {
  relayUrl: new URL('https://egress.floo.test'),
  relayToken: 'relay-token',
  access: { clientId: 'id.access', clientSecret: 'secret' },
  freshnessMaxAgeMs: 300_000,
  mfaChallengeTtlMs: 300_000,
};
const BODY = { profile_id: 'n:7' };
const TIMEOUT_MS = 50;

afterEach(() => {
  vi.unstubAllGlobals();
});

test('sends the bearer, the Access pair and the JSON body to the relay path', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ ok: true, health: 'active' }));
  vi.stubGlobal('fetch', fetch);
  expect(await relay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ ok: true, status: 200, answer: { ok: true, health: 'active' } });
  const [url, init] = fetch.mock.calls[0]!;
  expect(String(url)).toBe(`https://egress.floo.test${BOOKING_RELAY_PREFIX}/health`);
  expect(init.method).toBe('POST');
  expect(init.headers.authorization).toBe('Bearer relay-token');
  expect(init.headers['cf-access-client-id']).toBe('id.access');
  expect(init.headers['cf-access-client-secret']).toBe('secret');
  expect(init.redirect).toBe('manual');
  expect(JSON.parse(init.body)).toEqual(BODY);
});

test('the relay timeout envelope is an answer, with its status', async () => {
  const envelope = { ok: false, code: 'relay_timeout', category: 'retry_later', cart_url: 'https://www.recreation.gov/cart' };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(envelope, { status: 504 })));
  expect(await relay(CONFIG, 'atc', BODY, TIMEOUT_MS)).toEqual({ ok: true, status: 504, answer: envelope });
});

test('a 401 from the relay is relay_unauthorized', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: 'unauthorized' }, { status: 401 })));
  expect(await relay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ ok: false, code: 'relay_unauthorized' });
});

test("Cloudflare's HTML 524 page is relay_not_json, not a crash", async () => {
  const page = new Response('<!DOCTYPE html><title>524</title>', { status: 524, headers: { 'content-type': 'text/html' } });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(page));
  expect(await relay(CONFIG, 'atc', BODY, TIMEOUT_MS)).toEqual({ ok: false, code: 'relay_not_json' });
});

test('a success status with a non-envelope body is relay_not_json', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ hello: 'world' })));
  expect(await relay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ ok: false, code: 'relay_not_json' });
});

test('a redirect is relay_redirected', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 302, headers: { location: 'https://x.test' } })));
  expect(await relay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ ok: false, code: 'relay_redirected' });
});

test('a timeout and an unreachable relay are told apart', async () => {
  const timeout = Object.assign(new Error('timed out'), { name: 'TimeoutError' });
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(timeout));
  expect(await relay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ ok: false, code: 'timeout' });
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
  expect(await relay(CONFIG, 'health', BODY, TIMEOUT_MS)).toEqual({ ok: false, code: 'unreachable' });
});
