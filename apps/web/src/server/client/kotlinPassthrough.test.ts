import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { KOTLIN_FORCE_PATH, KOTLIN_READ_PATH, forwardToKotlin, isPassthrough } from './kotlinPassthrough';

const CADDYFILE = path.resolve(import.meta.dirname, '../../../../../caddy/Caddyfile');

beforeEach(() => {
  vi.stubEnv('AVAILABILITY_EGRESS_URL', 'https://egress.floo.test');
  vi.stubEnv('AVAILABILITY_EGRESS_ACCESS_CLIENT_ID', 'id.access');
  vi.stubEnv('AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET', 'secret');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

const get = (pathAndQuery: string) => new Request(`http://localhost${pathAndQuery}`);

test('only the dashboard reads, the force action and the docs pass through', () => {
  for (const ok of ['/api/availability/pollers', '/api/availability/pollers/summary', '/api/availability/pollers/12/runs', '/api/availability/runs', '/api/availability/changes', '/api/availability/changes/summary', '/api/docs', '/api/docs/openapi.json']) {
    expect(isPassthrough('GET', ok), ok).toBe(true);
  }
  expect(isPassthrough('POST', '/api/availability/pollers/12/force')).toBe(true);
  for (const [method, no] of [['GET', '/api/availability/pollers/12/force'], ['POST', '/api/availability/pollers'], ['GET', '/api/watches'], ['GET', '/api/admin/data/status'], ['DELETE', '/api/availability/runs'], ['GET', '/api/availability/pollers/abc/runs']] as const) {
    expect(isPassthrough(method, no), `${method} ${no}`).toBe(false);
  }
});

test('Caddy lets exactly these patterns through the egress host', () => {
  const caddyfile = readFileSync(CADDYFILE, 'utf8');
  expect(caddyfile).toContain(`path_regexp ${KOTLIN_READ_PATH}`);
  expect(caddyfile).toContain(`path_regexp ${KOTLIN_FORCE_PATH}`);
});

test('a read is forwarded with its query and the Access credentials, and its answer passed back', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{"pollers":[]}', { status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'x=1' } }));
  vi.stubGlobal('fetch', fetch);
  const response = await forwardToKotlin(get('/api/availability/pollers?active=true&limit=5'));
  expect(response.status).toBe(200);
  expect(await response.text()).toBe('{"pollers":[]}');
  expect(response.headers.get('set-cookie')).toBeNull();
  const [url, init] = fetch.mock.calls[0]!;
  expect(String(url)).toBe('https://egress.floo.test/api/availability/pollers?active=true&limit=5');
  expect(init.headers.get('cf-access-client-id')).toBe('id.access');
  expect(init.headers.get('cf-access-client-secret')).toBe('secret');
  expect(init.redirect).toBe('manual');
});

test('force is forwarded as a POST with its body and its status kept', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ error: 'cooldown' }, { status: 429, headers: { 'retry-after': '30' } }));
  vi.stubGlobal('fetch', fetch);
  const response = await forwardToKotlin(new Request('http://localhost/api/availability/pollers/12/force', { method: 'POST', body: '{}' }));
  expect(response.status).toBe(429);
  expect(response.headers.get('retry-after')).toBe('30');
  expect(new TextDecoder().decode(fetch.mock.calls[0]![1].body)).toBe('{}');
});

test('a path off the list is 404 and never forwarded', async () => {
  const fetch = vi.fn();
  vi.stubGlobal('fetch', fetch);
  expect((await forwardToKotlin(get('/api/availability/secret'))).status).toBe(404);
  expect(fetch).not.toHaveBeenCalled();
});

test('unconfigured or unreachable is 503', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
  expect((await forwardToKotlin(get('/api/availability/runs'))).status).toBe(503);
  vi.unstubAllEnvs();
  expect((await forwardToKotlin(get('/api/availability/runs'))).status).toBe(503);
  vi.restoreAllMocks();
});

test('a redirect to a login page is 503, and a relative redirect within the list passes', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 302, headers: { location: 'https://x.cloudflareaccess.com/login' } })));
  expect((await forwardToKotlin(get('/api/docs'))).status).toBe(503);
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 302, headers: { location: '/api/docs/index.html' } })));
  const redirected = await forwardToKotlin(get('/api/docs'));
  expect(redirected.status).toBe(302);
  expect(redirected.headers.get('location')).toBe('/api/docs/index.html');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 302, headers: { location: '//evil.test/api/docs' } })));
  expect((await forwardToKotlin(get('/api/docs'))).status).toBe(503);
  vi.restoreAllMocks();
});
