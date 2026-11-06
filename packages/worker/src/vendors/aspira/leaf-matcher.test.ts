import { describe, expect, test } from 'vitest';
import { DEFAULT_FUZZY_THRESHOLD, type MatchPolicy } from '../../registry/poi-registry.js';
import type { AspiraLeaf } from './leaf.js';
import { AspiraLeafMatcher } from './leaf-matcher.js';

const BOOKABLE = 9001n;
const NON_BOOKABLE = 9002n;
const STRICT_THRESHOLD = 0.9;
const TIED_SCORE = 0.5;
const BANFF: readonly [number, number] = [51.18, -115.57];
const TWO_JACK: readonly [number, number] = [51.22, -115.49];
const ALPHA: readonly [number, number] = [1, 2];
const BETA: readonly [number, number] = [3, 4];

const PARKS = new Map<string, readonly [number, number]>([
  ['banff', BANFF],
  ['two jack lakeside', TWO_JACK],
]);

const TWO_LAKES = new Map<string, readonly [number, number]>([
  ['alpha lake', ALPHA],
  ['beta lake', BETA],
]);

function policyOf(parentFallback: boolean, fuzzyThreshold = DEFAULT_FUZZY_THRESHOLD): MatchPolicy {
  return { fuzzyThreshold, parentFallback };
}

function matcher(
  policy: MatchPolicy = policyOf(true),
  byName: ReadonlyMap<string, readonly [number, number]> = PARKS,
): AspiraLeafMatcher<readonly [number, number]> {
  return new AspiraLeafMatcher(byName, new Set([NON_BOOKABLE]), policy);
}

function leaf(name: string, resourceLocationId: bigint | null = BOOKABLE, parentName: string | null = null): AspiraLeaf {
  return { name, transactionLocationId: 1n, mapId: 2n, resourceLocationId, parentName };
}

describe('the leaf matcher ladder', () => {
  test('an exact normalized name wins and names the index key it hit', () => {
    expect(matcher().match(leaf('Two Jack Lakeside Campground'))).toEqual({
      leaf: leaf('Two Jack Lakeside Campground'),
      value: TWO_JACK,
      kind: 'exact',
      matchedName: 'two jack lakeside',
      score: null,
    });
  });

  test('token overlap exactly at the threshold is a fuzzy match carrying its score', () => {
    const match = matcher().match(leaf('Two Jack Lake'));
    expect(match?.kind).toBe('fuzzy');
    expect(match?.value).toEqual(TWO_JACK);
    expect(match?.matchedName).toBe('two jack lakeside');
    expect(match?.score).toBe(DEFAULT_FUZZY_THRESHOLD);
  });

  test('the threshold is inclusive: nudging it above that same overlap rejects the match', () => {
    const justAbove = DEFAULT_FUZZY_THRESHOLD + Number.EPSILON;
    expect(matcher(policyOf(false, justAbove)).match(leaf('Two Jack Lake'))).toBeNull();
    expect(matcher(policyOf(false, DEFAULT_FUZZY_THRESHOLD)).match(leaf('Two Jack Lake'))?.kind).toBe('fuzzy');
  });

  test('a threshold above the best overlap rejects the fuzzy match', () => {
    expect(matcher(policyOf(false, STRICT_THRESHOLD)).match(leaf('Two Jack Lake'))).toBeNull();
  });

  test('the parent park name backstops a leaf with no geometry of its own', () => {
    const match = matcher().match(leaf('Backcountry Site', BOOKABLE, 'Banff National Park of Canada'));
    expect(match?.kind).toBe('parent');
    expect(match?.value).toEqual(BANFF);
    expect(match?.matchedName).toBe('banff');
    expect(match?.score).toBeNull();
  });

  test('parent fallback off leaves that same leaf unmatched', () => {
    expect(matcher(policyOf(false)).match(leaf('Backcountry Site', BOOKABLE, 'Banff National Park of Canada'))).toBeNull();
  });

  test('a leaf matching neither itself nor its parent is unmatched', () => {
    expect(matcher().match(leaf('Nowhere Site', BOOKABLE, 'Elsewhere'))).toBeNull();
  });

  test('a tie keeps the first entry in index order, not the last', () => {
    const match = matcher(policyOf(true), TWO_LAKES).match(leaf('Lake'));
    expect(match?.matchedName).toBe('alpha lake');
    expect(match?.value).toEqual(ALPHA);
    expect(match?.score).toBe(TIED_SCORE);
  });

  test('reversing the index order reverses which tied entry wins, so the rule is index order', () => {
    const reversed = new Map([...TWO_LAKES].reverse());
    const match = matcher(policyOf(true), reversed).match(leaf('Lake'));
    expect(match?.matchedName).toBe('beta lake');
    expect(match?.value).toEqual(BETA);
  });

  test('a strictly better entry later in index order still wins, so the rule is not merely first', () => {
    const match = matcher(policyOf(true), TWO_LAKES).match(leaf('Beta Lake Extra'));
    expect(match?.matchedName).toBe('beta lake');
    expect(match?.score).toBe(2 / 3);
  });

  test('the reported score is the overlap that won, not the threshold that admitted it', () => {
    const match = matcher(policyOf(true), TWO_LAKES).match(leaf('Alpha Lake Extra'));
    expect(match?.matchedName).toBe('alpha lake');
    expect(match?.score).toBe(2 / 3);
  });
});

describe('matchBookable, which applies the two leaf gates before any lookup', () => {
  test('a leaf with no resourceLocationId is a park container and never reaches the index', () => {
    const { matches, tally } = matcher().matchBookable([leaf('Banff', null)]);
    expect(matches).toEqual([]);
    expect(tally.skippedContainer).toBe(1);
    expect([tally.exact, tally.miss, tally.skippedNonBookable]).toEqual([0, 0, 0]);
  });

  test('a leaf whose resourceLocationId holds only non-bookable inventory is an activity mount', () => {
    const { matches, tally } = matcher().matchBookable([leaf('Two Jack Lakeside', NON_BOOKABLE)]);
    expect(matches).toEqual([]);
    expect(tally.skippedNonBookable).toBe(1);
    expect([tally.exact, tally.miss, tally.skippedContainer]).toEqual([0, 0, 0]);
  });

  test('the same two leaves do match once the gates no longer apply to them', () => {
    const { matches, tally } = matcher().matchBookable([leaf('Banff', BOOKABLE), leaf('Two Jack Lakeside', BOOKABLE)]);
    expect(matches.map((match) => match.matchedName)).toEqual(['banff', 'two jack lakeside']);
    expect([tally.skippedContainer, tally.skippedNonBookable]).toEqual([0, 0]);
  });

  test('the tally counts every kind and samples at most five misses, in leaf order', () => {
    const leaves = [
      leaf('Banff', null),
      leaf('Two Jack Lakeside', NON_BOOKABLE),
      leaf('Two Jack Lakeside'),
      leaf('Two Jack Lake'),
      leaf('Backcountry Site', BOOKABLE, 'Banff'),
      ...Array.from({ length: 7 }, (_, index) => leaf(`Miss ${index + 1}`)),
    ];

    const { matches, tally } = matcher().matchBookable(leaves);

    expect(matches.map((match) => match.leaf.name)).toEqual(['Two Jack Lakeside', 'Two Jack Lake', 'Backcountry Site']);
    expect(tally).toEqual({
      exact: 1,
      fuzzy: 1,
      parent: 1,
      miss: 7,
      skippedContainer: 1,
      skippedNonBookable: 1,
      missSamples: ['Miss 1', 'Miss 2', 'Miss 3', 'Miss 4', 'Miss 5'],
    });
  });
});
