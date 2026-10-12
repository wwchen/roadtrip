import { describe, expect, test } from 'vitest';
import type { BulkAvailabilityResponseDto, StayAvailabilityStatus } from '@/api/generated/api-types';
import type { StayResult } from '@/stores/stayCheckStore';
import { batchesOf, observedAtOf, rankByStay, resultsOf, stayTallies } from './stay-check';

const summary = (status: StayAvailabilityStatus, openSites = 0): StayResult => ({
  kind: 'summary',
  summary: { status, open_sites: openSites, checked_sites: 10 },
});
const failure: StayResult = { kind: 'error', code: 'rate_limited' };
const card = (id: number) => ({ summary: { id } });

describe('batchesOf', () => {
  test('splits into at most the given number of near-equal runs, in order', () => {
    expect(batchesOf([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 4)).toEqual([[1, 2, 3], [4, 5, 6], [7, 8, 9], [10]]);
  });

  test('fewer ids than batches is one id per batch', () => {
    expect(batchesOf([1, 2], 4)).toEqual([[1], [2]]);
  });

  test('no ids is no batches', () => {
    expect(batchesOf([], 4)).toEqual([]);
  });
});

describe('resultsOf', () => {
  test('keys each entry by its POI, as a summary or an error', () => {
    const response: BulkAvailabilityResponseDto = {
      pois: [
        { poi_id: 21, summary: { status: 'open', open_sites: 8, checked_sites: 40 } },
        { poi_id: 22, error: 'rate_limited' },
      ],
    };

    expect(resultsOf(response)).toEqual({
      21: { kind: 'summary', summary: { status: 'open', open_sites: 8, checked_sites: 40 } },
      22: { kind: 'error', code: 'rate_limited' },
    });
  });
});

describe('observedAtOf', () => {
  test('backs the time off by the stalest cache age in the response', () => {
    const now = Date.parse('2026-10-12T12:00:00Z');
    const response: BulkAvailabilityResponseDto = {
      pois: [
        { poi_id: 1, summary: { status: 'full', open_sites: 0, checked_sites: 3, cache: { hit: true, age_seconds: 60, ttl_seconds: 600 } } },
        { poi_id: 2, summary: { status: 'open', open_sites: 5, checked_sites: 9, cache: { hit: true, age_seconds: 240, ttl_seconds: 600 } } },
        { poi_id: 3, error: 'rate_limited' },
      ],
    };

    expect(observedAtOf(response, now)).toBe('2026-10-12T11:56:00.000Z');
  });

  test('is now when nothing reports an age', () => {
    const now = Date.parse('2026-10-12T12:00:00Z');
    expect(observedAtOf({ pois: [{ poi_id: 1, error: 'not_found' }] }, now)).toBe('2026-10-12T12:00:00.000Z');
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

    expect(stayTallies([1, 2, 3, 4, 5, 6, 7], results)).toEqual({ withSite: 2, full: 1, closed: 1, unknown: 2, answered: 6 });
  });
});
