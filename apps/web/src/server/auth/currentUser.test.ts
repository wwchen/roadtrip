import { expect, test, vi } from 'vitest';
import type { AuthPort, AuthUser } from './authPort';
import { appUserFor, profileIdFor } from './currentUser';
import type { AppUserRow } from '../repo/appUserRepo';

const SESSION_USER: AuthUser = { id: 'neon-1', email: 'camper@example.com', name: 'Camper', emailVerified: true };
const ROW: AppUserRow = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: 'Camper', theme: 'system' };

function port(user: AuthUser | null): AuthPort {
  return { currentUser: vi.fn().mockResolvedValue(user) } as unknown as AuthPort;
}

test('no auth port is no user', async () => {
  expect(await appUserFor(null, vi.fn())).toBeNull();
});

test('no session is no user, and no row is touched', async () => {
  const users = vi.fn();
  expect(await appUserFor(port(null), users)).toBeNull();
  expect(users).not.toHaveBeenCalled();
});

test('a session resolves to its linked app user', async () => {
  const users = vi.fn().mockResolvedValue(ROW);
  expect(await appUserFor(port(SESSION_USER), users)).toEqual(ROW);
  expect(users).toHaveBeenCalledWith({ subject: 'neon-1', email: 'camper@example.com', emailVerified: true, displayName: 'Camper' });
});

test('a session whose identity cannot be linked is no user', async () => {
  const users = vi.fn().mockRejectedValue(new Error('unverified'));
  expect(await appUserFor(port(SESSION_USER), users)).toBeNull();
});

test('a profile id names the Neon user with the n: prefix', () => {
  expect(profileIdFor(7n)).toBe('n:7');
});
