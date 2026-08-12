import { describe, expect, test } from 'vitest';
import type { TripStop } from '@/stores/tripStore';
import {
  addEmptyStop,
  addExternalStop,
  allStopsFilled,
  enterDirections,
  isLocated,
  markerLabel,
  removeStopAt,
  reorderStops,
  stopLabel,
  stopPlaceholder,
  stopRole,
  withStopAt,
} from './stops';

const stop = (name: string, lng = -122, lat = 47): TripStop => ({ name, lng, lat, kind: 'PLACE' });
const locating = (): TripStop => ({ name: 'Locating you…', lng: 0, lat: 0, pending: true });

const A = stop('Seattle');
const B = stop('Bowman Bay', -122.65, 48.41);
const C = stop('Bellingham', -122.48, 48.75);

describe('isLocated', () => {
  test('a filled stop is located', () => {
    expect(isLocated(A)).toBe(true);
  });

  test('an empty slot is not', () => {
    expect(isLocated(null)).toBe(false);
  });

  test('a pending placeholder is not, despite having coordinates', () => {
    expect(isLocated(locating())).toBe(false);
  });
});

describe('allStopsFilled', () => {
  test('needs two ends', () => {
    expect(allStopsFilled([A])).toBe(false);
    expect(allStopsFilled([A, B])).toBe(true);
  });

  test('is false while a row is still locating', () => {
    expect(allStopsFilled([locating(), B])).toBe(false);
  });

  test('is false with an empty via', () => {
    expect(allStopsFilled([A, null, B])).toBe(false);
  });
});

describe('roles and labels', () => {
  test('first is the origin, last the destination, the rest vias', () => {
    expect(stopRole(0, 3)).toBe('origin');
    expect(stopRole(1, 3)).toBe('via');
    expect(stopRole(2, 3)).toBe('destination');
  });

  test('browse mode names its one row a search box', () => {
    expect(stopPlaceholder(0, 1, 'browse')).toBe('Search a place or pin…');
    expect(stopPlaceholder(0, 2, 'directions')).toBe('Origin');
    expect(stopPlaceholder(1, 3, 'directions')).toBe('Stop 1');
    expect(stopPlaceholder(2, 3, 'directions')).toBe('Destination');
  });

  test('marker labels read as ends with numbered vias between', () => {
    expect([0, 1, 2].map((i) => markerLabel(i, 3))).toEqual(['A', '1', 'C']);
    expect(markerLabel(1, 2)).toBe('B');
  });

  test('the destination letter stops at Z', () => {
    expect(markerLabel(29, 30)).toBe('Z');
  });

  test('a stop label is its first word', () => {
    expect(stopLabel([stop('Bowman Bay Campground, WA')], 0)).toBe('Bowman');
  });

  test('an empty slot labels by position', () => {
    expect(stopLabel([null, B], 0)).toBe('Stop 1');
  });

  test('a single runaway word is truncated', () => {
    expect(stopLabel([stop('Llanfairpwllgwyngyllgogerychwyrn')], 0)).toBe('Llanfairpwllgwyn…');
  });
});

describe('withStopAt', () => {
  test('extends the list to reach the index', () => {
    expect(withStopAt([], 2, A)).toEqual([null, null, A]);
  });

  test('does not mutate its input', () => {
    const before: (TripStop | null)[] = [A];
    withStopAt(before, 0, B);
    expect(before).toEqual([A]);
  });
});

describe('entering directions', () => {
  test('keeps a filled search row as the origin and focuses the destination', () => {
    expect(enterDirections([A])).toEqual({
      stops: [A, null],
      mode: 'directions',
      focusRow: 1,
    });
  });

  test('focuses the origin when nothing is filled', () => {
    expect(enterDirections([]).focusRow).toBe(0);
  });

  test('leaves a complete trip alone, with nothing to focus', () => {
    const result = enterDirections([A, B]);

    expect(allStopsFilled(result.stops)).toBe(true);
    expect(result.focusRow).toBeNull();
  });
});

describe('adding an empty stop', () => {
  test('appends and focuses the new row', () => {
    expect(addEmptyStop([A, B], 'directions')).toEqual({
      stops: [A, B, null],
      mode: 'directions',
      focusRow: 2,
    });
  });

  test('refuses past the cap', () => {
    const full = Array.from({ length: 25 }, (_, i) => stop(`Stop ${i}`));

    expect(addEmptyStop(full, 'directions').stops).toHaveLength(25);
    expect(addEmptyStop(full, 'directions').focusRow).toBeNull();
  });
});

