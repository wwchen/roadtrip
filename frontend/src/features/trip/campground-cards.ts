// The in-view list's cards, from the bulk summaries the search hands us.
import type { CampgroundSummary } from '@/api/campground-api';
import type { CampsiteKind } from '@/api/generated/api-types';
import { distanceKm } from '@/lib/geo';
import { filterCopy, inViewCopy } from '@/lib/strings';
import { kindLabel } from '@/lib/campground-vocab';
import type { MapCenter } from '@/map/viewport';
import type { CampgroundFilterState } from '@/stores/campgroundFilterStore';
import { facetsFor } from './facets';
import type { TripCard } from './trip-cards';

/** A card that carries the summary it was built from, for the count line and facets. */
export interface InViewCard extends TripCard {
  summary: CampgroundSummary;
}

/**
 * Cards in the order the search returned (nearest the view's centre first). An id
 * whose summary has not landed yet is skipped rather than shown as a placeholder:
 * the bulk read answers for the whole set at once, so there is no per-card wait.
 */
export function cardsFromSummaries(
  ids: readonly number[],
  byId: ReadonlyMap<number, CampgroundSummary>,
  center: MapCenter | null,
): InViewCard[] {
  const cards: InViewCard[] = [];
  for (const id of ids) {
    const summary = byId.get(id);
    if (!summary) continue;
    cards.push({
      id,
      name: summary.name,
      sub: '',
      location: summary.region ?? '',
      agency: summary.agency ?? '',
      lng: summary.lng,
      lat: summary.lat,
      routeKm: null,
      distKm: center ? distanceKm(center.lat, center.lng, summary.lat, summary.lng) : 0,
      checkable: summary.availability_supported,
      rating: summary.rating?.average ?? null,
      hydrated: true,
      summary,
    });
  }
  return cards;
}

/**
 * Known matches first, then cards with a no-data facet — nearest-first order
 * kept within each group. Stable and allocation-free when nothing moves: the
 * common case, with no filter active, returns the same array instance.
 */
export function rankCards(
  cards: readonly InViewCard[],
  filter: Pick<CampgroundFilterState, 'siteType' | 'groupSize' | 'amenities'>,
): InViewCard[] {
  const hasNoData = (card: InViewCard) =>
    facetsFor(card.summary, filter).some((facet) => facet.state === 'no-data');

  const known: InViewCard[] = [];
  const unknown: InViewCard[] = [];
  for (const card of cards) {
    (hasNoData(card) ? unknown : known).push(card);
  }
  if (unknown.length === 0 || known.length === 0) return cards as InViewCard[];
  return [...known, ...unknown];
}

/** The card's count line; null for an empty catalog. */
export function countLine(summary: CampgroundSummary, siteType: CampsiteKind | null): string | null {
  if (summary.site_total === 0) return null;
  if (!siteType) return inViewCopy.sites(summary.site_total);
  return filterCopy.kindSites(summary.site_counts[siteType] ?? 0, kindLabel(siteType), summary.site_total);
}
