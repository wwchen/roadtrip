import { describe, expect, test } from 'vitest';
import type { StayAnswer } from '@/api/availability-api';
import type { StayAvailabilityStatus, StayOutcome, StaySummaryDto } from '@/api/generated/api-types';
import type { StayResult } from '@/stores/stayCheckStore';
import { rankByStay, resultOf, stayTallies, stayToneOf } from './stay-check';

const stay = (status: StayAvailabilityStatus, openSites = 0): StaySummaryDto => ({
  status,
  open_sites: openSites,
  checked_sites: 10,
  observed_at: '2026-10-12T11:56:00Z',
  from_cache: true,
});
const summary = (status: StayAvailabilityStatus, openSites = 0): StayResult => ({
  kind: 'summary',
  summary: stay(status, openSites),
});
const failure: StayResult = { kind: 'error', outcome: 'failed', reason: 'rate_limited', retryable: true };
const refusal: StayResult = { kind: 'error', outcome: 'refused', reason: 'beyond_booking_horizon', retryable: null };
const timeout: StayResult = { kind: 'error', outcome: 'timed_out', reason: null, retryable: null };
/** An outcome a newer server might send that this build has never heard of. */
const future: StayResult = { kind: 'error', outcome: 'some_future_outcome' as StayOutcome, reason: null, retryable: null };
const card = (id: number) => ({ summary: { id } });

describe('resultOf', () => {
  test('an answered stay is its summary', () => {
    expect(resultOf({ poi_id: 21, outcome: 'answered', stay: stay('open', 8) })).toEqual({
      kind: 'summary',
      summary: stay('open', 8),
    });
  });

  test('reads a refused answer from its outcome, not from a code list', () => {
    // `window_too_long` was never in the browser's own list of refusals; the server says it is one.
    expect(resultOf({ poi_id: 22, outcome: 'refused', reason: 'window_too_long' })).toEqual({
      kind: 'error',
      outcome: 'refused',
      reason: 'window_too_long',
      retryable: null,
    });
  });

  test('reads retryability from the server rather than inferring it', () => {
    const answer = (retryable: boolean): StayAnswer => ({ poi_id: 1, outcome: 'failed', reason: 'unknown', retryable });

    // `unknown` is not retryable and `rate_limited` is: whichever the server says is what the result carries.
    expect(resultOf(answer(false))).toMatchObject({ retryable: false });
    expect(resultOf(answer(true))).toMatchObject({ retryable: true });
    expect(resultOf({ poi_id: 2, outcome: 'timed_out' })).toEqual({
      kind: 'error',
      outcome: 'timed_out',
      reason: null,
      retryable: null,
    });
  });

  test('an outcome it has never seen is an error result, not a throw', () => {
    expect(resultOf({ poi_id: 1, outcome: 'some_future_outcome' as StayOutcome })).toEqual(future);
  });

  test('an answered outcome without its stay is not a summary', () => {
    expect(resultOf({ poi_id: 1, outcome: 'answered' })).toMatchObject({ kind: 'error', outcome: 'answered' });
  });
});

describe('stayToneOf', () => {
  test('reads each status, with closed for the season as full and a failure as unknown', () => {
    const statuses: StayAvailabilityStatus[] = ['open', 'few_left', 'full', 'closed_for_season', 'no_data'];

    expect(statuses.map((status) => stayToneOf(summary(status)))).toEqual(['open', 'few', 'full', 'full', 'unknown']);
    expect(stayToneOf(failure)).toBe('unknown');
  });
});

describe('an unrecognised outcome or status', () => {
  test('ranks and tones as unknown, behind every known status and ahead of the unasked', () => {
    expect(stayToneOf(future)).toBe('unknown');
    // A status a newer server added: neither lookup may come back undefined.
    const newStatus = summary('brand_new' as StayAvailabilityStatus, 3);
    expect(stayToneOf(newStatus)).toBe('unknown');

    const cards = [card(1), card(2), card(3), card(4), card(5)];
    const results = { 1: future, 2: newStatus, 3: summary('no_data'), 4: summary('open', 1) };

    // 5 was never asked about; 1 and 2 are the unrecognised pair, kept in arrival order.
    expect(rankByStay(cards, results).map((c) => c.summary.id)).toEqual([4, 3, 2, 1, 5]);
  });
});

describe('rankByStay', () => {
  test('orders by status, then most open sites, keeping arrival order for ties and the unasked last', () => {
    const cards = [card(1), card(2), card(3), card(4), card(5), card(6), card(7), card(8)];
    const results: Record<number, StayResult> = {
      1: summary('full'),
      2: summary('open', 4),
      3: failure,
      4: summary('few_left', 2),
      5: summary('open', 9),
      6: summary('no_data'),
      // 7 was never asked about
      8: summary('closed_for_season'),
    };

    expect(rankByStay(cards, results).map((c) => c.summary.id)).toEqual([5, 2, 4, 1, 8, 6, 3, 7]);
  });

  test('equal answers keep the order the cards arrived in', () => {
    const cards = [card(3), card(1), card(2)];
    const results = { 1: summary('full'), 2: summary('full'), 3: summary('full') };

    expect(rankByStay(cards, results).map((c) => c.summary.id)).toEqual([3, 1, 2]);
  });
});

describe('stayTallies', () => {
  // A regression guard, not a test that drove the code: under the old string-matching
  // an unfamiliar value fell to `unknown` too, so this was true before it was written.
  // It pins that forward-compatibility property across the move to `outcome`.
  test('tallies an outcome it has never seen as unknown', () => {
    const results = { 1: future, 2: summary('open', 1), 3: { ...future, outcome: 'answered' as StayOutcome } };

    expect(stayTallies([1, 2, 3], results)).toEqual({
      withSite: 1,
      full: 0,
      closed: 0,
      refused: 0,
      timedOut: 0,
      unknown: 2,
      answered: 3,
    });
  });

  test('counts somewhere to stay, full, closed and unknown over the ids asked about', () => {
    const results: Record<number, StayResult> = {
      1: summary('open', 5),
      2: summary('few_left', 1),
      3: summary('full'),
      4: summary('closed_for_season'),
      5: summary('no_data'),
      6: failure,
      99: summary('open', 3),
    };

    expect(
      stayTallies([1, 2, 3, 4, 5, 6, 7, 8, 9], {
        ...results,
        8: refusal,
        9: timeout,
      }),
    ).toEqual({
      withSite: 2,
      full: 1,
      closed: 1,
      refused: 1,
      timedOut: 1,
      unknown: 2,
      answered: 8,
    });
  });
});
