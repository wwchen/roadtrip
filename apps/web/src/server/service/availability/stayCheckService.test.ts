import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import type { EgressResult } from '../../client/availabilityEgress';
import type { AvailabilityEgressRequestDto, StayAnswerDto } from '../../contract';
import { ENABLED_PROVIDER, NEAR_CAMPGROUND_ID, NEAR_POI_ID, UNKNOWN_POI_ID, startCatalogFixture, type CatalogFixture } from '../../testing/catalogFixture';
import type { EgressFetch } from './availabilityMatrixService';
import { answerStay, runStayCheck, stayFailureOf, type StayCheckRequest, type StayEvent } from './stayCheckService';

const NOW = new Date('2026-11-07T12:00:00Z');
const START = '2026-12-01';
const END = '2026-12-03';
const NIGHTS = [START, '2026-12-02'];
const REQUEST: StayCheckRequest = { poiIds: [NEAR_POI_ID], startDate: START, endDate: END, siteTypes: [], groupSize: null, maxAgeSeconds: 300 };

let fixture: CatalogFixture;

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.execute(sql`UPDATE campgrounds SET booking_provider = 'campflare', booking_provider_ref = 'near' WHERE id = ${NEAR_CAMPGROUND_ID}`);
});

const ref = (index: number) => ({ data_provider: ENABLED_PROVIDER, data_provider_ref: `site-${index}` });
function answering(openRefs: number[], known: number[] = [0, 1, 2]): EgressFetch & { calls: AvailabilityEgressRequestDto[] } {
  const calls: AvailabilityEgressRequestDto[] = [];
  const fetch = async (request: AvailabilityEgressRequestDto): Promise<EgressResult> => {
    calls.push(request);
    return {
      kind: 'cells',
      body: {
        start_date: START,
        end_date: END,
        campsites: known.map(ref),
        cells: known.flatMap((index) =>
          NIGHTS.map((date) => ({ campsite: ref(index), date, status: openRefs.includes(index) ? 'available' : 'reserved', observed_at: '2026-11-07T11:58:00Z' })),
        ),
        from_cache: true,
      },
    };
  };
  return Object.assign(fetch, { calls });
}
const failing = (status: number, code: string | null): EgressFetch => async () => ({ kind: 'error', status, code, body: null });

test('a POI is answered through a refresh read with the caller freshness cutoff', async () => {
  const egress = answering([0, 1]);
  const answer = await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, egress, NOW);
  expect(answer).toEqual({
    poi_id: Number(NEAR_POI_ID),
    outcome: 'answered',
    stay: { status: 'few_left', open_sites: 2, checked_sites: 3, observed_at: '2026-11-07T11:58:00Z', from_cache: true },
  });
  expect(egress.calls).toEqual([
    { mode: 'refresh', campground: expect.anything(), start_date: START, end_date: END, fresh_at_or_after: '2026-11-07T11:55:00.000Z' },
  ]);
});

test('site type and group size narrow the checked campsites, and campsites mini-ca does not know are left out', async () => {
  const tents = await answerStay(fixture.readDb, NEAR_POI_ID, { ...REQUEST, siteTypes: ['tent'] }, answering([0]), NOW);
  expect(tents.stay).toMatchObject({ open_sites: 1, checked_sites: 2 });
  const groupOfSix = await answerStay(fixture.readDb, NEAR_POI_ID, { ...REQUEST, groupSize: 6 }, answering([0]), NOW);
  expect(groupOfSix.stay).toMatchObject({ status: 'full', open_sites: 0, checked_sites: 2 });
  const unknownToMiniCa = await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, answering([0], [0]), NOW);
  expect(unknownToMiniCa.stay).toMatchObject({ checked_sites: 1 });
});

test('egress failures classify as Kotlin does', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  expect(await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, failing(400, 'window_too_long'), NOW)).toMatchObject({ outcome: 'refused', reason: 'window_too_long' });
  expect(await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, failing(503, 'rate_limited'), NOW)).toMatchObject({ outcome: 'failed', reason: 'rate_limited', retryable: true });
  expect(await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, failing(0, 'timeout'), NOW)).toMatchObject({ outcome: 'timed_out' });
  expect(await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, failing(0, 'unreachable'), NOW)).toMatchObject({ reason: 'upstream_unreachable', retryable: true });
  expect(await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, failing(502, null), NOW)).toMatchObject({ reason: 'unknown', retryable: false });
  vi.restoreAllMocks();
});

test('an unknown POI is not_found, and an unconfigured egress is provider_misconfigured', async () => {
  expect(await answerStay(fixture.readDb, UNKNOWN_POI_ID, REQUEST, answering([]), NOW)).toMatchObject({ outcome: 'failed', reason: 'not_found', retryable: false });
  expect(await answerStay(fixture.readDb, NEAR_POI_ID, REQUEST, null, NOW)).toMatchObject({ outcome: 'failed', reason: 'provider_misconfigured' });
});

test('stayFailureOf maps an unheard-of code to unknown', () => {
  expect(stayFailureOf('brand_new')).toEqual({ outcome: 'failed', reason: 'unknown', retryable: false });
  expect(stayFailureOf(null)).toEqual({ outcome: 'failed', reason: 'unknown', retryable: false });
});

const LIMITS = { fanOut: 2, perPoiTimeoutMs: 1_000, streamTimeoutMs: 5_000 };
const answered = (poiId: bigint): StayAnswerDto => ({ poi_id: Number(poiId), outcome: 'answered' });

test('every POI answers once, then done complete', async () => {
  const events: StayEvent[] = [];
  await runStayCheck([1n, 2n, 3n], async (poiId) => answered(poiId), LIMITS, (event) => events.push(event));
  expect(events.filter((e) => e.event === 'answer').map((e) => e.data)).toEqual([1, 2, 3].map((id) => ({ poi_id: id, outcome: 'answered' })));
  expect(events.at(-1)).toEqual({ event: 'done', data: { outcome: 'complete' } });
});

test('a slow POI times out on its own, and the stream deadline times out the rest', async () => {
  vi.useFakeTimers();
  const events: StayEvent[] = [];
  const never = () => new Promise<StayAnswerDto>(() => {});
  const run = runStayCheck([1n, 2n, 3n], (poiId) => (poiId === 1n ? Promise.resolve(answered(poiId)) : never()), { fanOut: 1, perPoiTimeoutMs: 1_000, streamTimeoutMs: 1_500 }, (event) => events.push(event));
  await vi.advanceTimersByTimeAsync(2_000);
  await run;
  vi.useRealTimers();
  expect(events.map((e) => (e.event === 'answer' ? `${e.data.poi_id}:${e.data.outcome}` : e.data.outcome))).toEqual([
    '1:answered',
    '2:timed_out',
    '3:timed_out',
    'deadline_reached',
  ]);
});

test('a POI whose answer throws fails as unknown instead of breaking the stream', async () => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  const events: StayEvent[] = [];
  await runStayCheck([1n], async () => {
    throw new Error('boom');
  }, LIMITS, (event) => events.push(event));
  vi.restoreAllMocks();
  expect(events[0]).toEqual({ event: 'answer', data: { poi_id: 1, outcome: 'failed', reason: 'unknown', retryable: false } });
});
