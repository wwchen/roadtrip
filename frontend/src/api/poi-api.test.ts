// No node --test suite existed for this client. These pin the two things most
// easily broken in a port: the differing failure modes of the four read paths,
// and the categories serialisation.
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  fetchOnRoutePois,
  fetchPoiDetail,
  fetchViewportPois,
  poiSearchUrl,
  requestPoiDetail,
  searchPoiCatalog,
  searchPois,
} from './poi-api';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('poiSearchUrl', () => {
  test('defaults to an empty query at the catalog limit', () => {
    expect(poiSearchUrl()).toBe('/api/pois/search?q=&limit=25');
  });

  test('url-encodes the query', () => {
    expect(poiSearchUrl({ q: 'lassen volcanic & co' })).toBe(
      '/api/pois/search?q=lassen+volcanic+%26+co&limit=25',
    );
  });

  test('joins an array of categories with commas', () => {
    expect(poiSearchUrl({ q: 'x', categories: ['campground', 'state-park'] })).toBe(
      '/api/pois/search?q=x&limit=25&categories=campground%2Cstate-park',
    );
  });

  test('drops blank entries from a categories array', () => {
    expect(poiSearchUrl({ q: 'x', categories: ['campground', '', 'sp'] })).toContain(
      'categories=campground%2Csp',
    );
  });

  test.each([[[]], [['']], [undefined], ['']])(
    'omits the categories param for %j',
    (categories) => {
      expect(poiSearchUrl({ q: 'x', categories })).toBe('/api/pois/search?q=x&limit=25');
    },
  );

  test('passes a categories string straight through', () => {
    expect(poiSearchUrl({ q: 'x', categories: 'campground,sp' })).toContain(
      'categories=campground%2Csp',
    );
  });
});

describe('searchPois', () => {
  // The typeahead limit is deliberately lower than the catalog default.
  test('uses the typeahead limit of 8', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));

    await searchPois('lassen');

    expect(fetchStub.last.url).toBe('/api/pois/search?q=lassen&limit=8');
  });

  // Swallowing failure is the point: a typeahead should read "no matches", not
  // blow up mid-keystroke.
  test.each([
    ['a 500', textResponse('boom', 500)],
    ['a 404', jsonResponse({ error: 'nope' }, 404)],
  ])('yields an empty result list on %s', async (_label, response) => {
    stubFetch(response);

    await expect(searchPois('lassen')).resolves.toEqual({ results: [] });
  });

  test('returns the parsed body on success', async () => {
    stubFetch(jsonResponse({ results: [{ id: 1, name: 'Manzanita Lake' }] }));

    await expect(searchPois('manzanita')).resolves.toEqual({
      results: [{ id: 1, name: 'Manzanita Lake' }],
    });
  });
});

describe('searchPoiCatalog', () => {
  test('throws HttpError where searchPois would swallow', async () => {
    stubFetch(jsonResponse({ error: 'nope' }, 500));

    await expect(searchPoiCatalog({ q: 'x' })).rejects.toMatchObject({
      name: 'HttpError',
      status: 500,
      code: 'nope',
    });
  });

  test('defaults to the catalog limit of 25', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));

    await searchPoiCatalog({ q: 'x' });

    expect(fetchStub.last.url).toBe('/api/pois/search?q=x&limit=25');
  });
});

describe('POI detail', () => {
  test('requestPoiDetail resolves to the raw Response, even on 404', async () => {
    const fetchStub = stubFetch(jsonResponse({ error: 'not_found' }, 404));

    const response = await requestPoiDetail(42);

    expect(fetchStub.last.url).toBe('/api/pois/42');
    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(404);
  });

  test('fetchPoiDetail throws HttpError on 404', async () => {
    stubFetch(jsonResponse({}, 404));

    await expect(fetchPoiDetail(42)).rejects.toMatchObject({ name: 'HttpError', status: 404 });
  });

  test('fetchPoiDetail returns the parsed body on success', async () => {
    stubFetch(jsonResponse({ id: 42, properties: { name: 'Lassen' } }));

    await expect(fetchPoiDetail(42)).resolves.toEqual({
      id: 42,
      properties: { name: 'Lassen' },
    });
  });

  test('fetchPoiDetail forwards its whole init to fetch', async () => {
    const fetchStub = stubFetch(jsonResponse({}));
    const controller = new AbortController();

    await fetchPoiDetail(42, { signal: controller.signal, cache: 'no-store' });

    expect(fetchStub.last.init.signal).toBe(controller.signal);
    expect(fetchStub.last.init.cache).toBe('no-store');
  });

  test.each([
    [42, '/api/pois/42'],
    ['a/b', '/api/pois/a%2Fb'],
  ])('url-encodes the id %j', async (id, expected) => {
    const fetchStub = stubFetch(jsonResponse({}));

    await requestPoiDetail(id);

    expect(fetchStub.last.url).toBe(expected);
  });
});

describe('POST search paths', () => {
  test('fetchViewportPois posts bbox, zoom, and categories', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));
    const bbox = [
      [-122, 40],
      [-121, 41],
    ];

    await fetchViewportPois({ bbox, zoom: 9, categories: ['campground'] });

    expect(fetchStub.last.url).toBe('/api/pois');
    expect(fetchStub.last.method).toBe('POST');
    expect(fetchStub.last.body).toEqual({ bbox, zoom: 9, categories: ['campground'] });
  });

  // radiusMiles is renamed to the wire's snake_case radius_miles.
  test('fetchOnRoutePois posts waypoints and radius_miles', async () => {
    const fetchStub = stubFetch(jsonResponse({ results: [] }));
    const waypoints = [
      [-122, 40],
      [-121, 41],
    ];

    await fetchOnRoutePois({ waypoints, radiusMiles: 25, categories: ['campground'] });

    expect(fetchStub.last.url).toBe('/api/pois/on-route');
    expect(fetchStub.last.body).toEqual({
      waypoints,
      radius_miles: 25,
      categories: ['campground'],
    });
  });

  test('the POST paths throw HttpError on failure', async () => {
    stubFetch(jsonResponse({ error: 'bad_bbox' }, 400));

    await expect(fetchViewportPois({ bbox: [], zoom: 9 })).rejects.toMatchObject({
      name: 'HttpError',
      code: 'bad_bbox',
    });
  });
});
