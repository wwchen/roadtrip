import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const serverDb = vi.fn(() => ({}));
const pingDatabase = vi.fn();

vi.mock('@/server/db', () => ({ serverDb }));
vi.mock('@/server/repo/healthRepo', () => ({ pingDatabase }));

const { GET } = await import('./route');

const HEALTH_URL = 'http://localhost/api/health';
const SECRET = 'liveness-test-secret';
const STATUS_OK = 200;

beforeEach(() => {
  serverDb.mockClear();
  pingDatabase.mockReset();
  pingDatabase.mockResolvedValue(undefined);
  vi.stubEnv('HEALTH_DATABASE_TOKEN', SECRET);
});

afterEach(() => {
  vi.unstubAllEnvs();
});

test('an unauthenticated request neither builds a database client nor pings, through the real service', async () => {
  const response = await GET(new Request(HEALTH_URL));
  expect(response.status).toBe(STATUS_OK);
  expect(serverDb).not.toHaveBeenCalled();
  expect(pingDatabase).not.toHaveBeenCalled();
});

test('a refused request neither builds a database client nor pings', async () => {
  await GET(new Request(HEALTH_URL, { headers: { Authorization: 'Bearer wrong' } }));
  expect(serverDb).not.toHaveBeenCalled();
  expect(pingDatabase).not.toHaveBeenCalled();
});

test('the correct secret does reach the database through the real service', async () => {
  const response = await GET(new Request(HEALTH_URL, { headers: { Authorization: `Bearer ${SECRET}` } }));
  expect(response.status).toBe(STATUS_OK);
  expect(serverDb).toHaveBeenCalledTimes(1);
  expect(pingDatabase).toHaveBeenCalledTimes(1);
});
