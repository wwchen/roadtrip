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

test('with sign-in on the verifier goes to Neon Auth middleware, which sends unknown visitors to sign-in', async () => {
  const exchanged = new Response(null, { status: 307, headers: { location: 'https://roadtrip.test/watches' } });
  const handle = vi.fn(async () => exchanged);
  const middleware = vi.fn(() => handle);
  neonAuth.mockReturnValue({ middleware });
  const request = callback();
  expect(await proxy(request)).toBe(exchanged);
  expect(middleware).toHaveBeenCalledWith({ loginUrl: '/auth/sign-in' });
  expect(handle).toHaveBeenCalledWith(request);
});
