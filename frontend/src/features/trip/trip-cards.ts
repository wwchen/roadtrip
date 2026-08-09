// The corridor's campgrounds, as cards.
//
// Port of `setTripPois` / `hydrateTripCards`' merge step / `compactSeasonLabel` /
// `visibleCards` from web/topbar.js. Pure: the fetching is `useTripCards`, and the
// rendering is `TripResults`.
//
// The shape of the problem is why this is split out. `/api/pois/on-route` answers with
// SLIM features — an id, a point, a category, an agency — so a card starts as a
// placeholder and gains its name, site count, season and rating when
// `GET /api/pois/{id}` lands. Sorting, filtering and copy all have to work on both.
import { distanceKm } from '@/lib/geo';
import { UNCATEGORIZED_AGENCY } from '@/map/agencies';
import type { TripStop } from '@/stores/tripStore';
import { distanceAlongRouteKm, type RouteIndex } from './route-index';

/** Longer than this and a season string would blow the card's width. */
const MAX_SEASON_CHARS = 28;
const TRUNCATED_SEASON_CHARS = 26;

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
  /** Kilometres along the route — the sort key, and the card's headline number. */
  routeKm: number;
  /** Straight-line kilometres from the origin. Carried for parity; not rendered. */
  distKm: number;
  sites: number | null;
  season: string | null;
  /** `false` marks a first-come campground, which changes the season label. */
  reservable: boolean | undefined;
  /** `[rating, reviewCount]`, when the provider supplies one. */
  rating: readonly number[] | null;
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
    const id = feature?.id ?? (feature?.properties?.id as string | number | undefined);
    if (id == null) continue;
    const coordinates = feature?.geometry?.coordinates;
    if (!Array.isArray(coordinates)) continue;
    const [lng, lat] = coordinates as [unknown, unknown];
    if (typeof lng !== 'number' || !Number.isFinite(lng)) continue;
    if (typeof lat !== 'number' || !Number.isFinite(lat)) continue;

    cards.push({
      id,
      name: 'Campground',
      sub: '',
      location: '',
      agency: (feature.properties?.agency as string | undefined) || '',
      lng,
      lat,
      routeKm: distanceAlongRouteKm(routeIndex, lng, lat),
      distKm: origin ? distanceKm(origin.lat, origin.lng, lat, lng) : 0,
      sites: null,
      season: null,
      reservable: undefined,
      rating: null,
      hydrated: false,
    });
  }
  // The order the driver encounters them, which is the only ordering that makes the
  // list useful — see `route-index.ts`.
  return cards.sort((a, b) => a.routeKm - b.routeKm);
}

/**
 * A rating pair, from either shape the backend sends it in.
 *
 * `rating_reviews` arrives as an array from the POI detail endpoint and as a JSON
 * string from some provider payloads; the vanilla parsed both, and a card that
 * silently loses its stars for one provider is worse than the parse.
 */
export function parseRating(value: unknown): readonly number[] | null {
  if (Array.isArray(value)) return value.every((n) => typeof n === 'number') ? value : null;
  if (typeof value !== 'string') return null;
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/** Fold a hydrated POI's flattened properties into its placeholder card. */
export function hydrateCard(
  card: TripCard,
  properties: Record<string, unknown> | null | undefined,
): TripCard {
  const p = properties ?? {};
  const sites = Number(p.sites);
  return {
    ...card,
    name: (p.name as string | undefined) || 'Campground',
    sub: (p.typeLabel as string | undefined) || '',
    location: (p.state as string | undefined) || (p.country as string | undefined) || '',
    agency: (p.agency as string | undefined) || card.agency,
    sites: Number.isFinite(sites) ? sites : null,
    season: (p.season as string | undefined) || null,
    reservable: p.reservable as boolean | undefined,
    rating: parseRating(p.rating_reviews),
    hydrated: true,
  };
}

/**
 * The season, in the few words a card has room for.
 *
 * A lightweight re-implementation of what the drawer parses, kept deliberately
 * independent of it: the card list must not pull the drawer's detail modules in to
 * render one line of text. Returns '' when there is nothing worth asserting — but a
 * campground the provider marks unreservable gets "First-come", because that is the
 * single most useful thing to know about it from a list.
 */
export function compactSeasonLabel(
  season: string | null | undefined,
  reservable: boolean | undefined,
): string {
  if (!season) return reservable === false ? 'First-come' : '';
  if (/year[\s-]*round/i.test(season)) return 'Year-round';
  // Parenthetical qualifiers ("year-round (boat access)") are the first thing to go.
  const cleaned = season.replace(/\s*\([^)]*\)/g, '').trim();
  return cleaned.length > MAX_SEASON_CHARS
    ? `${cleaned.slice(0, TRUNCATED_SEASON_CHARS)}…`
    : cleaned;
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
