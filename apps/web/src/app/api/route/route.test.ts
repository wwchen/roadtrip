import { afterEach, expect, test, vi } from 'vitest';
import { GET } from './route';

afterEach(() => {
  vi.unstubAllEnvs();
});

test('with no MAPBOX_TOKEN, route is 503 routing_unavailable', async () => {
  vi.stubEnv('MAPBOX_TOKEN', '');
  const response = await GET(new Request('http://localhost/api/route?coords=-120,39;-119,39'));
  expect(response.status).toBe(503);
  expect(await response.json()).toMatchObject({ error: 'routing_unavailable', detail: expect.any(String) });
});

test('a bad query is 400 with its code and detail', async () => {
  vi.stubEnv('MAPBOX_TOKEN', 'pk.test');
  const response = await GET(new Request('http://localhost/api/route?coords=-120,39'));
  expect(response.status).toBe(400);
  expect(await response.json()).toMatchObject({ error: 'too_few_points', detail: expect.any(String) });
});
