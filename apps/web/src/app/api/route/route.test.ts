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

test('a caller over the Mapbox limit is 503 ip_throttled before any routing', async () => {
  vi.stubEnv('MAPBOX_TOKEN', 'pk.test');
  limits.allow.mockReturnValueOnce(false);
  const fetchSpy = vi.spyOn(globalThis, 'fetch');
  const response = await GET(new Request('http://localhost/api/route?coords=-120,39;-119,39', { headers: { 'x-forwarded-for': '203.0.113.7' } }));
  expect(response.status).toBe(503);
  expect(await response.json()).toEqual({ error: 'ip_throttled', detail: expect.any(String) });
  expect(limits.allow).toHaveBeenLastCalledWith('203.0.113.7');
  expect(fetchSpy).not.toHaveBeenCalled();
  fetchSpy.mockRestore();
});
