import assert from 'node:assert/strict';
import test from 'node:test';

import { requestPoiCampsitesAvailability } from './api/availability-api.js';
import { fetchPoiCampsites } from './api/reservable-api.js';

test('requestPoiCampsitesAvailability calls the canonical campsite endpoint', async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  const controller = new AbortController();
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return { ok: true };
  };

  try {
    await requestPoiCampsitesAvailability('poi 42', {
      startDate: '2026-07-08',
      endDate: '2026-07-15',
      siteType: 'standard',
      signal: controller.signal,
    });
  } finally {
    globalThis.fetch = previousFetch;
  }

  assert.equal(
    calls[0].url,
    '/api/pois/poi%2042/campsites/availability?start_date=2026-07-08&end_date=2026-07-15&site_type=standard',
  );
  assert.equal(calls[0].options.signal, controller.signal);
});

test('fetchPoiCampsites returns canonical campsite catalog rows', async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return {
      ok: true,
      json: async () => ({ poi_id: 42, campsites: [{ id: 1, rid: 'site:recgov:100' }] }),
    };
  };

  let json;
  try {
    json = await fetchPoiCampsites(42);
  } finally {
    globalThis.fetch = previousFetch;
  }

  assert.equal(calls[0].url, '/api/pois/42/campsites');
  assert.deepEqual(json.campsites, [{ id: 1, rid: 'site:recgov:100' }]);
});
