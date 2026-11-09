import { beforeEach, expect, test, vi } from 'vitest';

const authPort = vi.fn();
vi.mock('@/server/auth/neonAuth', () => ({ authPort }));
const { GET } = await import('./route');

beforeEach(() => {
  vi.restoreAllMocks();
  authPort.mockReset();
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
