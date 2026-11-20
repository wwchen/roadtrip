import { afterEach, describe, expect, test, vi } from 'vitest';
import { geocode } from './geocode-api';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('geocode', () => {
  test('defaults to autocomplete on and a limit of 5', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));

    await geocode('mineral ca');

    expect(fetchStub.last.url).toBe('/api/geocode?q=mineral+ca&autocomplete=1&limit=5');
  });

  test.each([
    [true, '1'],
    [false, '0'],
  ])('serialises autocomplete %s as %s', async (autocomplete, expected) => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));

    await geocode('x', { autocomplete });

    expect(fetchStub.last.url).toContain(`autocomplete=${expected}`);
  });

  test('includes proximity when given', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));

    await geocode('x', { proximity: '-121.5,40.5', limit: 3 });

    expect(fetchStub.last.url).toBe(
      '/api/geocode?q=x&autocomplete=1&limit=3&proximity=-121.5%2C40.5',
    );
  });

  test.each([[null], [undefined], ['']])('omits proximity when %j', async (proximity) => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));

    await geocode('x', { proximity });

    expect(fetchStub.last.url).not.toContain('proximity');
  });

  test('forwards the abort signal', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));
    const controller = new AbortController();

    await geocode('x', { signal: controller.signal });

    expect(fetchStub.last.init.signal).toBe(controller.signal);
  });

  test('returns the parsed results on success', async () => {
    const result = {
      id: 'place.1',
      place_name: 'Mineral, California',
      place_type: 'place',
      lng: -121.6,
      lat: 40.35,
    };
    stubFetch(jsonResponse({ results: [result] }));

    await expect(geocode('mineral')).resolves.toEqual({ results: [result] });
  });

  test('a refusal is an error carrying its code and detail, not an empty answer', async () => {
    stubFetch(jsonResponse({ error: 'geocoding_unavailable', detail: 'mapbox token missing' }, 503));

    await expect(geocode('x')).rejects.toMatchObject({
      name: 'HttpError',
      status: 503,
      code: 'geocoding_unavailable',
      detail: 'mapbox token missing',
    });
  });

  test('a non-JSON failure keeps its body as the detail', async () => {
    stubFetch(textResponse('boom', 500));

    await expect(geocode('x')).rejects.toMatchObject({ name: 'HttpError', status: 500, detail: 'boom' });
  });
});
