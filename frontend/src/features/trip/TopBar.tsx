// The trip planner's top-left panel.
//
// Port of `injectDom` + `renderRows`' button visibility + `bindEvents` +
// `onInputKey`'s pick half from web/topbar.js. The controller is
// `useTripPlanner`, the data comes from `useRoute` / `useOnRoutePois` /
// `useSearchResults`, and this file is the composition — which is why it holds only
// the state that is genuinely about *this* panel: which row is being typed in, what
// has been typed, and which dropdown row the keyboard has selected.
//
// One piece of the vanilla is deliberately absent: the `#tb-status` element's
// `innerHTML`. Its three contents (a leg breakdown, a routing error, a geolocation
// failure) are three components now, which is what makes the "computing route…"
// state distinguishable from the "no route" state without reading a CSS class.
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { RouteStatus } from './RouteStatus';
import { SearchDropdown } from './SearchDropdown';
import { StopRow } from './StopRow';
import { TripResults } from './TripResults';
import { MAX_SEARCH_RESULTS, type SearchResult } from './search-results';
import { allStopsFilled, isLocated } from '@/domain/trip/stops';
import { buildRouteIndex } from './route-index';
import { tripCardsFromFeatures } from './trip-cards';
import { useOnRoutePois } from './useOnRoutePois';
import { useRoute } from './useRoute';
import { useTripCards } from './useTripCards';
import { useSearchResults } from './useSearchResults';
import { useSharedTrip } from './useSharedTrip';
import { useTripPlanner } from './useTripPlanner';
import { useMapStore } from '@/stores/mapStore';
import { MAX_STOPS } from '@/stores/tripStore';
import './topbar.css';

/** What the keyboard has selected before any arrow key is pressed. */
const NO_ACTIVE_RESULT = -1;

export interface TopBarProps {
  alerts?: ReactNode;
  auth?: ReactNode;
}

