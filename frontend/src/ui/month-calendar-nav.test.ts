import { describe, expect, test } from 'vitest';
import { inBounds, keyTarget, tabStop } from './month-calendar-nav';

const SEPTEMBER = new Date(2026, 8, 1);

describe('keyTarget', () => {
  test('arrows move by a day and a week', () => {
    expect(keyTarget('2026-09-30', 'ArrowRight', false)).toBe('2026-10-01');
    expect(keyTarget('2026-09-01', 'ArrowLeft', false)).toBe('2026-08-31');
    expect(keyTarget('2026-09-11', 'ArrowUp', false)).toBe('2026-09-04');
    expect(keyTarget('2026-09-11', 'ArrowDown', false)).toBe('2026-09-18');
  });

  test('Home and End go to the ends of the week, Sunday first', () => {
    // Friday the 11th sits in the week of Sunday the 6th.
    expect(keyTarget('2026-09-11', 'Home', false)).toBe('2026-09-06');
    expect(keyTarget('2026-09-11', 'End', false)).toBe('2026-09-12');
  });

  test('PageUp and PageDown move a month, and a year with Shift', () => {
    expect(keyTarget('2026-09-11', 'PageDown', false)).toBe('2026-10-11');
    expect(keyTarget('2026-09-11', 'PageUp', false)).toBe('2026-08-11');
    expect(keyTarget('2026-09-11', 'PageDown', true)).toBe('2027-09-11');
    expect(keyTarget('2026-09-11', 'PageUp', true)).toBe('2025-09-11');
  });

  test('a month move clamps to the shorter month instead of rolling over', () => {
    expect(keyTarget('2026-01-31', 'PageDown', false)).toBe('2026-02-28');
  });

  test('other keys are not calendar moves', () => {
    expect(keyTarget('2026-09-11', 'Enter', false)).toBeNull();
    expect(keyTarget('2026-09-11', 'a', false)).toBeNull();
  });
});

describe('inBounds', () => {
  test('both bounds are inclusive, and a null bound is open', () => {
    expect(inBounds('2026-09-11', '2026-09-11', '2026-09-11')).toBe(true);
    expect(inBounds('2026-09-10', '2026-09-11', null)).toBe(false);
    expect(inBounds('2026-09-12', null, '2026-09-11')).toBe(false);
    expect(inBounds('1999-01-01', null, null)).toBe(true);
  });
});

describe('tabStop', () => {
  test('takes the first preferred day that is in the month and pickable', () => {
    expect(tabStop(SEPTEMBER, [null, '2026-10-02', '2026-09-14'], null, null)).toBe('2026-09-14');
    expect(tabStop(SEPTEMBER, ['2026-09-03', '2026-09-14'], '2026-09-05', null)).toBe('2026-09-14');
  });

  test('falls back to the first pickable day of the month', () => {
    expect(tabStop(SEPTEMBER, [], null, null)).toBe('2026-09-01');
    expect(tabStop(SEPTEMBER, [], '2026-09-12', null)).toBe('2026-09-12');
  });

  test('is null when nothing in the month can be picked', () => {
    expect(tabStop(SEPTEMBER, [], '2026-10-01', null)).toBeNull();
  });
});
