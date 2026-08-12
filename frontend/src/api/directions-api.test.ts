import { afterEach, describe, expect, test, vi } from 'vitest';
import { requestRoute } from './directions-api';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('requestRoute', () => {
  const stops = [
    { lng: -122.4194, lat: 37.7749 },
    { lng: -121.6, lat: 40.35 },
  ];

  test('encodes stops as lng,lat pairs joined by semicolons', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await requestRoute({ stops, radiusMiles: 25 });

    expect(fetchStub.last.url).toBe(
      '/api/route?coords=-122.4194%2C37.7749%3B-121.6%2C40.35&radius_miles=25',
    );
  });

  test('handles a single stop', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await requestRoute({ stops: [stops[0]!], radiusMiles: 5 });

    expect(fetchStub.last.url).toBe('/api/route?coords=-122.4194%2C37.7749&radius_miles=5');
  });

  test('sends an empty coords param for no stops', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await requestRoute({ stops: [], radiusMiles: 5 });

    expect(fetchStub.last.url).toBe('/api/route?coords=&radius_miles=5');
  });

  test('forwards the abort signal', async () => {
    const fetchStub = stubFetch(jsonResponse({}));
    const controller = new AbortController();

    await requestRoute({ stops, radiusMiles: 25, signal: controller.signal });

    expect(fetchStub.last.init.signal).toBe(controller.signal);
  });

  test('resolves to the raw Response, even on failure', async () => {
    stubFetch(jsonResponse({ error: 'no_route' }, 422));

    const response = await requestRoute({ stops, radiusMiles: 25 });

    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(422);
  });
});