export function TopBar({ alerts, auth }: TopBarProps) {
  const planner = useTripPlanner();
  const route = useRoute();
  const corridor = useOnRoutePois();
  const shared = useSharedTrip();
  usePublishedLocationFiller(planner.useCurrentLocation);

  // Placeholder cards from the corridor response, then hydrated per card. Both steps
  // are cheap enough to run per render: the index is one pass over the route's
  // vertices, and the hydration is a cache read once each id has landed.
  const routeIndex = useMemo(() => buildRouteIndex(route.line), [route.line]);
  const placeholders = useMemo(
    () => tripCardsFromFeatures(corridor.features, planner.stops[0] ?? null, routeIndex),
    [corridor.features, planner.stops, routeIndex],
  );
  const cards = useTripCards(placeholders);

  /**
   * The row being typed in, and what is in it.
   *
   * One draft, not one per row: the vanilla re-rendered every row from
   * `trip.stops`, so only the focused input could hold text that was not a stop's
   * name — moving focus discarded it. Keeping that means a half-typed query cannot
   * linger in a row the user has left.
   */
  const [draft, setDraft] = useState<{ row: number; text: string } | null>(null);
  const [activeResult, setActiveResult] = useState(NO_ACTIVE_RESULT);

  const search = useSearchResults(draft?.text ?? '');
  const results = search.results;
  const isDirections = planner.mode === 'directions';
  const rowCount = Math.max(planner.stops.length, 1);

  const pick = (result: SearchResult) => {
    planner.pickResult(draft?.row ?? 0, result);
    setDraft(null);
    setActiveResult(NO_ACTIVE_RESULT);
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>, row: number) => {
    if (results.length === 0) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveResult((current) => Math.min(current + 1, results.length - 1));
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveResult((current) => Math.max(current - 1, 0));
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      // Enter with nothing selected takes the first row, which is what a user who
      // typed a full name and hit Enter expects — and what the vanilla did.
      const chosen = results[activeResult >= 0 ? activeResult : 0];
      if (chosen) {
        planner.pickResult(row, chosen);
        setDraft(null);
        setActiveResult(NO_ACTIVE_RESULT);
      }
      return;
    }
    if (event.key === 'Escape') {
      setDraft(null);
      setActiveResult(NO_ACTIVE_RESULT);
      event.currentTarget.blur();
    }
  };

  return (
    <div className="tb-panel" id="topbar">
      <div className="tb-stops">
        {Array.from({ length: rowCount }, (_, index) => {
          const stop = planner.stops[index] ?? null;
          return (
            <StopRow
              key={index}
              index={index}
              count={rowCount}
              stop={stop}
              mode={planner.mode}
              value={draft?.row === index ? draft.text : (stop?.name ?? '')}
              // Rows only reorder in directions mode, and only when there is
              // something to reorder them against.
              draggable={isDirections && rowCount >= 2}
              autoFocus={planner.focusRow === index}
              onFocusHandled={planner.focusHandled}
              onChange={(text) => {
                setDraft({ row: index, text });
                setActiveResult(NO_ACTIVE_RESULT);
              }}
              onFocus={() => {
                // Focusing a row that is still locating drops the placeholder: the
                // user has decided to type their own origin, and the geolocation
                // callback must not overwrite it (it checks `pending` first).
                if (stop?.pending) planner.removeStop(index);
                if (draft && draft.row !== index) setDraft(null);
              }}
              onKeyDown={(event) => onKeyDown(event, index)}
              onRemove={() => {
                planner.removeStop(index);
                if (draft?.row === index) setDraft(null);
              }}
              onUseCurrentLocation={() => planner.useCurrentLocation(index)}
              onReorder={planner.reorder}
            />
          );
        })}
      </div>

      {/* `#tb-alerts` sat here in the vanilla DOM too: under the rows, above the
          actions. It renders nothing at all for a user with no watches. */}
      {alerts}

      {/* Where the vanilla topbar kept `#tb-auth`: sign-in, who you are, and the
          trigger for the settings modal Phase 3 built and nothing mounted. */}
      {auth}

      {/* The ids on this row and the controls below it are the smoke suite's
          selectors (`#tb-actions`, `#tb-directions`, `#tb-route-summary`,
          `#tb-dropdown`, `#tb-corridor*`). Kept so `SmokeTest.kt` addresses one
          DOM contract while both trees exist. */}
      <div className="tb-actions" id="tb-actions">
        {isDirections && planner.stops.length < MAX_STOPS ? (
          <button type="button" className="tb-add" onClick={planner.addStop}>
            + Add stop
          </button>
        ) : null}

        {route.summary ? (
          <span className="tb-route-summary" id="tb-route-summary" aria-live="polite">
            <strong>{route.summary.distance}</strong>
            <span className="tb-stat-sep">·</span>
            {route.summary.duration}
          </span>
        ) : null}

        <div className="tb-actions-spacer" />

        {/* The entry point into directions mode from the search bar. It is the only
            path for a geocoded pick, which has no drawer button to press. */}
        {!isDirections && isLocated(planner.stops[0] ?? null) ? (
          <button
            type="button"
            className="tb-icon-btn primary"
            id="tb-directions"
            title="Get directions"
            aria-label="Get directions"
            onClick={planner.startDirections}
          >
            <DirectionsIcon />
          </button>
        ) : null}

        {planner.stops.length > 0 ? (
          <button
            type="button"
            className="tb-icon-btn"
            title="Clear trip"
            aria-label="Clear trip"
            onClick={() => {
              planner.clearAll();
              setDraft(null);
            }}
          >
            <CloseIcon />
          </button>
        ) : null}
      </div>

      <SearchDropdown
        results={results.slice(0, MAX_SEARCH_RESULTS)}
        activeIndex={activeResult}
        onPick={pick}
      />

      {/* Precedence, stated once: a routing failure is about the trip the user just
          asked for, so it outranks a geolocation failure about one row, which in
          turn outranks a bad link they cannot do anything about. */}
      <RouteStatus
        computing={route.isFetching}
        error={route.error ?? planner.locationError ?? shared.error}
        legs={route.legs}
      />

      {/* The results section owns the corridor slider, because the radius is a property
          of this list: it is what decides which campgrounds are in it. That nesting is
          also the DOM contract `SmokeTest.kt` asserts —
          `#tb-results .tb-results-body #tb-corridor` must be visible.

          It appears only with a live route: without one there is nothing for a radius
          to be a radius of, and nothing to be "along". */}
      {allStopsFilled(planner.stops) && route.route ? (
        <TripResults
          cards={cards}
          loading={corridor.isFetching}
          corridorMiles={planner.corridorMiles}
          onCorridorMilesChange={planner.setCorridorMiles}
        />
      ) : null}
    </div>
  );
}

/**
 * Publish `window.__rtUseCurrentLocationForTripStop`.
 *
 * A test seam, not dead API: `SmokeTest.kt` (~line 803) calls it with a seeded
 * location to drive the "from my location" path without a real permission prompt,
 * then asserts row 0 reads "Current location". The Phase 0 audit recorded this
 * global as read by nothing and left it out of the transition shim; that was wrong,
 * and a React `/` without it fails that smoke step against a page that works.
 *
 * It lives here rather than in the shim because filling a row needs the planner,
 * and the shim has no hooks. Removed on unmount, so a remounted topbar cannot leave
 * a stale closure behind.
 */
function usePublishedLocationFiller(useCurrentLocation: (index: number) => void): void {
  useEffect(() => {
    window.__rtUseCurrentLocationForTripStop = (index, location) => {
      if (location && Number.isFinite(location.lng) && Number.isFinite(location.lat)) {
        useMapStore.getState().setUserLocation({ lng: location.lng, lat: location.lat });
      }
      useCurrentLocation(index);
    };
    return () => {
      delete window.__rtUseCurrentLocationForTripStop;
    };
  }, [useCurrentLocation]);
}

function DirectionsIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M21 10l-7 7-3-3-9 9" />
      <path d="M14 10h7v7" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  );
}
