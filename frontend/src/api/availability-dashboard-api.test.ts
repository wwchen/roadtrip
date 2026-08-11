import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  forcePoller,
  getChangesSummary,
  getPollersSummary,
  listChangesForCampsite,
  listChangesForPoi,
  listPollers,
  listRuns,
  listRunsForPoller,
} from './availability-dashboard-api';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('listPollers', () => {
  test('omits the query string entirely when unfiltered', async () => {
    const fetchStub = stubFetch(jsonResponse({ pollers: [] }));

    await listPollers();

    expect(fetchStub.last.url).toBe('/api/availability/pollers');
  });

  test('serialises every filter', async () => {
    const fetchStub = stubFetch(jsonResponse({ pollers: [] }));

    await listPollers({ active: true, limit: 50, offset: 100 });

    expect(fetchStub.last.url).toBe('/api/availability/pollers?active=true&limit=50&offset=100');
  });

  test.each([
    [false, '?active=false'],
    ['false', '?active=false'],
    ['', ''],
  ])('treats an active of %j as %j', async (active, query) => {
    const fetchStub = stubFetch(jsonResponse({ pollers: [] }));

    await listPollers({ active });

    expect(fetchStub.last.url).toBe(`/api/availability/pollers${query}`);
  });

  test('keeps a zero offset, which is falsy but meaningful', async () => {
    const fetchStub = stubFetch(jsonResponse({ pollers: [] }));

    await listPollers({ offset: 0, limit: 0 });

    expect(fetchStub.last.url).toBe('/api/availability/pollers?limit=0&offset=0');
  });
});

describe('getPollersSummary', () => {
  test('hits the summary endpoint', async () => {
    const fetchStub = stubFetch(jsonResponse({ active: 3, dormant: 1, due_now: 0, claimed: 0 }));

    const summary = await getPollersSummary();

    expect(fetchStub.last.url).toBe('/api/availability/pollers/summary');
    expect(summary.active).toBe(3);
  });
});

describe('forcePoller', () => {
  test('POSTs to the force endpoint with an empty body', async () => {
    const fetchStub = stubFetch(jsonResponse({ poller_id: 7, next_run_at: '2026-08-08T00:00:00Z' }));

    const result = await forcePoller(7);

    expect(fetchStub.last.url).toBe('/api/availability/pollers/7/force');
    expect(fetchStub.last.method).toBe('POST');
    expect(fetchStub.last.body).toEqual({});
    expect(result).toEqual({
      status: 200,
      ok: true,
      body: { poller_id: 7, next_run_at: '2026-08-08T00:00:00Z' },
    });
  });

  test('reports a 429 cooldown rather than throwing', async () => {
    stubFetch(jsonResponse({ poller_id: 7, retry_after_sec: 45 }, 429));

    const result = await forcePoller(7);

    expect(result.ok).toBe(false);
    expect(result.status).toBe(429);
    expect(result.body).toEqual({ poller_id: 7, retry_after_sec: 45 });
  });

  test('reports a 404 rather than throwing', async () => {
    stubFetch(jsonResponse({ error: 'not_found' }, 404));

    await expect(forcePoller(7)).resolves.toMatchObject({ status: 404, ok: false });
  });

  test('yields a null body when the response is not JSON', async () => {
    stubFetch(textResponse('', 502));

    await expect(forcePoller(7)).resolves.toEqual({ status: 502, ok: false, body: null });
  });

  test('url-encodes the poller id', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await forcePoller('a/b');

    expect(fetchStub.last.url).toBe('/api/availability/pollers/a%2Fb/force');
  });
});

describe('run listing', () => {
  test('listRunsForPoller nests under the poller', async () => {
    const fetchStub = stubFetch(jsonResponse({ runs: [] }));

    await listRunsForPoller(7, { limit: 20 });

    expect(fetchStub.last.url).toBe('/api/availability/pollers/7/runs?limit=20');
  });

  test('listRunsForPoller omits an absent limit', async () => {
    const fetchStub = stubFetch(jsonResponse({ runs: [] }));

    await listRunsForPoller(7);

    expect(fetchStub.last.url).toBe('/api/availability/pollers/7/runs');
  });

  test('listRuns serialises every filter', async () => {
    const fetchStub = stubFetch(jsonResponse({ runs: [] }));

    await listRuns({ status: 'failed', pollerId: 7, since: '2026-08-01', limit: 10 });

    expect(fetchStub.last.url).toBe(
      '/api/availability/runs?status=failed&poller_id=7&since=2026-08-01&limit=10',
    );
  });

  test('listRuns treats an empty pollerId as no filter', async () => {
    const fetchStub = stubFetch(jsonResponse({ runs: [] }));

    await listRuns({ pollerId: '' });

    expect(fetchStub.last.url).toBe('/api/availability/runs');
  });
});

describe('change listing', () => {
  test('listChangesForCampsite filters by campsite_id', async () => {
    const fetchStub = stubFetch(jsonResponse({ changes: [] }));

    await listChangesForCampsite(11, { targetDate: '2026-07-08', limit: 5 });

    expect(fetchStub.last.url).toBe(
      '/api/availability/changes?campsite_id=11&target_date=2026-07-08&limit=5',
    );
  });

  test('listChangesForPoi filters by poi_id', async () => {
    const fetchStub = stubFetch(jsonResponse({ changes: [] }));

    await listChangesForPoi(42);

    expect(fetchStub.last.url).toBe('/api/availability/changes?poi_id=42');
  });

  test('the two wrappers differ only in the filter key', async () => {
    const fetchStub = stubFetch(jsonResponse({ changes: [] }));

    await listChangesForCampsite(1, { limit: 2 });
    await listChangesForPoi(1, { limit: 2 });

    expect(fetchStub.requests.map((r) => r.url)).toEqual([
      '/api/availability/changes?campsite_id=1&limit=2',
      '/api/availability/changes?poi_id=1&limit=2',
    ]);
  });
});

describe('getChangesSummary', () => {
  test('joins explicit dates with commas', async () => {
    const fetchStub = stubFetch(jsonResponse({ poi_id: 42, stats: [] }));

    await getChangesSummary(42, { dates: ['2026-07-08', '2026-07-09'] });

    expect(fetchStub.last.url).toBe(
      '/api/availability/changes/summary?poi_id=42&dates=2026-07-08%2C2026-07-09',
    );
  });

  test.each([[[]], [undefined]])('omits the dates param when %j', async (dates) => {
    const fetchStub = stubFetch(jsonResponse({ poi_id: 42, stats: [] }));

    await getChangesSummary(42, { dates });

    expect(fetchStub.last.url).toBe('/api/availability/changes/summary?poi_id=42');
  });
});
