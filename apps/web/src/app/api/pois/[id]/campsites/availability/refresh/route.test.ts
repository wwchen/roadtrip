import { beforeEach, expect, test, vi } from 'vitest';
import { BadDateWindowError } from '@/server/service/availability/availabilityWindow';

const after = vi.fn();
vi.mock('next/server', () => ({ after }));
const planRefresh = vi.fn();
const runRefresh = vi.fn();
vi.mock('@/server/service/availability/availabilityRefreshService', () => ({ planRefresh, runRefresh }));
vi.mock('@/server/db', () => ({ serverDb: vi.fn().mockResolvedValue({}) }));
const { AvailabilityTargetError } = await import('@/server/service/availability/availabilityMatrixService');
const { POST, maxDuration } = await import('./route');

const post = (id: string, query = '') =>
  POST(new Request(`http://localhost/api/pois/${id}/campsites/availability/refresh${query}`, { method: 'POST' }), {
    params: Promise.resolve({ id }),
  });
const FETCH = { kind: 'fetch', request: {} };

beforeEach(() => {
  vi.unstubAllEnvs();
  after.mockReset();
  planRefresh.mockReset();
  runRefresh.mockReset();
});

test('the route allows the egress timeout to finish', () => {
  expect(maxDuration).toBe(60);
});

test('a campground with no campsites is 202 without scheduling a refresh', async () => {
  planRefresh.mockResolvedValue({ kind: 'nothing' });
  expect((await post('7')).status).toBe(202);
  expect(after).not.toHaveBeenCalled();
});

test('stale is 202 and schedules runRefresh through after', async () => {
  vi.stubEnv('AVAILABILITY_EGRESS_URL', 'https://egress.floo.test');
  vi.stubEnv('AVAILABILITY_EGRESS_ACCESS_CLIENT_ID', 'id.access');
  vi.stubEnv('AVAILABILITY_EGRESS_ACCESS_CLIENT_SECRET', 'secret');
  planRefresh.mockResolvedValue(FETCH);
  expect((await post('7', '?start_date=2026-12-01&end_date=2026-12-08')).status).toBe(202);
  expect(planRefresh.mock.calls[0]!.slice(1, 4)).toEqual([7n, '2026-12-01', '2026-12-08']);
  expect(planRefresh.mock.calls[0]![5]).toBe(true);
  expect(after).toHaveBeenCalledTimes(1);
  await after.mock.calls[0]![0]();
  expect(runRefresh).toHaveBeenCalledWith(FETCH, expect.any(Function));
});

test.each(['disabled', 'unsupported'])('%s is 501 with its code', async (kind) => {
  planRefresh.mockResolvedValue({ kind });
  const response = await post('7');
  expect(response.status).toBe(501);
  expect((await response.json()).error).toBe(kind === 'disabled' ? 'refresh_disabled' : 'unknown_campground');
});

test('an unknown POI is 404', async () => {
  planRefresh.mockRejectedValue(new AvailabilityTargetError('not_found'));
  expect((await post('7')).status).toBe(404);
});

test('bad dates are 400', async () => {
  expect((await post('7', '?start_date=nope')).status).toBe(400);
  planRefresh.mockRejectedValue(new BadDateWindowError('end_before_start'));
  expect((await post('7')).status).toBe(400);
});

test('a bad id is 400', async () => {
  expect((await post('x')).status).toBe(400);
});
