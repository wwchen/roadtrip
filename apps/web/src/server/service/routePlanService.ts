import { config } from '../config';
import { createMapboxClient, GeocodeUnavailableError, RoutingUnavailableError, type Directions, type GeocodeOptions, type LngLat } from '../client/mapbox';
import type { GeocodeResponseDto, PoisOnRouteResponseSchema, RouteFeatureCollectionDto } from '../contract';
import { toContractId } from '../contractIds';
import { serverDb } from '../db';
import { SERVED_CATEGORIES } from '../poiCategories';
import { fetchPoisWithinPolygon } from '../repo/poiServingRepo';
import { bufferedCorridor, CorridorUnavailableError } from '../repo/routeCorridorRepo';

export const MILES_TO_METERS = 1609.34;
export const ROUTE_CACHE_TTL_MS = 10 * 60 * 1000;
export const ROUTE_CACHE_ENTRIES = 256;
const CACHE_KEY_DECIMALS = 6;

export type RoutePlanResult =
  | { status: 'planned'; body: RouteFeatureCollectionDto }
  | { status: 'duplicate_adjacent'; index: number }
  | { status: 'routing_unavailable'; detail: string }
  | { status: 'corridor_unavailable'; detail: string };

export interface RoutePlannerDeps {
  directions(coords: readonly LngLat[]): Promise<Directions>;
  corridor(lineGeoJson: string, radiusMeters: number): Promise<string>;
  now?: () => number;
}

const lineString = (coordinates: number[][]) => ({ type: 'LineString', coordinates });
const cacheKey = (coords: readonly LngLat[]) =>
  coords.map(([lng, lat]) => `${lng.toFixed(CACHE_KEY_DECIMALS)},${lat.toFixed(CACHE_KEY_DECIMALS)}`).join(';');

export function createRoutePlanner(deps: RoutePlannerDeps) {
  const now = deps.now ?? Date.now;
  const cache = new Map<string, { directions: Directions; expiresAt: number }>();

  async function cachedDirections(coords: readonly LngLat[]): Promise<Directions> {
    const key = cacheKey(coords);
    const hit = cache.get(key);
    if (hit !== undefined && hit.expiresAt > now()) return hit.directions;
    cache.delete(key);
    const directions = await deps.directions(coords);
    if (cache.size >= ROUTE_CACHE_ENTRIES) cache.delete(cache.keys().next().value as string);
    cache.set(key, { directions, expiresAt: now() + ROUTE_CACHE_TTL_MS });
    return directions;
  }

  async function corridorFor(directions: Directions, radiusMiles: number): Promise<string> {
    return deps.corridor(JSON.stringify(lineString(directions.coordinates)), radiusMiles * MILES_TO_METERS);
  }

  return {
    cachedDirections,
    corridorFor,
    async plan(coords: readonly LngLat[], radiusMiles: number | null): Promise<RoutePlanResult> {
      const duplicate = coords.findIndex((point, index) => index > 0 && point[0] === coords[index - 1]?.[0] && point[1] === coords[index - 1]?.[1]);
      if (duplicate > 0) return { status: 'duplicate_adjacent', index: duplicate };
      let directions: Directions;
      try {
        directions = await cachedDirections(coords);
      } catch (error) {
        if (error instanceof RoutingUnavailableError) return { status: 'routing_unavailable', detail: error.message };
        throw error;
      }
      const features: unknown[] = [
        {
          type: 'Feature',
          geometry: lineString(directions.coordinates),
          properties: {
            distance_m: directions.distanceMeters,
            duration_s: directions.durationSeconds,
            legs: directions.legs.map((leg) => ({ distance_m: leg.distanceMeters, duration_s: leg.durationSeconds })),
            waypoints: coords.map(([lng, lat]) => [lng, lat]),
          },
        },
      ];
      if (radiusMiles !== null) {
        try {
          const corridor = JSON.parse(await corridorFor(directions, radiusMiles));
          features.push({ type: 'Feature', geometry: corridor, properties: { role: 'corridor', radius_miles: radiusMiles } });
        } catch (error) {
          if (error instanceof CorridorUnavailableError) return { status: 'corridor_unavailable', detail: error.message };
          throw error;
        }
      }
      return { status: 'planned', body: { type: 'FeatureCollection', features } };
    },
  };
}

export type RoutePlanner = ReturnType<typeof createRoutePlanner>;

let planner: { token: string; planner: RoutePlanner } | undefined;

export function routePlanner(): RoutePlanner | null {
  const token = config.mapboxToken;
  if (token === undefined) return null;
  if (planner?.token !== token) {
    const mapbox = createMapboxClient({ token });
    planner = {
      token,
      planner: createRoutePlanner({
        directions: (coords) => mapbox.directions(coords),
        corridor: async (line, meters) => bufferedCorridor(await serverDb(), line, meters),
      }),
    };
  }
  return planner.planner;
}

export async function poisOnRoute(
  routes: RoutePlanner,
  coords: readonly LngLat[],
  radiusMiles: number,
  categories: string[] | null,
): Promise<PoisOnRouteResponseSchema> {
  const wanted = categories ?? [...SERVED_CATEGORIES];
  if (wanted.length === 0) return { type: 'FeatureCollection', features: [] };
  const polygon = await routes.corridorFor(await routes.cachedDirections(coords), radiusMiles);
  const rows = await fetchPoisWithinPolygon(await serverDb(), polygon, wanted, config.enabledDataProviders);
  return {
    type: 'FeatureCollection',
    features: rows.map((row) => ({
      type: 'Feature',
      id: toContractId(row.id),
      geometry: { type: 'Point', coordinates: [row.lng, row.lat] },
      properties: {
        category: row.category,
        ...(row.subcategory === null ? {} : { subcategory: row.subcategory }),
        ...(row.agency === null ? {} : { agency: row.agency }),
      },
    })),
  };
}

export async function geocode(query: string, options: GeocodeOptions): Promise<GeocodeResponseDto | null> {
  const token = config.mapboxToken;
  if (token === undefined) return null;
  return { results: await createMapboxClient({ token }).geocode(query, options) };
}

export { CorridorUnavailableError, GeocodeUnavailableError, RoutingUnavailableError };
