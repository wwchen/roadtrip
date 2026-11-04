import { afterEach, expect, test, vi } from 'vitest';
import { GET } from './route';

afterEach(() => {
  vi.unstubAllEnvs();
});

test('with no MAPBOX_TOKEN, geocode is 503 geocoding_unavailable', async () => {
  vi.stubEnv('MAPBOX_TOKEN', '');
  const response = await GET(new Request('http://localhost/api/geocode?q=dallas'));
  expect(response.status).toBe(503);
  expect(await response.json()).toMatchObject({ error: 'geocoding_unavailable' });
});

test.each(['', '%20', 'x'.repeat(201)])('a blank or over-200-character q %j is 400 bad_query', async (q) => {
  vi.stubEnv('MAPBOX_TOKEN', 'pk.test');
  const response = await GET(new Request(`http://localhost/api/geocode?q=${q}`));
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'bad_query' });
});
