// No node --test suite existed for this client. Covers the boolean→'1'/'0'
// serialisation and the swallow-on-failure contract the search box relies on.
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

  // Swallowing failure keeps a transient upstream error showing "no matches"
  // rather than an error state mid-keystroke.
  test.each([
    ['a 500', textResponse('boom', 500)],
    ['a 429', jsonResponse({ error: 'rate_limited' }, 429)],
  ])('yields an empty result list on %s', async (_label, response) => {
    stubFetch(response);

    await expect(geocode('x')).resolves.toEqual({ results: [] });
  });
});
