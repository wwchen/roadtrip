import { describe, expect, test } from 'vitest';
import type { AvailabilityDay } from '@/api/availability-api';
import type { AvailabilityStatus } from '@/lib/availability-status';
import { nightOf, type NightState } from './night-state';
import { dayFull, dayOpen } from './stay-fixtures';

const DATE = '2026-10-25';

function dayWith(status: AvailabilityStatus, cells: readonly AvailabilityStatus[]): AvailabilityDay {
  return {
    date: DATE,
    status,
    watchable: false,
    cells: Object.fromEntries(cells.map((cell, index) => [String(index + 1), { status: cell, watchable: false }])),
  };
}

const EXPECTED = {
  available: 'open',
  reserved: 'full',
  first_come: 'first_come',
  closed: 'closed',
  past: 'past',
  unknown: 'unknown',
} satisfies Record<AvailabilityStatus, NightState>;

describe('nightOf', () => {
  test.each(Object.entries(EXPECTED) as [AvailabilityStatus, NightState][])(
    'a %s day with cells reads %s',
    (status, expected) => {
      expect(nightOf(dayWith(status, [status, status])).state).toBe(expected);
    },
  );

  test('no day for the date is unknown', () => {
    expect(nightOf(undefined)).toEqual({ state: 'unknown', openIds: [] });
  });

  test.each([...Object.keys(EXPECTED)] as AvailabilityStatus[])(
    'a %s rollup with no cells is unknown: the rollup cannot stand in for missing cells',
    (status) => {
      expect(nightOf(dayWith(status, [])).state).toBe('unknown');
    },
  );

  test('a status this build has never heard of is unknown', () => {
    expect(nightOf(dayWith('teleported' as AvailabilityStatus, ['available'])).state).toBe('unknown');
  });

  test('the state comes from the day, the open ids from its cells', () => {
    const day = dayWith('available', ['reserved', 'available', 'closed', 'available']);

    expect(nightOf(day)).toEqual({ state: 'open', openIds: [2, 4] });
  });

  test('open ids sort numerically, not as strings', () => {
    const day: AvailabilityDay = {
      ...dayOpen(DATE, 0),
      status: 'available',
      cells: {
        '100': { status: 'available', watchable: false },
        '9': { status: 'available', watchable: false },
      },
    };

    expect(nightOf(day).openIds).toEqual([9, 100]);
  });

  test('a rollup that says available over cells with none available is unknown, not zero sites open', () => {
    expect(nightOf(dayWith('available', ['reserved', 'reserved']))).toEqual({ state: 'unknown', openIds: [] });
  });

  test('a rollup that says full names no sites even over an available cell', () => {
    expect(nightOf(dayWith('reserved', ['available']))).toEqual({ state: 'full', openIds: [] });
  });

  test('the fixtures agree with their rollups', () => {
    expect(nightOf(dayOpen(DATE, 3))).toEqual({ state: 'open', openIds: [1, 2, 3] });
    expect(nightOf(dayFull(DATE)).state).toBe('full');
    expect(nightOf(dayOpen(DATE, 0)).state).toBe('unknown');
  });
});
