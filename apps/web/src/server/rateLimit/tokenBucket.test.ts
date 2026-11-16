import { expect, test } from 'vitest';
import { tokenBucket } from './tokenBucket';

const PER_MINUTE = 6;
const MS_PER_TOKEN = 10_000;
const MS_PER_MINUTE = 60_000;
const IDLE_MS = 5 * MS_PER_MINUTE;
const MAX_CALLERS = 10_000;

function clock(start = 0): { now: () => number; advance: (ms: number) => void } {
  let at = start;
  return { now: () => at, advance: (ms) => (at += ms) };
}

test('a caller spends a full minute of calls at once, then waits for the refill', () => {
  const time = clock();
  const limit = tokenBucket(PER_MINUTE, time.now);
  for (let call = 0; call < PER_MINUTE; call += 1) expect(limit.allow('a')).toBe(true);
  expect(limit.allow('a')).toBe(false);
  time.advance(MS_PER_TOKEN);
  expect(limit.allow('a')).toBe(true);
  expect(limit.allow('a')).toBe(false);
});

test('each caller has its own bucket', () => {
  const limit = tokenBucket(1, clock().now);
  expect(limit.allow('a')).toBe(true);
  expect(limit.allow('a')).toBe(false);
  expect(limit.allow('b')).toBe(true);
});

test('a call can cost several tokens, and a refused one spends nothing', () => {
  const limit = tokenBucket(PER_MINUTE, clock().now);
  expect(limit.allow('a', 4)).toBe(true);
  expect(limit.allow('a', 4)).toBe(false);
  expect(limit.allow('a', 2)).toBe(true);
});

test('a cost above the bucket, or below one, is refused rather than thrown', () => {
  const limit = tokenBucket(PER_MINUTE, clock().now);
  expect(limit.allow('a', PER_MINUTE + 1)).toBe(false);
  expect(limit.allow('a', 0)).toBe(false);
  expect(limit.allow('a', PER_MINUTE)).toBe(true);
});

test('the refill never exceeds one minute of calls', () => {
  const time = clock();
  const limit = tokenBucket(PER_MINUTE, time.now);
  expect(limit.allow('a', PER_MINUTE)).toBe(true);
  time.advance(10 * MS_PER_MINUTE);
  expect(limit.allow('a', PER_MINUTE)).toBe(true);
  expect(limit.allow('a')).toBe(false);
});

test('an idle caller is forgotten, which is the same as a full bucket', () => {
  const time = clock();
  const limit = tokenBucket(PER_MINUTE, time.now);
  expect(limit.allow('a', PER_MINUTE)).toBe(true);
  time.advance(IDLE_MS);
  expect(limit.allow('b')).toBe(true);
  expect(limit.allow('a', PER_MINUTE)).toBe(true);
});

test('a flood of distinct callers cannot grow the map without bound', () => {
  const limit = tokenBucket(1, clock().now);
  expect(limit.allow('first')).toBe(true);
  for (let caller = 0; caller <= MAX_CALLERS; caller += 1) limit.allow(`flood-${caller}`);
  expect(limit.allow('first')).toBe(true);
});
