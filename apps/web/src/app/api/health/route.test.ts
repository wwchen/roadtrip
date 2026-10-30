import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const isDatabaseReachable = vi.fn();

vi.mock('@/server/service/healthService', () => ({ isDatabaseReachable }));

const { GET } = await import('./route');

const HEALTH_URL = 'http://localhost/api/health';
const TOKEN_VARIABLE = 'HEALTH_DATABASE_TOKEN';
const SECRET = 'correct-horse-battery-staple';
const STATUS_OK = 200;
const STATUS_UNAUTHORIZED = 401;
const STATUS_UNAVAILABLE = 503;

const withAuthorization = (value: string) =>
  new Request(HEALTH_URL, { headers: { Authorization: value } });

beforeEach(() => {
  isDatabaseReachable.mockReset();
  vi.stubEnv(TOKEN_VARIABLE, SECRET);
});

afterEach(() => {
  vi.unstubAllEnvs();
});

test('an unauthenticated request is 200 and never reaches the database check', async () => {
  const response = await GET(new Request(HEALTH_URL));
  expect(response.status).toBe(STATUS_OK);
  expect(await response.json()).toEqual({ status: 'ok' });
  expect(isDatabaseReachable).not.toHaveBeenCalled();
});

test('an unauthenticated request is 200 and database-free even when no secret is configured', async () => {
  vi.stubEnv(TOKEN_VARIABLE, undefined);
  const response = await GET(new Request(HEALTH_URL));
  expect(response.status).toBe(STATUS_OK);
  expect(isDatabaseReachable).not.toHaveBeenCalled();
});

test('the correct secret runs the probe: a reachable database is 200 ok', async () => {
  isDatabaseReachable.mockResolvedValue(true);
  const response = await GET(withAuthorization(`Bearer ${SECRET}`));
  expect(response.status).toBe(STATUS_OK);
  expect(await response.json()).toEqual({ status: 'ok' });
  expect(isDatabaseReachable).toHaveBeenCalledTimes(1);
});

test('the correct secret runs the probe: an unreachable database is 503', async () => {
  isDatabaseReachable.mockResolvedValue(false);
  const response = await GET(withAuthorization(`Bearer ${SECRET}`));
  expect(response.status).toBe(STATUS_UNAVAILABLE);
  expect(await response.json()).toEqual({ status: 'unavailable' });
});

test('the scheme is case-insensitive', async () => {
  isDatabaseReachable.mockResolvedValue(true);
  const response = await GET(withAuthorization(`bearer ${SECRET}`));
  expect(response.status).toBe(STATUS_OK);
  expect(isDatabaseReachable).toHaveBeenCalledTimes(1);
});

test.each([
  ['a wrong secret', 'Bearer not-the-secret'],
  ['a secret that is a prefix of the real one', `Bearer ${SECRET.slice(0, -1)}`],
  ['the real secret with a suffix', `Bearer ${SECRET}x`],
  ['the wrong scheme', `Basic ${SECRET}`],
  ['the bare secret with no scheme', SECRET],
  ['an empty header', ''],
  ['an empty bearer token', 'Bearer '],
  ['a whitespace bearer token', 'Bearer    '],
])('%s is refused with 401 and does not query', async (_label, header) => {
  const response = await GET(withAuthorization(header));
  expect(response.status).toBe(STATUS_UNAUTHORIZED);
  expect(response.headers.get('WWW-Authenticate')).toBe('Bearer');
  expect(isDatabaseReachable).not.toHaveBeenCalled();
});

test.each([
  ['unset', undefined],
  ['an empty string', ''],
  ['whitespace', '   '],
])('when the configured secret is %s the database check is unavailable to everyone', async (_label, configured) => {
  vi.stubEnv(TOKEN_VARIABLE, configured);
  for (const presented of ['Bearer ', 'Bearer    ', 'Bearer undefined', `Bearer ${SECRET}`, `Bearer ${configured ?? ''}`]) {
    const response = await GET(withAuthorization(presented));
    expect(response.status).toBe(STATUS_UNAUTHORIZED);
  }
  expect(isDatabaseReachable).not.toHaveBeenCalled();
});
