import { expect, test, vi } from 'vitest';
import { AUTH_UNAVAILABLE, EMAIL_NOT_VERIFIED, PASSWORD_TOO_SHORT, USER_ALREADY_EXISTS, USER_NOT_FOUND } from './authPort';
import { portCodeOf } from './neonAuth';

const STATUS_FORBIDDEN = 403;
const STATUS_UNPROCESSABLE = 422;
const STATUS_BAD_GATEWAY = 502;
const STATUS_UNAVAILABLE = 503;

test.each([
  [{ code: 'email_not_confirmed', status: STATUS_FORBIDDEN }, EMAIL_NOT_VERIFIED],
  [{ code: 'user_already_exists', status: STATUS_UNPROCESSABLE }, USER_ALREADY_EXISTS],
  [{ code: 'email_exists', status: STATUS_UNPROCESSABLE }, USER_ALREADY_EXISTS],
  [{ code: 'weak_password', status: STATUS_UNPROCESSABLE }, PASSWORD_TOO_SHORT],
  [{ code: 'NETWORK_TIMEOUT', status: STATUS_BAD_GATEWAY }, AUTH_UNAVAILABLE],
  [{ code: 'internal_error', status: 500 }, AUTH_UNAVAILABLE],
  [{ code: 'unexpected_failure', status: 400 }, AUTH_UNAVAILABLE],
  [{ code: 'over_request_rate_limit', status: STATUS_UNAVAILABLE }, AUTH_UNAVAILABLE],
  [{ code: 'user_not_found', status: 404 }, USER_NOT_FOUND],
  [{ code: 'invalid_credentials', status: 401 }, 'invalid_credentials'],
  [{ status: 401 }, 'UNKNOWN'],
])('the SDK error %j reaches the port as %s', (error, expected) => {
  expect(portCodeOf(error)).toBe(expected);
});

test('a partial sign-in setup logs the error and leaves sign-in off', async () => {
  vi.resetModules();
  vi.stubEnv('NEON_AUTH_BASE_URL', 'https://ep-test.neonauth.test/neondb/auth');
  vi.stubEnv('NEON_AUTH_COOKIE_SECRET', undefined);
  vi.stubEnv('APP_DATABASE_URL', undefined);
  const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
  const { neonAuth } = await import('./neonAuth');
  expect(neonAuth()).toBeNull();
  expect(neonAuth()).toBeNull();
  expect(logged).toHaveBeenCalledTimes(1);
  vi.unstubAllEnvs();
  logged.mockRestore();
});
