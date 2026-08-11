// Origin and destination are structural slots: directions mode always keeps at
// least two rows, while clearing an intermediate stop removes that row.
import { MAX_STOPS, type TripMode, type TripStop } from '@/stores/tripStore';

/** A slot the user has not filled yet. */
export type StopSlot = TripStop | null;

/**
 * The route query is keyed on the resulting stops, so transitions do not carry a
 * separate "should re-route" flag.
 */
export interface StopsTransition {
  stops: StopSlot[];
  mode: TripMode;
  /**
   * The row to focus after the edit, or null to leave focus alone.
   *
   * Only meaningful on a desktop viewport — the caller decides that, because a
   * programmatic focus on a phone raises the keyboard over the map.
   */
  focusRow: number | null;
}

/**
 * Whether a stop is a real location.
 *
 * The "Locating you…" placeholder is a stop with `pending: true` and (0, 0)
 * coordinates, and it must not count: routing from null island is worse than
 * waiting for the geolocation callback. This is the whole reason `pending`
 * exists as a field rather than as a separate loading flag.
 */
export const isLocated = (stop: StopSlot): stop is TripStop => stop != null && !stop.pending;

/** Directions needs two ends, and both of them located. */
export const allStopsFilled = (stops: readonly StopSlot[]): boolean =>
  stops.length >= 2 && stops.every(isLocated);

/** Row 0 is the origin; the last row is the destination; the rest are vias. */
export type StopRole = 'origin' | 'via' | 'destination';

export function stopRole(index: number, count: number): StopRole {
  if (index === 0) return 'origin';
  return index === count - 1 ? 'destination' : 'via';
}

/**
 * The row's placeholder, which is also its accessible name.
 *
 * Browse mode has one row and it is a search box, not an origin — the same
 * input serves both, and the placeholder is the only thing that says which.
 */
export function stopPlaceholder(index: number, count: number, mode: TripMode): string {
  if (mode !== 'directions') return 'Search a place or pin…';
  switch (stopRole(index, count)) {
    case 'origin':
      return 'Origin';
    case 'destination':
      return 'Destination';
    default:
      return `Stop ${index}`;
  }
}

/**
 * The short label a route summary uses for a stop: its first word.
 *
 * "Bowman Bay Campground, Anacortes WA" is longer than the leg line it appears
 * in, and the leg breakdown is a 240px-wide list of them.
 */
const MAX_STOP_LABEL_CHARS = 18;
const TRUNCATED_STOP_LABEL_CHARS = 16;

export function stopLabel(stops: readonly StopSlot[], index: number): string {
  const stop = stops[index];
  if (!stop) return `Stop ${index + 1}`;
  const first = stop.name.split(/[\s,]+/)[0] ?? '';
  return first.length > MAX_STOP_LABEL_CHARS
    ? `${first.slice(0, TRUNCATED_STOP_LABEL_CHARS)}…`
    : first;
}

/** Write one slot, extending the list with empty slots to reach it. */
export function withStopAt(
  stops: readonly StopSlot[],
  index: number,
  stop: StopSlot,
): StopSlot[] {
  const next = stops.slice();
  while (next.length <= index) next.push(null);
  next[index] = stop;
  return next;
}

/**
 * Enter directions mode from the search bar.
 *
 * The row-0 search the user already typed becomes the origin, which is why this
 * pads rather than resets: they searched for where they are, then asked for
 * directions from it.
 */
export function enterDirections(stops: readonly StopSlot[]): StopsTransition {
  const next = stops.slice();
  while (next.length < 2) next.push(null);
  const firstEmpty = next.findIndex((stop) => !isLocated(stop));
  return {
    stops: next,
    mode: 'directions',
    focusRow: firstEmpty === -1 ? null : firstEmpty,
  };
}

/** Append an empty via, up to the cap. */
export function addEmptyStop(stops: readonly StopSlot[], mode: TripMode): StopsTransition {
  if (stops.length >= MAX_STOPS) {
    return { stops: stops.slice(), mode, focusRow: null };
  }
  const next = [...stops, null];
  return { stops: next, mode, focusRow: next.length - 1 };
}

/**
 * Whether a row is one the list always has.
 *
 * Origin and destination in directions mode, and browse mode's single search box.
 * Exported because it decides two things that must agree: what the X button *does*
 * (clear the slot, or remove the row) and what it is *called*.
 */
export function isStructuralRow(index: number, count: number, mode: TripMode): boolean {
  return mode !== 'directions' || index === 0 || index === count - 1;
}

/**
 * The X button on a row.
 *
 * Four outcomes, and the split is between *clearing* a slot and *removing* a
 * row:
 *
 *   - a structural slot (origin, destination, or any row in browse mode) clears
 *     in place, because directions mode without both ends is not a state the UI
 *     can render;
 *   - a via is removed outright;
 *   - removing the second-to-last row drops back to browse mode, since
 *     directions with one waypoint means nothing;
 *   - removing the last row is a full clear, which the caller handles as such
 *     (mode `browse`, no stops).
 */
