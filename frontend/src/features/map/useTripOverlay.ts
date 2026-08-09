// The trip's presence on the map: the route line, the corridor fill, the markers.
//
// The React half of `drawRoute` / `updateCorridor` / `fitMapToRoute` / `syncMarkers`
// from web/topbar.js — the imperative halves live in `map/route-overlay.ts` and
// `map/trip-markers.ts`. Four effects, and the split between them is the point:
// each watches exactly what it has to reinstall for.
//
//   install  — the route changed, or the style reloaded under it.
//   fit      — a NEW route arrived. Deliberately not the style reload: a basemap
//              change must not throw the camera back to the whole route when the
//              user has zoomed into one campground.
//   radius   — the slider moved; repaint the fill in place, do not reinstall.
//   markers  — the stops changed, which includes a reorder relabelling them.
import { useEffect, useMemo, useRef } from 'react';
import {
  computeCorridor,
  routeLine,
  serverCorridor,
  type CorridorGeometry,
} from '@/features/trip/corridor';
import {
  ROUTE_FIT_DURATION_MS,
  ROUTE_FIT_PADDING_PX,
  installRouteOverlay,
  removeRouteOverlay,
  routeBounds,
  setCorridorData,
} from '@/map/route-overlay';
import {
  createTripMarkerRegistry,
  removeTripMarkers,
  syncTripMarkers,
  type TripMarkerRegistry,
} from '@/map/trip-markers';
import { useTripStore } from '@/stores/tripStore';
import { useMapContext } from './MapProvider';

export function useTripOverlay(): void {
  const { map, styleReady } = useMapContext();
  const route = useTripStore((s) => s.route);
  const stops = useTripStore((s) => s.stops);
  const corridorMiles = useTripStore((s) => s.corridorMiles);
  const setCorridor = useTripStore((s) => s.setCorridor);

  const line = useMemo(() => routeLine(route), [route]);
  /**
   * The server's own corridor polygon, when the response carried one.
   *
   * Preferred for the first paint because it is the exact polygon
   * /api/pois/on-route filtered by — ours is a local approximation of the same
   * buffer, and a fill that disagrees with the list beside it looks like a bug.
   */
  const server = useMemo(() => serverCorridor(route), [route]);

  const registryRef = useRef<TripMarkerRegistry>();
  registryRef.current ??= createTripMarkerRegistry();

  /**
   * The radius the installed fill was built for.
   *
   * Without it the radius effect would fire once on mount and immediately replace a
   * server-supplied corridor with our own local buffer — the one polygon we
   * deliberately did not compute.
   */
  const installedMiles = useRef<number | null>(null);

  // Install. `styleReady` is in the deps because a basemap change wipes every app
  // layer and flips it false→true; without that the route would vanish on a
  // basemap switch, which is the one behaviour `styleReady` exists for.
  useEffect(() => {
    if (!map || !styleReady) return;
    if (!line) {
      removeRouteOverlay(map);
      setCorridor(null);
      return;
    }
    const corridor: CorridorGeometry | null = server ?? computeCorridor(line, corridorMiles);
    installRouteOverlay(map, line, corridor);
    setCorridor(corridor);
    installedMiles.current = corridorMiles;
    return () => {
      // Not conditional on `line`: the cleanup runs when the route changes as well
      // as on unmount, and leaving the previous line up while the next installs
      // would double-draw for a frame.
      removeRouteOverlay(map);
    };
    // `corridorMiles` is deliberately absent: a radius change is the third effect's
    // job, and reinstalling here would restart the camera fit as well.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, styleReady, line, server, setCorridor]);

  // Fit, once per route. The ref is the "have I already framed this one" flag: a
  // style reload re-runs the install effect, and refitting there would undo the
  // user's zoom every time they changed basemap.
  const fittedLine = useRef<unknown>(null);
  useEffect(() => {
    if (!map || !styleReady || !line) return;
    if (fittedLine.current === line) return;
    const bounds = routeBounds(line);
    if (!bounds) return;
    fittedLine.current = line;
    map.fitBounds(bounds, { padding: ROUTE_FIT_PADDING_PX, duration: ROUTE_FIT_DURATION_MS });
  }, [map, styleReady, line]);

  // The radius. Local recompute per tick — the server is not asked again, which is
  // why `useRoute` leaves the radius out of its key.
  useEffect(() => {
    if (!map || !line) return;
    if (installedMiles.current === corridorMiles) return;
    installedMiles.current = corridorMiles;
    const corridor = computeCorridor(line, corridorMiles);
    setCorridor(corridor);
    setCorridorData(map, corridor);
  }, [map, line, corridorMiles, setCorridor]);

  // Markers.
  useEffect(() => {
    const registry = registryRef.current;
    if (!map || !registry) return;
    syncTripMarkers(map, registry, stops);
  }, [map, stops]);

  // Markers again, for unmount only: they are DOM elements the map holds, so they
  // outlive a React unmount unless something removes them.
  useEffect(() => {
    const registry = registryRef.current;
    return () => {
      if (registry) removeTripMarkers(registry);
    };
  }, []);
}
