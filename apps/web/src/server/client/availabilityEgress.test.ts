import { afterEach, expect, test, vi } from 'vitest';
import { EGRESS_PATH, fetchEgress } from './availabilityEgress';

const ENDPOINT = { url: new URL('https://egress.floo.test'), token: 'egress-token' };
const REQUEST = { campground: { data_provider: 'aspira', data_provider_ref: 'bc:1:2' }, start_date: '2026-12-01', end_date: '2026-12-08' };
const BODY = { start_date: '2026-12-01', end_date: '2026-12-08', cells: [] };
const TIMEOUT_MS = 50;

afterEach(() => {
  vi.unstubAllGlobals();
});

test('sends the bearer and the JSON body to the egress path', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json(BODY));
  vi.stubGlobal('fetch', fetch);
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'cells', body: BODY });
  const [url, init] = fetch.mock.calls[0]!;
  expect(String(url)).toBe(`https://egress.floo.test${EGRESS_PATH}`);
  expect(init.method).toBe('POST');
  expect(init.headers.authorization).toBe('Bearer egress-token');
  expect(JSON.parse(init.body)).toEqual(REQUEST);
});

test('a 503 body becomes an error with its code', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ state: 'error', error: 'rate_limited' }, { status: 503 })));
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 503, code: 'rate_limited' });
});

test('an answer past the timeout becomes an error with code timeout', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn((_url: URL, init: RequestInit) => new Promise((_resolve, reject) => init.signal!.addEventListener('abort', () => reject(init.signal!.reason)))),
  );
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 0, code: 'timeout' });
});

test('an unreachable host becomes an error with code unreachable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
  expect(await fetchEgress(ENDPOINT, REQUEST, TIMEOUT_MS)).toEqual({ kind: 'error', status: 0, code: 'unreachable' });
});
