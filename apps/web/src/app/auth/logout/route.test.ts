import { beforeEach, expect, test, vi } from 'vitest';

const authPort = vi.fn();
const deleted: string[] = [];
const jar = [{ name: '__Secure-neon-auth.session_token' }, { name: '__Secure-neon-auth.local.session_data' }, { name: 'theme' }];
vi.mock('@/server/auth/neonAuth', () => ({ authPort, NEON_AUTH_COOKIE_PREFIX: '__Secure-neon-auth.' }));
vi.mock('next/headers', () => ({
  cookies: async () => ({
    getAll: () => jar,
    set: (name: string, _value: string, options: { maxAge?: number; secure?: boolean; path?: string }) => {
      if (options.maxAge === 0 && options.secure === true && options.path === '/') deleted.push(name);
    },
  }),
}));
const { GET } = await import('./route');

beforeEach(() => {
  vi.restoreAllMocks();
  authPort.mockReset();
  deleted.length = 0;
});

test('logout signs out and goes home', async () => {
  const signOut = vi.fn(async () => {});
  authPort.mockReturnValue({ signOut });
  const response = await GET();
  expect(signOut).toHaveBeenCalledTimes(1);
  expect(response.status).toBe(302);
  expect(response.headers.get('location')).toBe('/');
});

test('logout with sign-in off still goes home', async () => {
  authPort.mockReturnValue(null);
  const response = await GET();
  expect(response.status).toBe(302);
  expect(response.headers.get('location')).toBe('/');
});

test.each([
  ['answers a failure', async () => ({ ok: false, code: 'forbidden' })],
  ['throws', async () => Promise.reject(new Error('upstream exploded'))],
])('logout still goes home when sign-out %s', async (_how, signOut) => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  authPort.mockReturnValue({ signOut: vi.fn(signOut) });
  const response = await GET();
  expect(response.status).toBe(302);
  expect(response.headers.get('location')).toBe('/');
  expect(console.warn).toHaveBeenCalledTimes(1);
});

test('a refused sign-out still expires the Neon Auth cookies, and only those', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  authPort.mockReturnValue({ signOut: vi.fn(async () => ({ ok: false, code: 'feature_not_supported' })) });
  await GET();
  expect(deleted.sort()).toEqual(['__Secure-neon-auth.local.session_data', '__Secure-neon-auth.session_token']);
});

test('a sign-out Neon Auth accepted leaves the cookies to the SDK', async () => {
  authPort.mockReturnValue({ signOut: vi.fn(async () => ({ ok: true })) });
  await GET();
  expect(deleted).toEqual([]);
});
