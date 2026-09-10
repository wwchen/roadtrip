import { describe, expect, test } from 'vitest';
import { availableCampsiteIds, availableCount } from './day-fields';

describe('reading a day row', () => {
  test('counts the cells whose status is available, in wire order', () => {
    const day = {
      cells: {
        '7': { status: 'reserved' as const, watchable: true },
        '3': { status: 'available' as const, watchable: false },
      },
    };

    expect(availableCampsiteIds(day)).toEqual(['3']);
    expect(availableCount(day)).toBe(1);
  });

  test('a missing day is empty, not a crash', () => {
    expect(availableCampsiteIds(null)).toEqual([]);
    expect(availableCount(undefined)).toBe(0);
  });

  test('a cells value that is not an object is no cells at all', () => {
    // An array is read by `Object.entries` as a map keyed by index, which would
    // invent campsite ids ("0", "1") the backend never sent.
    expect(availableCampsiteIds({ cells: [] as never })).toEqual([]);

    const asArray = { cells: [{ status: 'available', watchable: false }] as never };
    expect(availableCampsiteIds(asArray)).toEqual([]);

    expect(availableCampsiteIds({ cells: 'nonsense' as never })).toEqual([]);
  });
});
