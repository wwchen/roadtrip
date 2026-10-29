import { beforeEach, expect, test, vi } from 'vitest';

const isDatabaseReachable = vi.fn();

vi.mock('@/server/service/healthService', () => ({ isDatabaseReachable }));

const { GET } = await import('./route');

const STATUS_OK = 200;
const STATUS_UNAVAILABLE = 503;

beforeEach(() => {
  isDatabaseReachable.mockReset();
});

test('a reachable database is 200 ok', async () => {
  isDatabaseReachable.mockResolvedValue(true);
  const response = await GET();
  expect(response.status).toBe(STATUS_OK);
  expect(await response.json()).toEqual({ status: 'ok' });
});

test('an unreachable database is 503', async () => {
  isDatabaseReachable.mockResolvedValue(false);
  const response = await GET();
  expect(response.status).toBe(STATUS_UNAVAILABLE);
  expect(await response.json()).toEqual({ status: 'unavailable' });
});
