import { beforeEach, expect, test, vi } from 'vitest';

const getCampsites = vi.fn();
vi.mock('@/server/service/availability/campsiteListService', () => ({ getCampsites }));
const { GET } = await import('./route');

const get = (id: string, query = '') => GET(new Request(`http://localhost/api/pois/${id}/campsites${query}`), { params: Promise.resolve({ id }) });
const LIST = { poi_id: 7, type: 'campsite', campsites: [], reservation_url_templates: {} };

beforeEach(() => {
  getCampsites.mockReset();
});

test('bad id is 400 bad_id', async () => {
  const response = await get('x');
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'bad_id' });
});

test('an unknown site_type is 400 bad_request naming it', async () => {
  const response = await get('7', '?site_type=yurt');
  expect(response.status).toBe(400);
  expect((await response.json()).detail).toContain('yurt');
});

test('an unknown POI is 404 not_found', async () => {
  getCampsites.mockResolvedValue(null);
  const response = await get('7');
  expect(response.status).toBe(404);
  expect(await response.json()).toEqual({ error: 'not_found' });
});

test('site_type reaches the service and the list is served', async () => {
  getCampsites.mockResolvedValue(LIST);
  const response = await get('7', '?site_type=rv');
  expect(response.status).toBe(200);
  expect(getCampsites).toHaveBeenCalledWith(7n, ['rv']);
  expect(await response.json()).toEqual(LIST);
});
