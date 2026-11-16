import { afterEach, expect, test, vi } from 'vitest';

const BASE_URL = 'https://ep-test.neonauth.test/neondb/auth';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.doUnmock('./neonAuth');
  vi.restoreAllMocks();
});

test('a port with no sign-in settings is still configured, with no admins', async () => {
  vi.stubEnv('NEON_AUTH_BASE_URL', undefined);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  vi.resetModules();
  vi.doMock('./neonAuth', () => ({ authPort: () => ({}) }));
  const { signInConfigured, signInSetup } = await import('./signInConfigured');
  expect(signInConfigured()).toBe(true);
  expect(signInSetup()?.adminEmails.size).toBe(0);
});

async function freshSignInConfigured(): Promise<() => boolean> {
  vi.resetModules();
  const { signInConfigured } = await import('./signInConfigured');
  return signInConfigured;
}

test('no sign-in settings means sign-in is not configured', async () => {
  vi.stubEnv('NEON_AUTH_BASE_URL', undefined);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  const signInConfigured = await freshSignInConfigured();
  expect(signInConfigured()).toBe(false);
});

test('a partial sign-in setup is not configured, and does not throw', async () => {
  vi.stubEnv('NEON_AUTH_BASE_URL', BASE_URL);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const signInConfigured = await freshSignInConfigured();
  expect(signInConfigured()).toBe(false);
});
