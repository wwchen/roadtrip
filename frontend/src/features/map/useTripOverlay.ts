// The trip's presence on the map: the route line, the corridor fill, the markers.
//
// The React half of `drawRoute` / `updateCorridor` / `fitMapToRoute` / `syncMarkers`
// from web/topbar.js — the imperative halves live in `map/route-overlay.ts` and
// `map/trip-markers.ts`.
//
// The corridor is ONE derived value (`corridor` below) rather than something two
// effects each compute, and that is the fix for a bug an adversarial review of 4e
// found: the install effect preferred the server's polygon and then recorded the
// current radius as installed, so after a basemap change — which re-runs the install
// — the fill silently reverted to the radius the route was fetched at while the
// slider and the campground count said something else, with no path back until the
// route itself changed. A single memo cannot disagree with itself.
import { useEffect, useMemo, useRef } from 'react';
import { computeCorridor, routeLine, serverCorridor } from '@/lib/trip-corridor';
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

  const line = useMemo(() => routeLine(route), [route]);
  const server = useMemo(() => serverCorridor(route), [route]);

  /**
   * The radius the current route was requested with.
   *
   * `useRoute` sends the radius that was set when it fetched, so the server's
   * polygon describes THAT radius — it is only the right thing to draw while the
   * slider has not moved since. Recorded on each new route, in render rather than in
   * an effect, so the memo below can read it on the same pass.
   */
  const routeIdentity = useRef<unknown>(null);
  const radiusAtRoute = useRef(corridorMiles);
  if (routeIdentity.current !== route) {
    routeIdentity.current = route;
    radiusAtRoute.current = corridorMiles;
  }

  /**
   * The corridor to draw: the server's polygon at the radius it was built for,
   * otherwise our own buffer at the radius the slider is on now.
   *
   * The server's is preferred where it applies because it is the exact polygon
   * `/api/pois/on-route` filtered by, and a fill that disagrees with the list beside
   * it reads as a bug.
   */
  const corridor = useMemo(() => {
    if (!line) return null;
    if (server && corridorMiles === radiusAtRoute.current) return server;
    return computeCorridor(line, corridorMiles);
  }, [line, server, corridorMiles]);

  /**
   * The newest corridor, readable from the install effect.
   *
   * Assigned during render, so an install triggered by a style reload paints the
   * corridor the slider is currently on — 4b's lesson about effects running in
   * declaration order, in its smallest form.
   */
  const corridorRef = useRef(corridor);
  corridorRef.current = corridor;

  // Install: the route changed, or the style reloaded under it. `styleReady` is in
  // the deps because a basemap change wipes every app layer and flips it false→true;
  // without that the route would vanish on a basemap switch, which is the one
  // behaviour `styleReady` exists for.
  useEffect(() => {
    if (!map || !styleReady) return;
    if (!line) {
      removeRouteOverlay(map);
      return;
    }
    installRouteOverlay(map, line, corridorRef.current);
    return () => {
      // Not conditional on `line`: this cleanup runs when the route changes as well
      // as on unmount, and leaving the previous line up while the next installs
      // would double-draw for a frame.
      removeRouteOverlay(map);
    };
  }, [map, styleReady, line]);

  // Update the fill without reinstalling the route layers. This runs on every
  // tick of the radius slider.
  useEffect(() => {
    if (map) setCorridorData(map, corridor);
  }, [map, corridor]);

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

  // Markers: the stops changed, which includes a reorder relabelling them.
  const registryRef = useRef<TripMarkerRegistry>();
  registryRef.current ??= createTripMarkerRegistry();
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
