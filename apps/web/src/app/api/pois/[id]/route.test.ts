import { beforeEach, expect, test, vi } from 'vitest';

const getPoiDetail = vi.fn();
vi.mock('@/server/service/poiDetailService', () => ({ getPoiDetail }));

const { GET } = await import('./route');

const get = (id: string) => GET(new Request(`http://localhost/api/pois/${id}`), { params: Promise.resolve({ id }) });
const FEATURE = { type: 'Feature', id: 7, geometry: { type: 'Point', coordinates: [0, 0] }, properties: {} };

beforeEach(() => {
  getPoiDetail.mockReset();
});

test.each(['abc', '1.5', '', '99999999999999999999999'])('a non-numeric id %j is 400 bad_id', async (id) => {
  const response = await get(id);
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'bad_id' });
  expect(getPoiDetail).not.toHaveBeenCalled();
});

test('an unknown id is 404 not_found', async () => {
  getPoiDetail.mockResolvedValue(null);
  const response = await get('42');
  expect(response.status).toBe(404);
  expect(await response.json()).toEqual({ error: 'not_found' });
});

test('a found POI is 200 with Cache-Control public, max-age=300, stale-while-revalidate=3600', async () => {
  getPoiDetail.mockResolvedValue(FEATURE);
  const response = await get('7');
  expect(response.status).toBe(200);
  expect(response.headers.get('Cache-Control')).toBe('public, max-age=300, stale-while-revalidate=3600');
  expect(await response.json()).toEqual(FEATURE);
  expect(getPoiDetail).toHaveBeenCalledWith(7n);
});
