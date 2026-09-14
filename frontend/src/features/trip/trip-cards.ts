// The corridor's campgrounds, as cards.
//
// The shape of the problem is why this is split out. `/api/pois/on-route` answers with
// SLIM features — an id, a point, a category, an agency — so a card starts as a
// placeholder and gains its name, type and region when `GET /api/pois/{id}` lands.
// Sorting, filtering and copy all have to work on both.
import { distanceKm } from '@/lib/geo';
import { UNCATEGORIZED_AGENCY } from '@/map/agencies';
import type { TripStop } from '@/stores/tripStore';
import { distanceAlongRouteKm, type RouteIndex } from './route-index';

export interface TripCard {
  id: string | number;
  /** "Campground" until the detail request lands. */
  name: string;
  /** The type label, e.g. "Standard campground". */
  sub: string;
  /** State or country, shown to the right of the name. */
  location: string;
  agency: string;
  lng: number;
  lat: number;
  /** Kilometres along the route — the sort key with a route; null without one. */
  routeKm: number | null;
  /** Straight-line kilometres from the origin, or from the map centre with no route. */
  distKm: number;
  /** True when the campground has a booking provider we can ask (`availability_supported`). */
  checkable: boolean;
  /** Average rating, when the detail carries one. */
  rating: number | null;
  hydrated: boolean;
}

/**
 * What the corridor endpoint gives us, as much of it as a card needs.
 *
 * Structural rather than the api client's `Feature<Point, PoiPinProperties>`, because
 * an interface with named properties does not satisfy `Record<string, unknown>` — and
 * because this reads `properties.id` as a fallback, which the pinned wire type does not
 * declare (it is there on some provider payloads and the vanilla looked for it).
 */
interface SlimFeature {
  /** Accepted and ignored, so a real GeoJSON feature fits without a cast. */
  type?: string;
  id?: string | number;
  geometry?: { type?: string; coordinates?: unknown } | null;
  properties?: { agency?: unknown; id?: unknown } | null;
}

const PLACEHOLDER_NAME = 'Campground';

interface CardBase {
  id: string | number;
  lng: number;
  lat: number;
  agency: string;
}

/** What a slim feature contributes to a card, or null when it cannot be one. */
function cardBaseOf(feature: SlimFeature): CardBase | null {
  const id = feature?.id ?? (feature?.properties?.id as string | number | undefined);
  if (id == null) return null;
  const coordinates = feature?.geometry?.coordinates;
  if (!Array.isArray(coordinates)) return null;
  const [lng, lat] = coordinates as [unknown, unknown];
  if (typeof lng !== 'number' || !Number.isFinite(lng)) return null;
  if (typeof lat !== 'number' || !Number.isFinite(lat)) return null;
  return { id, lng, lat, agency: (feature.properties?.agency as string | undefined) || '' };
}

function placeholderCard(base: CardBase, routeKm: number | null, distKm: number): TripCard {
  return {
    ...base,
    name: PLACEHOLDER_NAME,
    sub: '',
    location: '',
    routeKm,
    distKm,
    checkable: false,
    rating: null,
    hydrated: false,
  };
}

/**
 * Placeholder cards from a fresh corridor response, in the order a driver meets them.
 *
 * Features with no id or no usable coordinates are dropped rather than rendered: the
 * id is what hydration and the click-through both need, and a card with neither is a
 * row that cannot do anything.
 */
export function tripCardsFromFeatures(
  features: readonly SlimFeature[] | null | undefined,
  origin: TripStop | null | undefined,
  routeIndex: RouteIndex | null,
): TripCard[] {
  const cards: TripCard[] = [];
  for (const feature of features ?? []) {
    const base = cardBaseOf(feature);
    if (!base) continue;
    cards.push(
      placeholderCard(
        base,
        distanceAlongRouteKm(routeIndex, base.lng, base.lat),
        origin ? distanceKm(origin.lat, origin.lng, base.lat, base.lng) : 0,
      ),
    );
  }
  // The order the driver encounters them, which is the only ordering that makes the
  // list useful — see `route-index.ts`.
  return cards.sort((a, b) => (a.routeKm ?? 0) - (b.routeKm ?? 0));
}

/** Fold a hydrated POI's flattened properties into its placeholder card. */
export function hydrateCard(
  card: TripCard,
  properties: Record<string, unknown> | null | undefined,
): TripCard {
  const p = properties ?? {};
  const rating = p.rating as { average?: unknown } | null | undefined;
  return {
    ...card,
    name: (p.name as string | undefined) || PLACEHOLDER_NAME,
    sub: (p.typeLabel as string | undefined) || '',
    location: (p.state as string | undefined) || (p.country as string | undefined) || '',
    agency: (p.agency as string | undefined) || card.agency,
    checkable: p.availability_supported === true,
    rating: typeof rating?.average === 'number' ? rating.average : null,
    hydrated: true,
  };
}

export interface CardFilter {
  /** Agencies the legend has switched off. */
  hiddenAgencies: readonly string[];
  /** True when the legend has switched campgrounds off entirely. */
  campgroundsHidden: boolean;
}

/**
 * The cards the map is currently showing pins for.
 *
 * The list has to agree with the map, because clicking a card flies to its pin — a
 * row for a hidden pin is a row that flies the camera to nothing. The vanilla filtered
 * by agency only; the overlay check is new because 4b's legend can switch campgrounds
 * off wholesale, which the vanilla legend could not.
 */
export function visibleCards(
  cards: readonly TripCard[],
  { hiddenAgencies, campgroundsHidden }: CardFilter,
): TripCard[] {
  if (campgroundsHidden) return [];
  if (hiddenAgencies.length === 0) return [...cards];
  const hidden = new Set(hiddenAgencies);
  return cards.filter((card) => !hidden.has(card.agency || UNCATEGORIZED_AGENCY));
}
