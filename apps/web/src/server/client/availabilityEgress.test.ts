import { afterEach, expect, test, vi } from 'vitest';
import { EGRESS_PATH, fetchEgress } from './availabilityEgress';

const ENDPOINT = { url: new URL('https://egress.floo.test'), access: { clientId: 'id.access', clientSecret: 'secret' } };
const REQUEST = { campground: { data_provider: 'aspira', data_provider_ref: 'bc:1:2' }, start_date: '2026-12-01', end_date: '2026-12-08' };
const BODY = { start_date: '2026-12-01', end_date: '2026-12-08', cells: [] };
const TIMEOUT_MS = 50;

afterEach(() => {
  vi.unstubAllGlobals();
});

test('sends the Access credentials and the JSON body to the egress path, with no bearer', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json(BODY));
  vi.stubGlobal('fetch', fetch);
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'cells', body: BODY });
  const [url, init] = fetch.mock.calls[0]!;
  expect(String(url)).toBe(`https://egress.floo.test${EGRESS_PATH}`);
  expect(init.method).toBe('POST');
  expect(init.headers['cf-access-client-id']).toBe('id.access');
  expect(init.headers['cf-access-client-secret']).toBe('secret');
  expect(init.headers).not.toHaveProperty('authorization');
  expect(JSON.parse(init.body)).toEqual(REQUEST);
});

test('redirects are not followed: an Access login redirect is an error, not a page', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 302, headers: { location: 'https://team.cloudflareaccess.com/cdn-cgi/access/login/x' } }));
  vi.stubGlobal('fetch', fetch);
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 302, code: 'egress_redirected', body: null });
  expect(fetch.mock.calls[0]![1].redirect).toBe('manual');
});

test.each([
  ['an HTML page', new Response('<!DOCTYPE html><title>Access</title>', { status: 200, headers: { 'content-type': 'text/html' } })],
  ['malformed JSON', new Response('{"start_date":', { status: 200, headers: { 'content-type': 'application/json' } })],
])('a success answer carrying %s is an error, not a crash', async (_label, response) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 200, code: 'egress_not_json', body: null });
});

test('a 503 body becomes an error with its code', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ state: 'error', error: 'rate_limited' }, { status: 503 })));
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({
    kind: 'error',
    status: 503,
    code: 'rate_limited',
    body: { state: 'error', error: 'rate_limited' },
  });
});

test('an answer past the timeout becomes an error with code timeout', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((_url: URL, init: RequestInit) => new Promise((_resolve, reject) => init.signal!.addEventListener('abort', () => reject(init.signal!.reason)))),
  );
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 0, code: 'timeout', body: null });
});

test('an unreachable host becomes an error with code unreachable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 0, code: 'unreachable', body: null });
});
