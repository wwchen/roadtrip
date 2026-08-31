// The trip planner's state.
//
// Abort controllers and MapLibre markers belong to the query and map layers,
// respectively; this store contains serializable planner state only.
import { create } from 'zustand';
import type { Feature, FeatureCollection } from 'geojson';

export const MAX_STOPS = 25;

// Corridor: a buffered polygon around the active route, used to filter
// /api/pois server-side. 5 mi default keeps "along route" tight; the user can
// widen it when they are willing to detour.
export const CORRIDOR_DEFAULT_MILES = 5;
export const CORRIDOR_MIN_MILES = 5;
export const CORRIDOR_MAX_MILES = 100;
export const CORRIDOR_STEP_MILES = 5;
/** Degrees — ~2km at mid-latitudes. Keeps even cross-country routes under the
 *  backend's 2000-vertex polygon cap in one POST body. */
export const CORRIDOR_SIMPLIFY_TOLERANCE = 0.02;

export const GEOCODE_DEBOUNCE_MS = 220;

export type TripMode =
  /** Single search bar, no route. */
  | 'browse'
  /** N >= 2 slots; the route is fetched once every slot is filled. */
  | 'directions';

export interface TripStop {
  name: string;
  lng: number;
  lat: number;
  /** Search-result kind (PLACE, ADDR, CG, SC, NP, SP, PF) — drives the pin colour. */
  kind?: string;
  /** The originating search-result row, when the stop came from one. */
  pinItem?: unknown;
  /**
   * A placeholder while the browser resolves the user's location.
   *
   * Ported from the legacy `_pending` flag, and it has to be a property of the
   * stop rather than a separate loading flag because the row it belongs to can
   * move: the coordinates are (0, 0) until geolocation answers, so routing and
   * marker placement both have to skip it — see `isLocated` in
   * domain/trip/stops.ts. Null island is a real coordinate that passes every
   * finite check.
   */
  pending?: boolean;
}

/**
 * The trip's geometries, typed as GeoJSON now that Phase 4e consumes them.
 *
 * Phase 0 declared these as `Record<string, unknown>` on the grounds that they
 * were "passed through to MapLibre" — true of the map, but the planner also reads
 * the route's `properties` for its summary and its line for the corridor buffer,
 * and an untyped bag made every one of those reads a cast. Narrowed here for the
 * same reason the filter fields were reshaped in 4b: a store shape is a guess
 * until something consumes it.
 */
export type TripRouteCollection = FeatureCollection;
/** A slim POI pin from /api/pois/on-route. Painted, never read field-by-field. */
export type TripPoiFeature = Feature;

export interface TripState {
  mode: TripMode;
  /** `null` marks an empty slot, so slot position is stable while editing. */
  stops: (TripStop | null)[];
  /** FeatureCollection from /api/route. */
  route: TripRouteCollection | null;
  corridorMiles: number;
  /** POI features found along the active route. */
  routePois: TripPoiFeature[];
  /**
   * The row that should take keyboard focus, once.
   *
   * In the store rather than in the topbar's own state because it has several
   * producers: the topbar's own edits, the drawer's Directions button (which
   * enters directions mode and wants the empty origin focused), and the map's
   * blocked-geolocation toast, whose "Search a place" action sends the user to
   * row 0. The consumer — `StopRow` — clears it through `clearFocus`, so it is a
   * request, not a mode.
   */
  focusRow: number | null;

  setMode: (mode: TripMode) => void;
  setStops: (stops: (TripStop | null)[]) => void;
  /** Set one slot by index. Extends the list with empty slots if needed. */
  setStopAt: (index: number, stop: TripStop | null) => void;
  /**
   * Append a stop from outside the planner (the drawer's "add to trip" button).
   * Fills the first empty slot if there is one, otherwise appends — and never
   * grows past MAX_STOPS.
   */
  addStop: (stop: TripStop) => void;
  removeStopAt: (index: number) => void;
  setRoute: (route: TripRouteCollection | null) => void;
  setCorridorMiles: (miles: number) => void;
  setRoutePois: (features: TripPoiFeature[]) => void;
  requestFocus: (row: number | null) => void;
  clearFocus: () => void;
  reset: () => void;
}

const INITIAL_TRIP = {
  mode: 'browse',
  stops: [],
  route: null,
  corridorMiles: CORRIDOR_DEFAULT_MILES,
  routePois: [],
  focusRow: null,
} satisfies Omit<
  TripState,
  | 'setMode'
  | 'setStops'
  | 'setStopAt'
  | 'addStop'
  | 'removeStopAt'
  | 'setRoute'
  | 'setCorridorMiles'
  | 'setRoutePois'
  | 'requestFocus'
  | 'clearFocus'
  | 'reset'
>;

const clampCorridorMiles = (miles: number): number =>
  Math.min(CORRIDOR_MAX_MILES, Math.max(CORRIDOR_MIN_MILES, miles));

export const useTripStore = create<TripState>()((set) => ({
  ...INITIAL_TRIP,

  setMode: (mode) => set({ mode }),
  setStops: (stops) => set({ stops }),

  setStopAt: (index, stop) =>
    set((s) => {
      const stops = s.stops.slice();
      while (stops.length <= index) stops.push(null);
      stops[index] = stop;
      return { stops };
    }),

  addStop: (stop) =>
    set((s) => {
      const emptyIndex = s.stops.indexOf(null);
      if (emptyIndex !== -1) {
        const stops = s.stops.slice();
        stops[emptyIndex] = stop;
        return { stops };
      }
      if (s.stops.length >= MAX_STOPS) return {};
      return { stops: [...s.stops, stop] };
    }),

  removeStopAt: (index) => set((s) => ({ stops: s.stops.filter((_, i) => i !== index) })),

  setRoute: (route) => set({ route }),
  setCorridorMiles: (miles) => set({ corridorMiles: clampCorridorMiles(miles) }),
  setRoutePois: (routePois) => set({ routePois }),
  requestFocus: (focusRow) => set({ focusRow }),
  clearFocus: () => set({ focusRow: null }),

  reset: () => set({ ...INITIAL_TRIP }),
}));

// ---------------------------------------------------------------------------
// Selectors
// ---------------------------------------------------------------------------

/**
 * Whether every slot holds something.
 *
 * Deliberately NOT the routing gate: it counts a `pending` "Locating you…" stop as
 * filled, because for the store's purposes the slot is occupied. The predicate that
 * decides whether a route may be requested is `allStopsFilled` in
 * domain/trip/stops.ts, which also requires every stop to be located. The two
 * cannot disagree about a live route — `selectRouteActive` needs a fetched route as
 * well, and only that stricter gate can produce one.
 */
export const selectAllStopsFilled = (s: TripState): boolean =>
  s.stops.length > 0 && s.stops.every((stop) => stop != null);

/**
 * Whether a usable route is on the map. Mirrors the legacy
 * Route-active predicate: directions mode, a fetched route, and
 * every slot filled.
 */
export const selectRouteActive = (s: TripState): boolean =>
  s.mode === 'directions' && !!s.route && selectAllStopsFilled(s);

export const selectFilledStops = (s: TripState): TripStop[] =>
  s.stops.filter((stop): stop is TripStop => stop != null);
