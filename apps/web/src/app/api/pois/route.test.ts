import { beforeEach, expect, test, vi } from 'vitest';

const viewportPois = vi.fn();
vi.mock('@/server/service/poiService', () => ({ viewportPois }));

const { POST } = await import('./route');

const URL = 'http://localhost/api/pois';
const post = (body: string) => POST(new Request(URL, { method: 'POST', body, headers: { 'content-type': 'application/json' } }));

beforeEach(() => {
  viewportPois.mockReset();
  viewportPois.mockResolvedValue({ type: 'FeatureCollection', truncated: false, features: [] });
});

test('a valid body is answered by the service', async () => {
  const response = await post(JSON.stringify({ bbox: [-121, 38, -119, 40], zoom: 7 }));
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ type: 'FeatureCollection', truncated: false, features: [] });
  expect(viewportPois).toHaveBeenCalledWith({ west: -121, south: 38, east: -119, north: 40 }, 7, null);
});

test.each(['{"bbox":[2,0,1,1]}', 'not json', ''])('a bad body %j is 400 bad_request and never reaches the service', async (body) => {
  const response = await post(body);
  expect(response.status).toBe(400);
  expect(await response.json()).toMatchObject({ error: 'bad_request' });
  expect(viewportPois).not.toHaveBeenCalled();
});
