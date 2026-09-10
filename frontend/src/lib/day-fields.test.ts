import { describe, expect, test } from 'vitest';
import { availableCampsiteIds, availableCount, campsiteCount } from './day-fields';

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
    expect(campsiteCount(day)).toBe(2);
  });

  test('a missing day is empty, not a crash', () => {
    expect(availableCampsiteIds(null)).toEqual([]);
    expect(campsiteCount(undefined)).toBe(0);
  });

  test('a cells value that is not an object is no cells at all', () => {
    // An array is read by `Object.entries` as a map keyed by index, which would
    // invent campsite ids ("0", "1") the backend never sent.
    expect(availableCampsiteIds({ cells: [] as never })).toEqual([]);
    expect(campsiteCount({ cells: [] as never })).toBe(0);

    const asArray = { cells: [{ status: 'available', watchable: false }] as never };
    expect(availableCampsiteIds(asArray)).toEqual([]);
    expect(campsiteCount(asArray)).toBe(0);

    expect(campsiteCount({ cells: 'nonsense' as never })).toBe(0);
  });
});
