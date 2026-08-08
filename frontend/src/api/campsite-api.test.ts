// Ports web/campsite-api.test.mjs to Vitest. Covers both clients it exercised —
// the availability window and the canonical campsite catalog — because the pair
// is the contract the Phase-4 drawer reads.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { requestPoiCampsitesAvailability } from './availability-api';
import { fetchPoiCampsites, poiCampsitesUrl } from './campsite-api';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('requestPoiCampsitesAvailability', () => {
  test('calls the canonical campsite endpoint, url-encoding the POI id', async () => {
    const fetchStub = stubFetch(jsonResponse({}));
    const controller = new AbortController();

    await requestPoiCampsitesAvailability('poi 42', {
      startDate: '2026-07-08',
      endDate: '2026-07-15',
      siteType: 'standard',
      signal: controller.signal,
    });

    expect(fetchStub.last.url).toBe(
      '/api/pois/poi%2042/campsites/availability?start_date=2026-07-08&end_date=2026-07-15&site_type=standard',
    );
    expect(fetchStub.last.init.signal).toBe(controller.signal);
  });

  test('omits absent params and the whole query string', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await requestPoiCampsitesAvailability(42);

    expect(fetchStub.last.url).toBe('/api/pois/42/campsites/availability');
  });

  test('includes only the params that were given', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await requestPoiCampsitesAvailability(42, { startDate: '2026-07-08' });

    expect(fetchStub.last.url).toBe('/api/pois/42/campsites/availability?start_date=2026-07-08');
  });

  // Returns the Response, not parsed JSON — the week grid branches on status.
  test('resolves to the raw Response', async () => {
    stubFetch(jsonResponse({ poi_id: 42 }, 404));

    const response = await requestPoiCampsitesAvailability(42);

    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(404);
  });
});

describe('fetchPoiCampsites', () => {
  test('returns canonical campsite catalog rows', async () => {
    const fetchStub = stubFetch(
      jsonResponse({
        poi_id: 42,
        type: 'campground',
        campsites: [{ id: 1, data_provider: 'recgov', data_provider_ref: '100' }],
        reservation_url_templates: { 1: 'https://example.test/{start_date}' },
      }),
    );

    const json = await fetchPoiCampsites(42);

    expect(fetchStub.last.url).toBe('/api/pois/42/campsites');
    expect(json.campsites).toEqual([{ id: 1, data_provider: 'recgov', data_provider_ref: '100' }]);
    expect(json.reservation_url_templates).toEqual({ 1: 'https://example.test/{start_date}' });
  });

  test('throws HttpError on a failed response', async () => {
    stubFetch(jsonResponse({ error: 'not_found' }, 404));

    await expect(fetchPoiCampsites(42)).rejects.toMatchObject({
      name: 'HttpError',
      status: 404,
      code: 'not_found',
    });
  });
});

describe('poiCampsitesUrl', () => {
  test.each([
    [42, '/api/pois/42/campsites'],
    ['poi 42', '/api/pois/poi%2042/campsites'],
    ['a/b', '/api/pois/a%2Fb/campsites'],
  ])('builds %j as %s', (id, expected) => {
    expect(poiCampsitesUrl(id)).toBe(expected);
  });
});
