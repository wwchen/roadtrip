import { afterEach, expect, test, vi } from 'vitest';
import { register } from './instrumentation';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

function stubPartialSignIn(): void {
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://auth.example.test');
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET_FILE', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
}

test('a Node server that starts with a group set in part logs it once, by group, as an error', async () => {
  const error = vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.stubEnv('NEXT_RUNTIME', 'nodejs');
  stubPartialSignIn();

  await register();

  const signInLines = error.mock.calls.filter((call) => call[1] === 'signIn');
  expect(signInLines).toHaveLength(1);
  expect(signInLines[0]![0]).toBe('config:');
  expect(String(signInLines[0]![2])).toMatch(/APP_DATABASE_URL/);
});

test('outside the Node runtime, register reads no config', async () => {
  const error = vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.stubEnv('NEXT_RUNTIME', 'edge');
  stubPartialSignIn();

  await register();

  expect(error).not.toHaveBeenCalled();
});
