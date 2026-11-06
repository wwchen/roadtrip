import { afterEach, expect, test, vi } from 'vitest';
import { POST } from './route';

const BODY = JSON.stringify({ waypoints: [{ lat: 39, lng: -120 }, { lat: 39.5, lng: -119 }], radius_miles: 5 });
const post = (body: string) => POST(new Request('http://localhost/api/pois/on-route', { method: 'POST', body }));

afterEach(() => {
  vi.unstubAllEnvs();
});

test('with no MAPBOX_TOKEN, on-route is 503 routing_unavailable', async () => {
  vi.stubEnv('MAPBOX_TOKEN', '');
  const response = await post(BODY);
  expect(response.status).toBe(503);
  expect(await response.json()).toEqual({ error: 'routing_unavailable' });
});

test('radius_miles 0 is 400 bad_request', async () => {
  vi.stubEnv('MAPBOX_TOKEN', 'pk.test');
  const response = await post(JSON.stringify({ waypoints: [{ lat: 39, lng: -120 }, { lat: 39.5, lng: -119 }], radius_miles: 0 }));
  expect(response.status).toBe(400);
  expect(await response.json()).toMatchObject({ error: 'bad_request' });
});
