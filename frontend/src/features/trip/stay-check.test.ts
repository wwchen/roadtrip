import { describe, expect, test } from 'vitest';
import type { StayAvailabilityStatus } from '@/api/generated/api-types';
import type { StayResult } from '@/stores/stayCheckStore';
import { observedAtOf, rankByStay, resultOf, stayTallies, stayToneOf } from './stay-check';

const summary = (status: StayAvailabilityStatus, openSites = 0): StayResult => ({
  kind: 'summary',
  summary: { status, open_sites: openSites, checked_sites: 10 },
});
const failure: StayResult = { kind: 'error', code: 'rate_limited' };
const card = (id: number) => ({ summary: { id } });

describe('resultOf', () => {
  test('is the answer\'s summary, or its error code', () => {
    expect(resultOf({ poi_id: 21, summary: { status: 'open', open_sites: 8, checked_sites: 40 } })).toEqual({
      kind: 'summary',
      summary: { status: 'open', open_sites: 8, checked_sites: 40 },
    });
    expect(resultOf({ poi_id: 22, error: 'rate_limited' })).toEqual({ kind: 'error', code: 'rate_limited' });
  });

  test('an answer carrying neither is the backend\'s unclassified failure', () => {
    expect(resultOf({ poi_id: 23 })).toEqual({ kind: 'error', code: 'unknown' });
  });
});

describe('observedAtOf', () => {
  const now = Date.parse('2026-10-12T12:00:00Z');

  test('backs the time off by the cache age the answer reports', () => {
    const answer = {
      poi_id: 2,
      summary: { status: 'open' as const, open_sites: 5, checked_sites: 9, cache: { hit: true, age_seconds: 240, ttl_seconds: 600 } },
    };

    expect(observedAtOf(answer, now)).toBe('2026-10-12T11:56:00.000Z');
  });

  test('is now when the answer reports no age', () => {
    expect(observedAtOf({ poi_id: 1, error: 'not_found' }, now)).toBe('2026-10-12T12:00:00.000Z');
  });
});

describe('stayToneOf', () => {
  test('reads each status, with closed for the season as full and a failure as unknown', () => {
    const statuses: StayAvailabilityStatus[] = ['open', 'few_left', 'full', 'closed_for_season', 'no_data'];

    expect(statuses.map((status) => stayToneOf(summary(status)))).toEqual(['open', 'few', 'full', 'full', 'unknown']);
    expect(stayToneOf(failure)).toBe('unknown');
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

    expect(stayTallies([1, 2, 3, 4, 5, 6, 7, 8], { ...results, 8: { kind: 'error', code: 'beyond_booking_horizon' } })).toEqual({
      withSite: 2,
      full: 1,
      closed: 1,
      refused: 1,
      unknown: 2,
      answered: 7,
    });
  });
});