describe('removing a stop', () => {
  test('clears a filled destination in place, and focuses it', () => {
    expect(removeStopAt([A, B], 1, 'directions')).toEqual({
      stops: [A, null],
      mode: 'directions',
      focusRow: 1,
    });
  });

  test('clears a filled origin in place', () => {
    expect(removeStopAt([A, B], 0, 'directions').stops).toEqual([null, B]);
  });

  test('removes a via outright', () => {
    expect(removeStopAt([A, C, B], 1, 'directions').stops).toEqual([A, B]);
  });

  test('leaves a complete trip complete after removing a via', () => {
    expect(allStopsFilled(removeStopAt([A, C, B], 1, 'directions').stops)).toBe(true);
  });

  test('leaves an incomplete trip incomplete', () => {
    expect(allStopsFilled(removeStopAt([A, C, null], 1, 'directions').stops)).toBe(false);
  });

  test('clears browse mode"s search row in place', () => {
    expect(removeStopAt([A], 0, 'browse')).toEqual({
      stops: [null],
      mode: 'browse',
      focusRow: 0,
    });
  });

  test('removing an already-empty browse row empties the trip', () => {
    expect(removeStopAt([null], 0, 'browse')).toEqual({
      stops: [],
      mode: 'browse',
      focusRow: null,
    });
  });

  test('falls back to browse mode when only one stop would remain', () => {
    expect(removeStopAt([A, null], 1, 'browse')).toEqual({
      stops: [A],
      mode: 'browse',
      focusRow: null,
    });
  });

  test('an empty endpoint in a two-row trip changes nothing', () => {
    expect(removeStopAt([null, B], 0, 'directions')).toEqual({
      stops: [null, B],
      mode: 'directions',
      focusRow: null,
    });
  });
});

describe('reordering', () => {
  test('moves a row down past its neighbour', () => {
    expect(reorderStops([A, B, C], 0, 1).stops).toEqual([B, A, C]);
  });

  test('moves a row up', () => {
    expect(reorderStops([A, B, C], 2, 0).stops).toEqual([C, A, B]);
  });

  test('a reordered complete trip is still complete', () => {
    expect(allStopsFilled(reorderStops([A, B, C], 0, 2).stops)).toBe(true);
    expect(allStopsFilled(reorderStops([A, null, C], 0, 2).stops)).toBe(false);
  });

  test('a drop on itself is a no-op', () => {
    const result = reorderStops([A, B], 1, 1);

    expect(result.stops).toEqual([A, B]);
    expect(result.focusRow).toBeNull();
  });

  test('ignores an out-of-range or non-numeric source', () => {
    expect(reorderStops([A, B], 5, 0).stops).toEqual([A, B]);
    expect(reorderStops([A, B], Number.NaN, 0).stops).toEqual([A, B]);
  });

  test('a drop past the end appends', () => {
    expect(reorderStops([A, B, C], 0, 9).stops).toEqual([B, C, A]);
  });
});

describe('a stop added from the drawer', () => {
  test('browse mode becomes a two-row trip with the POI as destination', () => {
    expect(addExternalStop([A], 'browse', B, { autoFocusOrigin: true })).toEqual({
      stops: [null, B],
      mode: 'directions',
      focusRow: 0,
      fillOrigin: false,
    });
  });

  test('on a phone it asks for the origin instead of focusing it', () => {
    const result = addExternalStop([A], 'browse', B, { autoFocusOrigin: false });

    expect(result.focusRow).toBeNull();
    expect(result.fillOrigin).toBe(true);
  });

  test('fills an empty destination in directions mode', () => {
    const result = addExternalStop([A, null], 'directions', B, { autoFocusOrigin: true });

    // Both ends are now filled, so the route fires without another action — the
    // stops are the query key.
    expect(result.stops).toEqual([A, B]);
    expect(allStopsFilled(result.stops)).toBe(true);
  });

  test('inserts before the destination when that slot is taken', () => {
    const result = addExternalStop([A, B], 'directions', C, { autoFocusOrigin: true });

    expect(result.stops).toEqual([A, C, B]);
    expect(allStopsFilled(result.stops)).toBe(true);
  });

  test('refuses past the cap', () => {
    const full = Array.from({ length: 25 }, (_, i) => stop(`Stop ${i}`));

    expect(addExternalStop(full, 'directions', C, { autoFocusOrigin: true }).stops).toHaveLength(25);
  });
});

describe('edge cases', () => {
  test('a pending stop cannot be shared, even though (0, 0) is finite', () => {
    // Covered end to end in share-links.test.ts; asserted here because `isLocated` is
    // the predicate the planner gates on and the encoder now mirrors it.
    expect(isLocated(locating())).toBe(false);
  });
});
