import { afterEach, describe, expect, test, vi } from 'vitest';
import { API_ENDPOINTS } from './generated/api-types';
import { HttpError } from './http';
import { STAY_ANSWER_EVENT, STAY_DONE_EVENT, STAY_STREAM_URL, streamStays, type StayStreamEvent } from './availability-api';
import { eventStreamResponse, jsonResponse, stubFetch } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

const STAY = { poiIds: [21, 22], start: '2026-09-11', end: '2026-09-13', siteType: null, groupSize: null, maxAgeSeconds: null };

async function eventsOf(stream: AsyncGenerator<StayStreamEvent>): Promise<StayStreamEvent[]> {
  const events: StayStreamEvent[] = [];
  for await (const event of stream) events.push(event);
  return events;
}

describe('streamStays', () => {
  test('posts the stay to the stream route, asking for events, and yields each answer then done', async () => {
    const fetchStub = stubFetch(
      eventStreamResponse([
        { event: STAY_ANSWER_EVENT, data: { poi_id: 22, outcome: 'failed', reason: 'rate_limited', retryable: true } },
        { event: STAY_ANSWER_EVENT, data: { poi_id: 21, outcome: 'answered', stay: { status: 'open', open_sites: 8, checked_sites: 40, from_cache: false } } },
        { event: STAY_DONE_EVENT, data: { outcome: 'complete' } },
      ]),
    );

    const events = await eventsOf(streamStays({ ...STAY, siteType: 'tent', groupSize: 4, maxAgeSeconds: 120 }));

    expect(fetchStub.last.url).toBe(STAY_STREAM_URL);
    expect(fetchStub.last.method).toBe('POST');
    expect(new Headers(fetchStub.last.init.headers).get('Accept')).toContain('text/event-stream');
    expect(fetchStub.last.body).toEqual({
      poi_ids: [21, 22],
      start_date: '2026-09-11',
      end_date: '2026-09-13',
      site_type: ['tent'],
      group_size: 4,
      max_age_seconds: 120,
    });
    expect(events).toEqual([
      { kind: 'answer', answer: { poi_id: 22, outcome: 'failed', reason: 'rate_limited', retryable: true } },
      { kind: 'answer', answer: { poi_id: 21, outcome: 'answered', stay: { status: 'open', open_sites: 8, checked_sites: 40, from_cache: false } } },
      { kind: 'done', done: { outcome: 'complete' } },
    ]);
  });

  test('leaves the optional fields out when the stay does not set them', async () => {
    const fetchStub = stubFetch(eventStreamResponse([{ event: STAY_DONE_EVENT, data: { outcome: 'complete' } }]));

    await eventsOf(streamStays(STAY));

    expect(fetchStub.last.body).toEqual({ poi_ids: [21, 22], start_date: '2026-09-11', end_date: '2026-09-13', site_type: [] });
  });

  test('skips an event it does not know, so the stream can grow one', async () => {
    stubFetch(
      eventStreamResponse([
        { event: 'progress', data: { percent: 50 } },
        { event: STAY_DONE_EVENT, data: { outcome: 'complete' } },
      ]),
    );

    expect(await eventsOf(streamStays(STAY))).toEqual([{ kind: 'done', done: { outcome: 'complete' } }]);
  });

  test('a refusal throws before any event, carrying its code', async () => {
    stubFetch(jsonResponse({ error: 'ip_throttled' }, 503));

    const error = await eventsOf(streamStays(STAY)).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(HttpError);
    expect(error).toMatchObject({ status: 503, code: 'ip_throttled' });
  });

  test('reads the events its contract row declares, by the names it declares them', () => {
    const row = API_ENDPOINTS.find((endpoint) => endpoint.path === STAY_STREAM_URL);

    expect(row && 'events' in row.success ? Object.keys(row.success.events).sort() : null).toEqual(
      [STAY_ANSWER_EVENT, STAY_DONE_EVENT].sort(),
    );
  });
});
