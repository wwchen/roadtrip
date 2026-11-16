import { afterEach, expect, test, vi } from 'vitest';
import { GET } from './route';
const limits = vi.hoisted(() => {
  const allow = vi.fn((_caller: string, _cost?: number) => true);
  return { allow, all: { refresh: { allow }, stayCheckPois: { allow }, mapbox: { allow } } };
});
vi.mock('@/server/wiring/callerLimits', () => ({ callerLimits: () => limits.all }));

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

test('a caller over the Mapbox limit is 503 ip_throttled before any geocoding', async () => {
  vi.stubEnv('MAPBOX_TOKEN', 'pk.test');
  limits.allow.mockReturnValueOnce(false);
  const fetchSpy = vi.spyOn(globalThis, 'fetch');
  const response = await GET(new Request('http://localhost/api/geocode?q=dallas', { headers: { 'x-forwarded-for': '203.0.113.7' } }));
  expect(response.status).toBe(503);
  expect(await response.json()).toEqual({ error: 'ip_throttled' });
  expect(limits.allow).toHaveBeenLastCalledWith('203.0.113.7');
  expect(fetchSpy).not.toHaveBeenCalled();
  fetchSpy.mockRestore();
});
