// Reads and writes the independent `?route=` share parameter without clobbering
// `?poi=`. Camera fitting is handled separately once the map style is ready.
import { useEffect, useRef, useState } from 'react';
import { decodeRouteState, setVisibleRouteParam } from '@/lib/share-links';
import { CORRIDOR_DEFAULT_MILES, useTripStore } from '@/stores/tripStore';
import { clampCorridorMiles } from '@/lib/trip-corridor';
import { allStopsFilled } from '@/domain/trip/stops';

export const ROUTE_PARAM = 'route';

export interface SharedTrip {
  /** Set when a `?route=` was present but could not be read. */
  error: string | null;
}

const INVALID_SHARED_ROUTE = 'Shared route link is invalid.';

export function useSharedTrip(): SharedTrip {
  const stops = useTripStore((s) => s.stops);
  const corridorMiles = useTripStore((s) => s.corridorMiles);
  const setStops = useTripStore((s) => s.setStops);
  const setMode = useTripStore((s) => s.setMode);
  const setCorridorMiles = useTripStore((s) => s.setCorridorMiles);

  /**
   * The restore runs once per mount, whatever else changes.
   *
   * A ref rather than an empty dependency array alone: the effect below reads the
   * store's setters, and a ref makes "already restored" explicit rather than a
   * property of how React happens to schedule effects.
   */
  const restored = useRef(false);
  /**
   * Whether this hook has ever written the parameter.
   *
   * Without it the writer's first run would DELETE the `?route=` it was mounted
   * with: effects run in order, and the writer's first pass still sees the empty
   * store from before the restore effect's `setStops` was observable. The link
   * survived only because the next render put it back — two `replaceState` calls and
   * a window in which a reload would have lost the trip.
   */
  const written = useRef(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (restored.current) return;
    restored.current = true;

    const param = new URLSearchParams(window.location.search).get(ROUTE_PARAM);
    if (!param) return;

    const shared = decodeRouteState(param);
    if (!shared) {
      setError(INVALID_SHARED_ROUTE);
      return;
    }
    setStops(shared.stops);
    setMode('directions');
    // A link can carry any radius; the slider can only show notches, so it is
    // snapped on the way in rather than left between two of them.
    setCorridorMiles(clampCorridorMiles(shared.corridorMiles ?? CORRIDOR_DEFAULT_MILES));
  }, [setCorridorMiles, setMode, setStops]);

  /**
   * Keep the address bar in step with the trip.
   *
   * Only a complete trip is written: a half-typed itinerary has no shareable form,
   * and writing a partial one would hand out links that open a broken planner. The
   * writer preserves every other parameter, which is what keeps an open drawer's
   * `?poi=` alive — see `setVisibleRouteParam`.
   */
  useEffect(() => {
    const shareable = allStopsFilled(stops);
    // Nothing to write and nothing written yet: leave the address bar exactly as the
    // user opened it, including a `?route=` that is still being restored — or one
    // that failed to decode, which they may want to keep to look at.
    if (!shareable && !written.current) return;
    written.current = shareable;
    setVisibleRouteParam(shareable ? stops : [], corridorMiles);
  }, [stops, corridorMiles]);

  return { error };
}
