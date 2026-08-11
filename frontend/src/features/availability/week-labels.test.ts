import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import {
  dayOfMonthLabel,
  dowLabel,
  formatWeekLabel,
  longDayLabel,
} from './week-labels';

const ORIGINAL_TZ = process.env.TZ;

beforeAll(() => {
  process.env.TZ = 'America/Los_Angeles';
});
afterAll(() => {
  process.env.TZ = ORIGINAL_TZ;
});

test('the test zone actually shifts a bare ISO date', () => {
  const midnightUtc = new Date('2026-08-11T00:00:00Z');

  expect(midnightUtc.toLocaleDateString('en-US', { weekday: 'short' })).toBe('Mon');
  expect(longDayLabel('2026-08-11')).toBe('Tue, Aug 11');
});

describe('column headers', () => {
  test('name the weekday of the date itself, not of local midnight', () => {
    // 2026-08-11 is a Tuesday. Parsed locally it is Monday evening.
    expect(dowLabel('2026-08-11')).toBe('Tue');
  });

  test('cover the whole week', () => {
    const week = ['2026-08-09', '2026-08-10', '2026-08-11', '2026-08-12', '2026-08-13', '2026-08-14', '2026-08-15'];

    expect(week.map(dowLabel)).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']);
  });

  test('show the day of month with no leading zero', () => {
    expect(dayOfMonthLabel('2026-08-01')).toBe('1');
    expect(dayOfMonthLabel('2026-08-31')).toBe('31');
  });

  test('fall back to the raw value', () => {
    expect(dayOfMonthLabel('not-a-date')).toBe('not-a-date');
  });
});

describe('the long day label', () => {
  test('reads as the date it is', () => {
    expect(longDayLabel('2026-08-11')).toBe('Tue, Aug 11');
  });

  test('does not slip a day across the new year', () => {
    expect(longDayLabel('2027-01-01')).toBe('Fri, Jan 1');
  });
});

describe('the week label', () => {
  test('collapses the month within one month', () => {
    expect(formatWeekLabel('2026-08-10', '2026-08-16')).toBe('Aug 10 – 16, 2026');
  });

  test('names both months across a boundary', () => {
    expect(formatWeekLabel('2026-08-30', '2026-09-05')).toBe('Aug 30 – Sep 5, 2026');
  });

  test('spans a year boundary on the start year', () => {
    expect(formatWeekLabel('2026-12-28', '2027-01-03')).toBe('Dec 28 – Jan 3, 2026');
  });

  test('a lone date labels itself', () => {
    expect(formatWeekLabel('2026-08-10')).toBe('Aug 10 – 10, 2026');
  });

  test('an empty start is an empty label, not "Invalid Date"', () => {
    expect(formatWeekLabel('')).toBe('');
    expect(formatWeekLabel('nonsense')).toBe('nonsense');
  });
});
