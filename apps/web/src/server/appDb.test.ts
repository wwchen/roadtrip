import { afterEach, expect, test, vi } from 'vitest';

vi.mock('server-only', () => ({}));
const { appDb } = await import('./appDb');
const { ConfigError } = await import('./config');

afterEach(() => {
  vi.unstubAllEnvs();
});

test('the app database is unavailable while sign-in is off', () => {
  vi.stubEnv('NEON_AUTH_BASE_URL', undefined);
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  expect(() => appDb()).toThrow(ConfigError);
});

test('the app database is opened once and reused', () => {
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://ep-test.neonauth.test/neondb/auth');
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', 'c'.repeat(32));
  vi.stubEnv('APP_DATABASE_URL', 'postgres://roadtrip_web_writer@db.test/roadtrip');
  expect(appDb()).toBe(appDb());
});