export function removeStopAt(
  stops: readonly StopSlot[],
  index: number,
  mode: TripMode,
): StopsTransition {
  // Browse mode's single row counts as structural for the same reason the
  // endpoints do: it is the search box, and the page always has one.
  const structural = isStructuralRow(index, stops.length, mode);
  const filled = stops[index] != null;

  if (filled && structural) {
    const next = withStopAt(stops, index, null);
    return { stops: next, mode, focusRow: index };
  }

  // An empty endpoint in a two-row trip: there is nothing to clear and the row
  // cannot go, since directions mode has no state with fewer than two rows.
  //
  // The vanilla wrote `if (wasFilled) { clear in place }` inside this guard,
  // which cannot fire: in a two-row trip every row is structural, so a filled
  // one returned above. Dropped rather than translated.
  if (mode === 'directions' && stops.length <= 2) {
    return { stops: stops.slice(), mode, focusRow: null };
  }

  const next = stops.filter((_, i) => i !== index);
  if (next.length === 0) return { stops: [], mode: 'browse', focusRow: null };
  // One waypoint is not a route. The survivor becomes the browse selection,
  // which is what the vanilla did — and it is why this returns a mode at all.
  if (next.length === 1) {
    return { stops: next, mode: 'browse', focusRow: null };
  }
  return { stops: next, mode, focusRow: null };
}

/**
 * Drag-reorder: move the row at `from` to sit at `to`.
 *
 * Splice-out-then-splice-in, so dragging down past a row lands *after* it — the
 * same arithmetic as the vanilla drop handler, which is what makes a dragged row
 * end up where the drop indicator was.
 */
export function reorderStops(
  stops: readonly StopSlot[],
  from: number,
  to: number,
): StopsTransition {
  const unchanged = (): StopsTransition => ({
    stops: stops.slice(),
    mode: 'directions',
    focusRow: null,
  });
  if (!Number.isInteger(from) || !Number.isInteger(to)) return unchanged();
  if (from === to || from < 0 || from >= stops.length) return unchanged();

  const next = stops.slice();
  const [moved] = next.splice(from, 1);
  if (to >= next.length) next.push(moved ?? null);
  else next.splice(Math.max(to, 0), 0, moved ?? null);
  return { stops: next, mode: 'directions', focusRow: null };
}

/**
 * A stop added from outside the planner — the drawer's Directions button.
 *
 * `autoFocusOrigin` is the caller's viewport decision, and it changes the
 * result rather than just the focus: on a phone the origin is filled from the
 * user's location instead (`fillOrigin` in the returned transition), because
 * "directions to this campground" on a phone almost always means "from here",
 * and the soft keyboard would cover the drawer anyway.
 */
export interface ExternalStopTransition extends StopsTransition {
  /** True when the caller should resolve the user's location into row 0. */
  fillOrigin: boolean;
}

export function addExternalStop(
  stops: readonly StopSlot[],
  mode: TripMode,
  stop: TripStop,
  { autoFocusOrigin }: { autoFocusOrigin: boolean },
): ExternalStopTransition {
  if (mode === 'browse') {
    // Reset rather than append: row 0 may hold a leftover pin click from browse
    // mode, and the POI the user just asked for directions to is the
    // destination, not a second search result.
    const next: StopSlot[] = [null, stop];
    return {
      stops: next,
      mode: 'directions',
      focusRow: autoFocusOrigin ? 0 : null,
      fillOrigin: !autoFocusOrigin,
    };
  }

  const last = stops.length - 1;
  if (stops[last] == null) {
    // The destination slot is still empty, so it is the useful place to put it —
    // and the route fires immediately if the origin is already filled.
    const next = withStopAt(stops, last, stop);
    return {
      stops: next,
      mode,
      focusRow: null,
      fillOrigin: false,
    };
  }

  if (stops.length >= MAX_STOPS) {
    return { stops: stops.slice(), mode, focusRow: null, fillOrigin: false };
  }
  // Insert as a via *before* the destination, so the endpoint the user chose
  // stays the endpoint.
  const next = stops.slice();
  next.splice(last, 0, stop);
  return { stops: next, mode, focusRow: null, fillOrigin: false };
}

/**
 * The marker label for a row: A for the origin, a letter for the destination,
 * the row number for a via.
 *
 * The destination's letter counts from the trip's length, so a three-stop trip
 * shows A, 1, C — the via keeps its ordinal and the ends read as ends. Capped at
 * Z, past which the alphabet runs out and the number is what matters anyway.
 */
const LETTER_A = 65;
const LAST_LETTER_OFFSET = 25;

export function markerLabel(index: number, count: number): string {
  switch (stopRole(index, count)) {
    case 'origin':
      return 'A';
    case 'destination':
      return String.fromCharCode(LETTER_A + Math.min(count - 1, LAST_LETTER_OFFSET));
    default:
      return String(index);
  }
}
