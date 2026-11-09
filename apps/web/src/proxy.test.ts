import { NextRequest } from 'next/server';
import { beforeEach, expect, test, vi } from 'vitest';

const neonAuth = vi.fn();
vi.mock('@/server/auth/neonAuth', () => ({ neonAuth }));
const { config, proxy } = await import('./proxy');

const VERIFIER_PARAM = 'neon_auth_session_verifier';
const callback = () => new NextRequest(`https://roadtrip.test/watches?${VERIFIER_PARAM}=v-1`);

beforeEach(() => {
  neonAuth.mockReset();
});

test('the proxy runs only on requests that carry a session verifier', () => {
  expect(config.matcher).toEqual([{ source: '/:path*', has: [{ type: 'query', key: VERIFIER_PARAM }] }]);
});

test('with sign-in off the request passes through', async () => {
  neonAuth.mockReturnValue(null);
  const response = await proxy(callback());
  expect(response.headers.get('x-middleware-next')).toBe('1');
});

const CHALLENGE_COOKIE = '__Secure-neon-auth.session_challenge=c-1';

test('with sign-in on, a verifier with its challenge cookie goes to Neon Auth middleware, which keeps the return path on failure', async () => {
  const exchanged = new Response(null, { status: 307, headers: { location: 'https://roadtrip.test/watches?x=1' } });
  const handle = vi.fn(async () => exchanged);
  const middleware = vi.fn(() => handle);
  neonAuth.mockReturnValue({ middleware });
  const request = new NextRequest(`https://roadtrip.test/watches?x=1&${VERIFIER_PARAM}=v-1`, {
    headers: { cookie: CHALLENGE_COOKIE },
  });
  expect(await proxy(request)).toBe(exchanged);
  expect(middleware).toHaveBeenCalledWith({ loginUrl: '/auth/sign-in?return_to=%2Fwatches%3Fx%3D1&error=retry' });
  expect(handle).toHaveBeenCalledWith(request);
});

test('a verifier opened without its challenge cookie lands on sign-in with the return path and a notice', async () => {
  const middleware = vi.fn();
  neonAuth.mockReturnValue({ middleware });
  const response = await proxy(callback());
  expect(middleware).not.toHaveBeenCalled();
  expect([302, 307]).toContain(response.status);
  expect(new URL(response.headers.get('location') ?? '').pathname + new URL(response.headers.get('location') ?? '').search).toBe(
    '/auth/sign-in?return_to=%2Fwatches&error=link',
  );
});
