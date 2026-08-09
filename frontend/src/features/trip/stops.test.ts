// The stop list's rules, which in the vanilla were four intertwined branches of
// one DOM handler. Each case here is a rule someone would otherwise have to
// rediscover by dragging rows around in a browser.
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

  // (0, 0) is a coordinate in the Gulf of Guinea, and it passes every finite
  // check — routing from it is worse than waiting for the callback.
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

  // A one-row list is browse mode's search box; role() answers origin for it,
  // and the placeholder is what says it is a search box rather than a trip.
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

  // The alphabet runs out long before MAX_STOPS does.
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
  // The search the user already typed becomes the origin — they searched for
  // where they are, then asked for directions from it.
  test('keeps a filled search row as the origin and focuses the destination', () => {
    expect(enterDirections([A])).toEqual({
      stops: [A, null],
      mode: 'directions',
      shouldRoute: false,
      focusRow: 1,
    });
  });

  test('focuses the origin when nothing is filled', () => {
    expect(enterDirections([]).focusRow).toBe(0);
  });

  // Reached from a shared link, which arrives with both ends already set.
  test('routes immediately when both ends are already filled', () => {
    const result = enterDirections([A, B]);

    expect(result.shouldRoute).toBe(true);
    expect(result.focusRow).toBeNull();
  });
});

describe('adding an empty stop', () => {
  test('appends and focuses the new row', () => {
    expect(addEmptyStop([A, B], 'directions')).toEqual({
      stops: [A, B, null],
      mode: 'directions',
      shouldRoute: false,
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
  // Origin and destination are structural: directions mode with one end is not a
  // state the rows can render, so the X clears the slot and keeps the row.
  test('clears a filled destination in place, and focuses it', () => {
    expect(removeStopAt([A, B], 1, 'directions')).toEqual({
      stops: [A, null],
      mode: 'directions',
      shouldRoute: false,
      focusRow: 1,
    });
  });

  test('clears a filled origin in place', () => {
    expect(removeStopAt([A, B], 0, 'directions').stops).toEqual([null, B]);
  });

  test('removes a via outright', () => {
    expect(removeStopAt([A, C, B], 1, 'directions').stops).toEqual([A, B]);
  });

  // Removing a via from a complete trip leaves a complete trip, which should
  // re-route: the itinerary changed.
  test('re-routes after removing a via from a complete trip', () => {
    expect(removeStopAt([A, C, B], 1, 'directions').shouldRoute).toBe(true);
  });

  test('does not re-route when a slot is still empty', () => {
    expect(removeStopAt([A, C, null], 1, 'directions').shouldRoute).toBe(false);
  });

  // Browse mode's row is the search box, so its X clears the text and the box
  // stays — the same rule as an endpoint, for the same reason.
  test('clears browse mode"s search row in place', () => {
    expect(removeStopAt([A], 0, 'browse')).toEqual({
      stops: [null],
      mode: 'browse',
      shouldRoute: false,
      focusRow: 0,
    });
  });

  test('removing an already-empty browse row empties the trip', () => {
    expect(removeStopAt([null], 0, 'browse')).toEqual({
      stops: [],
      mode: 'browse',
      shouldRoute: false,
      focusRow: null,
    });
  });

  // One waypoint is not a route, so the survivor becomes the browse selection.
  // Reached from a stop list left over from a previous directions session.
  test('falls back to browse mode when only one stop would remain', () => {
    expect(removeStopAt([A, null], 1, 'browse')).toEqual({
      stops: [A],
      mode: 'browse',
      shouldRoute: false,
      focusRow: null,
    });
  });

  // Directions mode has no state with fewer than two rows, so an empty endpoint's
  // X has nothing to do. The vanilla re-rendered, cleared a route layer that
  // could not exist, and — on desktop — stole focus back into the row.
  test('an empty endpoint in a two-row trip changes nothing', () => {
    expect(removeStopAt([null, B], 0, 'directions')).toEqual({
      stops: [null, B],
      mode: 'directions',
      shouldRoute: false,
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

  test('re-routes when the reordered trip is complete', () => {
    expect(reorderStops([A, B, C], 0, 2).shouldRoute).toBe(true);
    expect(reorderStops([A, null, C], 0, 2).shouldRoute).toBe(false);
  });

  test('a drop on itself is a no-op', () => {
    const result = reorderStops([A, B], 1, 1);

    expect(result.stops).toEqual([A, B]);
    expect(result.shouldRoute).toBe(false);
  });

  // A drop can carry a stale index — the row that started the drag may have been
  // removed by the time it lands.
  test('ignores an out-of-range or non-numeric source', () => {
    expect(reorderStops([A, B], 5, 0).stops).toEqual([A, B]);
    expect(reorderStops([A, B], Number.NaN, 0).stops).toEqual([A, B]);
  });

  test('a drop past the end appends', () => {
    expect(reorderStops([A, B, C], 0, 9).stops).toEqual([B, C, A]);
  });
});

describe('a stop added from the drawer', () => {
  // The POI is the destination; the origin is the user's business. On desktop we
  // focus it, on a phone we offer to fill it from the device's location.
  test('browse mode becomes a two-row trip with the POI as destination', () => {
    expect(addExternalStop([A], 'browse', B, { autoFocusOrigin: true })).toEqual({
      stops: [null, B],
      mode: 'directions',
      shouldRoute: false,
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

    expect(result.stops).toEqual([A, B]);
    // Both ends are now filled, so the route fires without another action.
    expect(result.shouldRoute).toBe(true);
  });

  // The endpoint the user chose stays the endpoint.
  test('inserts before the destination when that slot is taken', () => {
    const result = addExternalStop([A, B], 'directions', C, { autoFocusOrigin: true });

    expect(result.stops).toEqual([A, C, B]);
    expect(result.shouldRoute).toBe(true);
  });

  test('refuses past the cap', () => {
    const full = Array.from({ length: 25 }, (_, i) => stop(`Stop ${i}`));

    expect(addExternalStop(full, 'directions', C, { autoFocusOrigin: true }).stops).toHaveLength(25);
  });
});
