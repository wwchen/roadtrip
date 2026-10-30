import { describe, expect, test } from 'vitest';
import type { AvailabilityDay } from '@/api/availability-api';
import type { AvailabilityStatus } from '@/lib/availability-status';
import { nightOf, type NightState } from './night-state';
import { dayFull, dayOpen } from './stay-fixtures';

const DATE = '2026-10-25';

const IGNORED_ROLLUP: AvailabilityStatus = 'unknown';

function dayWith(status: AvailabilityStatus, cells: readonly AvailabilityStatus[]): AvailabilityDay {
  return {
    date: DATE,
    status,
    watchable: false,
    cells: Object.fromEntries(cells.map((cell, index) => [String(index + 1), { status: cell, watchable: false }])),
  };
}

const repeat = (status: AvailabilityStatus, times: number): AvailabilityStatus[] =>
  Array.from({ length: times }, () => status);

const UPPER_PINES_RESERVED = 235;
const UPPER_PINES_UNREAD = 2;

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
    'a night whose cells all read %s is %s',
    (status, expected) => {
      expect(nightOf(dayWith(IGNORED_ROLLUP, [status, status])).state).toBe(expected);
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

  test('a cell status this build has never heard of counts as unread', () => {
    expect(nightOf(dayWith(IGNORED_ROLLUP, ['teleported' as AvailabilityStatus])).state).toBe('unknown');
  });

  test('the night comes off its cells, state and open ids alike', () => {
    const day = dayWith(IGNORED_ROLLUP, ['reserved', 'available', 'closed', 'available']);

    expect(nightOf(day)).toEqual({ state: 'open', openIds: [2, 4] });
  });

  test('open ids sort numerically, not as strings', () => {
    const day: AvailabilityDay = {
      ...dayOpen(DATE, 0),
      cells: {
        '100': { status: 'available', watchable: false },
        '9': { status: 'available', watchable: false },
      },
    };

    expect(nightOf(day).openIds).toEqual([9, 100]);
  });

  test('a night taken but for a couple of unread sites is full, not No data', () => {
    const day = dayWith(IGNORED_ROLLUP, [
      ...repeat('reserved', UPPER_PINES_RESERVED),
      ...repeat('unknown', UPPER_PINES_UNREAD),
    ]);

    expect(nightOf(day)).toEqual({ state: 'full', openIds: [] });
  });

  test('a night where most sites went unread says nothing rather than full', () => {
    const day = dayWith(IGNORED_ROLLUP, [
      ...repeat('reserved', UPPER_PINES_UNREAD),
      ...repeat('unknown', UPPER_PINES_RESERVED),
    ]);

    expect(nightOf(day).state).toBe('unknown');
  });

  test('one open site stands on its own, however little else was read', () => {
    const day = dayWith(IGNORED_ROLLUP, ['available', ...repeat('unknown', UPPER_PINES_RESERVED)]);

    expect(nightOf(day)).toEqual({ state: 'open', openIds: [1] });
  });

  test('a first-come site stands on its own too, since turning up is an answer', () => {
    const day = dayWith(IGNORED_ROLLUP, ['first_come', ...repeat('unknown', UPPER_PINES_RESERVED)]);

    expect(nightOf(day)).toEqual({ state: 'first_come', openIds: [] });
  });

  test('the most permissive cell decides, so some sites closed and the rest taken is full', () => {
    expect(nightOf(dayWith(IGNORED_ROLLUP, ['closed', 'reserved', 'closed'])).state).toBe('full');
  });

  test('the cells overrule a rollup that disagrees with them', () => {
    expect(nightOf(dayWith('reserved', ['available']))).toEqual({ state: 'open', openIds: [1] });
    expect(nightOf(dayWith('available', ['reserved', 'reserved']))).toEqual({ state: 'full', openIds: [] });
  });

  test('the fixtures agree with their cells', () => {
    expect(nightOf(dayOpen(DATE, 3))).toEqual({ state: 'open', openIds: [1, 2, 3] });
    expect(nightOf(dayFull(DATE)).state).toBe('full');
    expect(nightOf(dayOpen(DATE, 0)).state).toBe('unknown');
  });
});
