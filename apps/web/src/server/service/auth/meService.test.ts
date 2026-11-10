import { afterEach, expect, test, vi } from 'vitest';
import type { AuthPort, AuthUser } from '@/server/auth/authPort';
import { EmailTakenError, type AppUserRow, type IdentityClaims } from '@/server/repo/appUserRepo';
import { meFor } from './meService';

const ANONYMOUS = { authenticated: false, auth_enabled: true, auth_embedded: false };
const USER_ID = 42n;
const ADMINS = new Set(['will@example.com']);

const sessionUser: AuthUser = { id: 'neon-user-1', email: 'Will@Example.com', name: 'Will', emailVerified: true };

const portWith = (currentUser: AuthPort['currentUser']): AuthPort =>
  ({ currentUser }) as unknown as AuthPort;

const usersReturning = (row: Partial<AppUserRow> = {}) =>
  vi.fn(async (claims: IdentityClaims): Promise<AppUserRow> => ({
    id: USER_ID,
    email: claims.email,
    emailVerified: claims.emailVerified,
    displayName: claims.displayName,
    theme: 'dark',
    ...row,
  }));

afterEach(() => {
  vi.restoreAllMocks();
});

test('no port means sign-in is disabled', async () => {
  expect(await meFor(null, usersReturning(), ADMINS)).toEqual({
    authenticated: false,
    auth_enabled: false,
    auth_embedded: false,
  });
});

test('no session is anonymous with sign-in enabled', async () => {
  expect(await meFor(portWith(async () => null), usersReturning(), ADMINS)).toEqual(ANONYMOUS);
});

test('a signed-in admin', async () => {
  const users = usersReturning();
  const me = await meFor(portWith(async () => sessionUser), users, ADMINS);
  expect(users).toHaveBeenCalledWith({
    subject: 'neon-user-1',
    email: 'Will@Example.com',
    emailVerified: true,
    displayName: 'Will',
  });
  expect(me).toEqual({
    authenticated: true,
    auth_enabled: true,
    auth_embedded: false,
    user: {
      id: 42,
      email: 'Will@Example.com',
      display_name: 'Will',
      email_verified: true,
      roles: ['admin'],
      theme: 'dark',
    },
  });
});

test('a signed-in non-admin has no roles', async () => {
  const me = await meFor(portWith(async () => ({ ...sessionUser, email: 'other@example.com' })), usersReturning(), ADMINS);
  expect(me.user?.roles).toEqual([]);
});

test('an unreachable Neon Auth answers anonymous', async () => {
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const failing = Object.assign(new Error('fetch failed'), { code: 'NETWORK_TIMEOUT' });
  const me = await meFor(portWith(async () => Promise.reject(failing)), usersReturning(), ADMINS);
  expect(me).toEqual(ANONYMOUS);
  expect(warn).toHaveBeenCalledWith('me:', 'NETWORK_TIMEOUT');
});

test('an email taken by another account answers anonymous', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  const users = vi.fn(async () => Promise.reject(new EmailTakenError()));
  expect(await meFor(portWith(async () => sessionUser), users, ADMINS)).toEqual(ANONYMOUS);
});

test('an unverified address never gets admin', async () => {
  const me = await meFor(portWith(async () => sessionUser), usersReturning({ emailVerified: false }), ADMINS);
  expect(me.user?.roles).toEqual([]);
});

test('a partial sign-in configuration answers sign-in off and logs it, rather than failing every page', async () => {
  const { currentMe } = await import('./meService');
  const error = vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://ep-test.neonauth.test/neondb/auth');
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  try {
    expect(await currentMe()).toEqual({ authenticated: false, auth_enabled: false, auth_embedded: false });
    expect(error).toHaveBeenCalledWith('me:', expect.stringContaining('NEON_AUTH_COOKIE_SECRET'));
  } finally {
    vi.unstubAllEnvs();
  }
});
