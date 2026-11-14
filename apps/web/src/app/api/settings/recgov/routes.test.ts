import { beforeEach, expect, test, vi } from 'vitest';
import { sealMfaChallenge } from '@/server/auth/mfaChallengeCookie';
import { challengeCodec } from '@/server/mapper/http/recgovHttp';
import { memoryVault } from '@/server/testing/memoryVault';

const KEY = Buffer.alloc(32, 5);
const NOW = Date.parse('2026-12-01T00:00:00Z');
const clock = { now: () => NOW };
const STORED = { username: 'rec@example.com', password: 'hunter2' };
const FAILED = { kind: 'failed', refusal: { category: 'upstream', code: 'recgov_refresh_failed' } };

const currentAppUser = vi.fn();
const gateway = {
  hold: vi.fn(),
  signIn: vi.fn(),
  submitMfa: vi.fn(),
  refresh: vi.fn(),
  verify: vi.fn(),
  health: vi.fn(),
  signOut: vi.fn(),
};
vi.mock('@/server/auth/currentUser', () => ({ currentAppUser: () => currentAppUser() }));
vi.mock('@/server/wiring/recgovPorts', () => ({
  recgovPorts: () => ({ gateway, vault: memoryVault(STORED).vault, clock, mfaChallengeTtlMs: 300_000, challenges: challengeCodec(KEY, clock) }),
}));

beforeEach(() => {
  currentAppUser.mockReset();
  for (const fn of Object.values(gateway)) fn.mockReset();
});

const root = await import('./route');
const loginRoute = await import('./login/route');
const mfaRoute = await import('./login/mfa/route');
const verifyRoute = await import('./verify/route');
const statusRoute = await import('./status/route');

const USER = { id: 7n, email: 'a@b.test', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const request = (method: string, body?: string, cookie?: string) =>
  new Request('http://localhost/api/settings/recgov', {
    method,
    ...(body === undefined ? {} : { body }),
    headers: cookie === undefined ? {} : { cookie },
  });

test('every route answers 401 to an anonymous caller, never cached, and calls no gateway', async () => {
  const calls = [
    () => root.PUT(request('PUT', '{}')),
    () => root.DELETE(),
    () => loginRoute.POST(),
    () => mfaRoute.POST(request('POST', '{"code":"1"}')),
    () => verifyRoute.POST(),
    () => statusRoute.GET(request('GET')),
  ];
  for (const call of calls) {
    currentAppUser.mockResolvedValueOnce(null);
    const response = await call();
    expect(response.status).toBe(401);
    expect(response.headers.get('cache-control')).toBe('private, no-store');
  }
  for (const fn of Object.values(gateway)) expect(fn).not.toHaveBeenCalled();
});

test('mfa_required sets the sealed challenge cookie on the login path only', async () => {
  currentAppUser.mockResolvedValue(USER);
  gateway.refresh.mockResolvedValue(FAILED);
  gateway.signIn.mockResolvedValue({ kind: 'mfa_required', challengeId: 'ch-9', expiresAt: '2026-12-01T00:02:00Z' });
  const response = await loginRoute.POST();
  const cookie = response.headers.get('set-cookie') ?? '';
  expect(cookie).toContain('__Secure-recgov-mfa=');
  expect(cookie).toContain('Path=/api/settings/recgov;');
  expect(cookie).toContain('Max-Age=120');
  expect(cookie).toContain('HttpOnly');
  expect(cookie).toContain('Secure');
  expect(cookie).toContain('SameSite=Strict');
  expect(response.headers.get('cache-control')).toBe('private, no-store');
});

test('the mfa route hands the gateway the challenge the cookie names and clears it', async () => {
  currentAppUser.mockResolvedValue(USER);
  gateway.submitMfa.mockResolvedValue({ kind: 'signed_in' });
  const sealed = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 60_000 });
  const response = await mfaRoute.POST(request('POST', '{"code":"123456"}', `other=1; __Secure-recgov-mfa=${sealed}`));
  expect(gateway.submitMfa).toHaveBeenCalledWith(7n, 'ch-9', '123456');
  expect(response.headers.get('set-cookie')).toContain('Max-Age=0');
});

test('status reads the cookie and is never cached', async () => {
  currentAppUser.mockResolvedValue(USER);
  gateway.health.mockResolvedValue({ kind: 'never_logged_in' });
  const sealed = sealMfaChallenge(KEY, { challengeId: 'ch-9', userId: 7n, expiresAt: NOW + 60_000 });
  const response = await statusRoute.GET(request('GET', undefined, `__Secure-recgov-mfa=${sealed}`));
  expect(await response.json()).toMatchObject({ configured: true, mfa_pending: true });
  expect(response.headers.get('cache-control')).toBe('private, no-store');
});

test('routes that reach mini-ca once allow the relay its full timeout', () => {
  for (const route of [root, mfaRoute, verifyRoute, statusRoute]) expect(route.maxDuration).toBe(120);
});

test('login, which can make a refresh call and then a sign-in call, allows both', () => {
  expect(loginRoute.maxDuration).toBe(200);
});
