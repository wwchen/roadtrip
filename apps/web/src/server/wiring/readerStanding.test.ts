import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { ActiveUser } from '../service/auth/model';
import type { CredentialVault } from '../service/booking/ports';
import { memoryVault } from '../testing/memoryVault';

vi.mock('server-only', () => ({}));

const signInConfigured = vi.fn<() => boolean>();
const currentAppUser = vi.fn<() => Promise<ActiveUser | null>>();
const liveVault = vi.fn<() => CredentialVault>();
let realLiveVault: () => CredentialVault;
vi.mock('../auth/signInConfigured', () => ({ signInConfigured: () => signInConfigured() }));
vi.mock('../auth/currentUser', () => ({ currentAppUser: () => currentAppUser() }));
vi.mock('./bookingAdapters', async (original) => {
  const actual = await original<typeof import('./bookingAdapters')>();
  realLiveVault = actual.liveVault;
  return { ...actual, liveVault: () => liveVault() };
});

const { liveReaderStanding } = await import('./readerStanding');

const READER: ActiveUser = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', status: 'active' };
const STORED = { username: 'rec@example.com', password: 'hunter2-recognisable' };
const MALFORMED_KEY = 'not-a-key';

function stubSignInSettings(): void {
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://ep-test.neonauth.test/neondb/auth');
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', 'c'.repeat(32));
  vi.stubEnv('APP_DATABASE_URL', 'postgres://roadtrip_web_writer@db.test/roadtrip');
}

beforeEach(() => {
  signInConfigured.mockReset();
  currentAppUser.mockReset();
  currentAppUser.mockResolvedValue(null);
  liveVault.mockReset();
  liveVault.mockImplementation(() => realLiveVault());
});

afterEach(() => {
  vi.unstubAllEnvs();
});

test('with sign-in off the live reader is sign_in_off and the vault is never opened', async () => {
  vi.stubEnv('NEON_AUTH_BASE_URL', undefined);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  signInConfigured.mockReturnValue(false);

  expect(await liveReaderStanding()).toBe('sign_in_off');
  expect(currentAppUser).not.toHaveBeenCalled();
  expect(liveVault).not.toHaveBeenCalled();
});

test('a signed-out reader is signed_out and a malformed encryption key is never read', async () => {
  stubSignInSettings();
  vi.stubEnv('BOOKING_CREDENTIAL_ENCRYPTION_KEY', MALFORMED_KEY);
  signInConfigured.mockReturnValue(true);

  expect(await liveReaderStanding()).toBe('signed_out');
  expect(liveVault).not.toHaveBeenCalled();
});

test('a signed-in reader is read from the vault, opened once they are known', async () => {
  signInConfigured.mockReturnValue(true);
  currentAppUser.mockResolvedValue(READER);

  liveVault.mockReturnValue(memoryVault(STORED).vault);
  expect(await liveReaderStanding()).toBe('ready');

  liveVault.mockReturnValue(memoryVault(null).vault);
  expect(await liveReaderStanding()).toBe('no_credentials');
  expect(liveVault).toHaveBeenCalledTimes(2);
});
