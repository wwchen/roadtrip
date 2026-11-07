import { expect, test } from 'vitest';
import { fuseWeek, rollupStatus, weekCache } from './matrixFusion';

const START = '2026-12-01';
const END = '2026-12-03';
const A = 11n;
const B = 12n;
const NOW = new Date('2026-11-05T12:00:00Z');
const TTL = 7200;
const cell = (campsiteId: bigint, date: string, status: 'available' | 'reserved' | 'closed' | 'first_come' | 'unknown', at = NOW) => ({
  campsiteId,
  date,
  status,
  lastObservedAt: at,
});

test.each([
  [['reserved', 'available'], 'available'],
  [['reserved', 'first_come'], 'first_come'],
  [['reserved', 'unknown'], 'unknown'],
  [['reserved', 'closed'], 'reserved'],
  [['closed', 'closed'], 'closed'],
  [[], 'unknown'],
] as const)('rollup of %j is %s', (statuses, expected) => {
  expect(rollupStatus(statuses)).toBe(expected);
});

test('missing cells read as unknown, cells are keyed by campsite id and never watchable', () => {
  const fused = fuseWeek([A, B], [cell(A, START, 'available')], START, END);
  expect(fused.state).toBe('success');
  expect(fused.days).toHaveLength(2);
  expect(fused.days[0]).toEqual({
    date: START,
    status: 'available',
    watchable: false,
    cells: { [String(A)]: { status: 'available', watchable: false }, [String(B)]: { status: 'unknown', watchable: false } },
  });
  expect(fused.days[1]?.status).toBe('unknown');
});

test('every campsite closed every day is closed_for_season with no days', () => {
  const cells = [cell(A, START, 'closed'), cell(A, '2026-12-02', 'closed'), cell(B, START, 'closed'), cell(B, '2026-12-02', 'closed')];
  expect(fuseWeek([A, B], cells, START, END)).toEqual({ state: 'closed_for_season', days: [] });
});

test('closed_for_season is judged over the season campsites only', () => {
  const cells = [cell(A, START, 'closed'), cell(A, '2026-12-02', 'closed')];
  expect(fuseWeek([A, B], cells, START, END).state).toBe('success');
  expect(fuseWeek([A, B], cells, START, END, [A])).toEqual({ state: 'closed_for_season', days: [] });
});

test('a fully observed week reports cache hit with the stalest cell age', () => {
  const stale = new Date(NOW.getTime() - 3_600_000);
  const cells = [cell(A, START, 'available'), cell(A, '2026-12-02', 'available', stale)];
  expect(weekCache([A], cells, START, END, TTL, NOW)).toEqual({ hit: true, age_seconds: 3600, ttl_seconds: TTL });
});

test('a week missing any cell has no cache block', () => {
  expect(weekCache([A, B], [cell(A, START, 'available'), cell(A, '2026-12-02', 'available')], START, END, TTL, NOW)).toBeNull();
});
