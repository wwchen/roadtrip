// What the topbar says about a computed route.
//
// The formatting half of `showRouteSummary` / `formatRouteKm` / `formatDuration`
// and the error mapping inside `tryFetchRoute` in web/topbar.js. Pure, so the copy
// can be tested without a route request.
//
// `formatDrivingTime` is deliberately NOT `lib/format.ts`'s `formatDuration`,
// which renders `45s` / `3m 20s` for the operations dashboard. A routing engine's
// seconds are noise, and showing them invites the user to trust them.
import { SECONDS_PER_MINUTE } from '@/lib/format';
import { stopLabel, type StopSlot } from './stops';

const METRES_PER_KM = 1000;
const MINUTES_PER_HOUR = 60;
/** Below this, one decimal place still means something on a road. */
const PRECISE_KM_LIMIT = 10;

export interface RouteLeg {
  distance_m: number;
  duration_s: number;
}

export interface RouteProperties {
  distance_m?: number;
  duration_s?: number;
  legs?: RouteLeg[];
}

export interface RouteSummary {
  /** "1,842 km" — the whole trip. */
  distance: string;
  /** "18h 20m". */
  duration: string;
}

export interface RouteLegLine {
  from: string;
  to: string;
  distance: string;
  duration: string;
}

/**
 * A whole-trip distance: whole kilometres, with a thousands separator.
 *
 * No decimal, because a routing engine's tenth of a kilometre over 1,800 of them
 * is false precision — and the separator because "1842 km" is harder to read at
 * 11px than "1,842 km".
 */
export function formatTotalKm(metres: number | undefined): string {
  const km = (metres ?? 0) / METRES_PER_KM;
  return `${Math.round(km).toLocaleString('en-US')} km`;
}

/**
 * How far along the route a POI sits — "12 km in", not "12 km away".
 *
 * Carried over verbatim, including the phrasing: the number answers "how far into
 * the drive", and "away" would read as distance from the user.
 */
export function formatDistanceAlongRoute(km: number): string {
  if (km < 1) return `${Math.round(km * METRES_PER_KM)} m in`;
  if (km < PRECISE_KM_LIMIT) return `${km.toFixed(1)} km in`;
  return `${Math.round(km)} km in`;
}

/** A driving duration: minutes under an hour, hours and minutes above it. */
export function formatDrivingTime(seconds: number | undefined): string {
  const total = Math.max(0, Math.round((seconds ?? 0) / SECONDS_PER_MINUTE));
  if (total < MINUTES_PER_HOUR) return `${total}m`;
  const hours = Math.floor(total / MINUTES_PER_HOUR);
  const minutes = total % MINUTES_PER_HOUR;
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
}

export function routeSummary(properties: RouteProperties | null | undefined): RouteSummary | null {
  if (!properties) return null;
  return {
    distance: formatTotalKm(properties.distance_m),
    duration: formatDrivingTime(properties.duration_s),
  };
}

/**
 * The per-leg breakdown, or nothing when there is nothing to break down.
 *
 * A two-stop trip has exactly one leg, which is the total already on screen — the
 * vanilla showed the breakdown only for three or more stops, and this returns an
 * empty list rather than one redundant line.
 *
 * One deliberate change: legs use the same `formatDrivingTime` as the total, where
 * the vanilla printed raw minutes. A 6-hour leg reading "372 min" is not an
 * improvement over "6h 12m".
 */
export function routeLegLines(
  properties: RouteProperties | null | undefined,
  stops: readonly StopSlot[],
): RouteLegLine[] {
  const legs = properties?.legs ?? [];
  if (legs.length <= 1) return [];
  return legs.map((leg, index) => ({
    from: stopLabel(stops, index),
    to: stopLabel(stops, index + 1),
    distance: formatTotalKm(leg.distance_m),
    duration: formatDrivingTime(leg.duration_s),
  }));
}

/** What the routing endpoint can refuse, and what to tell the user about it. */
const ROUTE_ERROR_MESSAGES = new Map<string, string>([
  ['duplicate_adjacent', 'Two adjacent stops are the same.'],
  ['too_few_points', 'Need at least 2 stops.'],
  ['too_many_points', 'Too many stops.'],
  ['routing_unavailable', 'Routing temporarily unavailable.'],
]);

/**
 * Turn a routing failure into a sentence.
 *
 * A `Map`, not an object literal: a code naming an `Object.prototype` member
 * would otherwise resolve up the prototype chain and return a function — the same
 * defect the settings-error port fixed in Phase 3.
 *
 * The fallback names the HTTP status, because a routing failure with no code we
 * recognise is usually infrastructural and the number is the only actionable
 * detail. With no status at all it was a transport failure, which is a different
 * sentence.
 */
export function routeErrorMessage(code: string | null | undefined, status?: number): string {
  const known = code ? ROUTE_ERROR_MESSAGES.get(code) : undefined;
  if (known) return known;
  return status ? `Routing error (${status})` : 'Network error';
}
