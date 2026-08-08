import { describe, expect, test } from 'vitest';
import {
  addLocalDays,
  addLocalMonths,
  localToday,
  localYmd,
  parseLocalYmd,
  sameLocalDay,
  startOfLocalMonth,
} from './local-date';

describe('localYmd', () => {
  test('zero-pads month and day', () => {
    expect(localYmd(new Date(2026, 0, 3))).toBe('2026-01-03');
  });

  test('formats a two-digit month and day unpadded', () => {
    expect(localYmd(new Date(2026, 10, 25))).toBe('2026-11-25');
  });
});

describe('parseLocalYmd', () => {
  test('parses to local midnight, not UTC', () => {
    const d = parseLocalYmd('2026-07-08');
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(6);
    expect(d.getDate()).toBe(8);
    expect(d.getHours()).toBe(0);
  });

  test('round-trips through localYmd', () => {
    expect(localYmd(parseLocalYmd('2026-02-29'))).toBe('2026-03-01'); // 2026 is not a leap year
  });

  test.each([['2026-7-8'], ['not a date'], [''], ['2026-07-08T00:00:00Z']])(
    'returns an Invalid Date for %j',
    (value) => {
      expect(Number.isNaN(parseLocalYmd(value).getTime())).toBe(true);
    },
  );

  test('returns an Invalid Date for null and undefined', () => {
    expect(Number.isNaN(parseLocalYmd(null).getTime())).toBe(true);
    expect(Number.isNaN(parseLocalYmd(undefined).getTime())).toBe(true);
  });
});

describe('addLocalDays', () => {
  test('crosses a month boundary', () => {
    expect(localYmd(addLocalDays(parseLocalYmd('2026-01-30'), 3))).toBe('2026-02-02');
  });

  test('goes backwards across a year boundary', () => {
    expect(localYmd(addLocalDays(parseLocalYmd('2026-01-01'), -1))).toBe('2025-12-31');
  });

  test('does not mutate its argument', () => {
    const start = parseLocalYmd('2026-05-10');
    addLocalDays(start, 5);
    expect(localYmd(start)).toBe('2026-05-10');
  });
});

describe('month helpers', () => {
  test('startOfLocalMonth snaps to the first', () => {
    expect(localYmd(startOfLocalMonth(parseLocalYmd('2026-08-22')))).toBe('2026-08-01');
  });

  test('addLocalMonths rolls over the year and snaps to the first', () => {
    expect(localYmd(addLocalMonths(parseLocalYmd('2026-11-15'), 3))).toBe('2027-02-01');
  });

  test('addLocalMonths goes backwards', () => {
    expect(localYmd(addLocalMonths(parseLocalYmd('2026-01-15'), -2))).toBe('2025-11-01');
  });
});

describe('sameLocalDay', () => {
  test('ignores the time of day', () => {
    expect(sameLocalDay(new Date(2026, 6, 8, 0, 0), new Date(2026, 6, 8, 23, 59))).toBe(true);
  });

  test('distinguishes adjacent days', () => {
    expect(sameLocalDay(new Date(2026, 6, 8), new Date(2026, 6, 9))).toBe(false);
  });
});

describe('localToday', () => {
  test('is local midnight of the current day', () => {
    const today = localToday();
    expect(today.getHours()).toBe(0);
    expect(today.getMinutes()).toBe(0);
    expect(today.getSeconds()).toBe(0);
    expect(today.getMilliseconds()).toBe(0);
    expect(localYmd(today)).toBe(localYmd(new Date()));
  });
});
