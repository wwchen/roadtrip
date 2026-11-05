import { beforeEach, expect, test, vi } from 'vitest';
import { BadDateWindowError } from '@/server/service/availability/availabilityWindow';

const getAvailabilityWeek = vi.fn();
vi.mock('@/server/service/availability/availabilityMatrixService', async (original) => ({
  ...(await original<typeof import('@/server/service/availability/availabilityMatrixService')>()),
  getAvailabilityWeek,
}));
const { AvailabilityTargetError } = await import('@/server/service/availability/availabilityMatrixService');
const { GET } = await import('./route');

const get = (id: string, query = '') =>
  GET(new Request(`http://localhost/api/pois/${id}/campsites/availability${query}`), { params: Promise.resolve({ id }) });
const WEEK = { poi_id: 7, start_date: '2026-12-01', end_date: '2026-12-08', state: 'success', days: [], watch_capabilities: {} };

beforeEach(() => {
  getAvailabilityWeek.mockReset();
});

test('bad id is 400 bad_poi_id', async () => {
  const response = await get('abc');
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ state: 'error', error: 'bad_poi_id' });
});

test.each(['?start_date=2026-13-01', '?end_date=soon'])('bad dates %s are 400 bad_date_window', async (query) => {
  const response = await get('7', query);
  expect(response.status).toBe(400);
  expect((await response.json()).error).toBe('bad_date_window');
  expect(getAvailabilityWeek).not.toHaveBeenCalled();
});

test('an unknown site_type is 400 bad_request', async () => {
  expect((await get('7', '?site_type=yurt')).status).toBe(400);
});

test('site_type and dates reach the service', async () => {
  getAvailabilityWeek.mockResolvedValue(WEEK);
  const response = await get('7', '?site_type=rv&site_type=tent&start_date=2026-12-01&end_date=2026-12-08');
  expect(response.status).toBe(200);
  expect(getAvailabilityWeek).toHaveBeenCalledWith(7n, ['rv', 'tent'], '2026-12-01', '2026-12-08');
});

test('a window the provider refuses is 400 with its code and detail', async () => {
  getAvailabilityWeek.mockRejectedValue(new BadDateWindowError('window_too_long', { max_days: 30 }));
  const response = await get('7');
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ state: 'error', error: 'window_too_long', max_days: 30 });
});

test.each(['not_found', 'unknown_campground'] as const)('%s is 404', async (code) => {
  getAvailabilityWeek.mockRejectedValue(new AvailabilityTargetError(code));
  const response = await get('7');
  expect(response.status).toBe(404);
  expect((await response.json()).error).toBe(code);
});
