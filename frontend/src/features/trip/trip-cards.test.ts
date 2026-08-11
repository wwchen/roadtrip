import { describe, expect, test } from 'vitest';
import type { LineString } from 'geojson';
import type { TripStop } from '@/stores/tripStore';
import { buildRouteIndex, distanceAlongRouteKm } from './route-index';
import {
  compactSeasonLabel,
  hydrateCard,
  parseRating,
  tripCardsFromFeatures,
  visibleCards,
  type TripCard,
} from './trip-cards';

/** Seattle → Bellingham, roughly north along the I-5 corridor. */
const line: LineString = {
  type: 'LineString',
  coordinates: [
    [-122.33, 47.6],
    [-122.33, 48.0],
    [-122.48, 48.75],
  ],
};

const origin: TripStop = { name: 'Seattle', lng: -122.33, lat: 47.6 };

const slim = (id: number, lng: number, lat: number, agency = 'WA Parks') => ({
  type: 'Feature' as const,
  id,
  geometry: { type: 'Point' as const, coordinates: [lng, lat] },
  properties: { category: 'campground', agency },
});

const card = (over: Partial<TripCard> = {}): TripCard => ({
  id: 1,
  name: 'Campground',
  sub: '',
  location: '',
  agency: 'WA Parks',
  lng: -122.4,
  lat: 48.1,
  routeKm: 50,
  distKm: 60,
  sites: null,
  season: null,
  reservable: undefined,
  rating: null,
  hydrated: false,
  ...over,
});

describe('buildRouteIndex', () => {
  test('accumulates real distances along the line', () => {
    const index = buildRouteIndex(line)!;

    expect(index.cum[0]).toBe(0);
    // ~44km for 0.4° of latitude, then ~85km more.
    expect(index.cum[1]).toBeGreaterThan(40);
    expect(index.cum[2]).toBeGreaterThan(index.cum[1]!);
  });

  test('answers null for a line with nothing in it', () => {
    expect(buildRouteIndex({ type: 'LineString', coordinates: [] })).toBeNull();
    expect(buildRouteIndex(null)).toBeNull();
  });
});

describe('distanceAlongRouteKm', () => {
  const index = buildRouteIndex(line)!;

  test('is zero at the start and the whole length at the end', () => {
    expect(distanceAlongRouteKm(index, -122.33, 47.6)).toBeCloseTo(0, 1);
    expect(distanceAlongRouteKm(index, -122.48, 48.75)).toBeCloseTo(index.cum[2]!, 1);
  });

  test('projects a point beside the route onto it', () => {
    const km = distanceAlongRouteKm(index, -122.5, 48.0);

    expect(km).toBeGreaterThan(index.cum[1]! - 5);
    expect(km).toBeLessThan(index.cum[1]! + 15);
  });

  test('clamps a point before the start', () => {
    expect(distanceAlongRouteKm(index, -122.33, 47.0)).toBeCloseTo(0, 1);
  });

  test('answers zero with no index', () => {
    expect(distanceAlongRouteKm(null, -122, 47)).toBe(0);
  });

  test('survives a zero-length segment', () => {
    const doubled = buildRouteIndex({
      type: 'LineString',
      coordinates: [
        [-122.33, 47.6],
        [-122.33, 47.6],
        [-122.33, 48.0],
      ],
    })!;

    expect(Number.isFinite(distanceAlongRouteKm(doubled, -122.3, 47.8))).toBe(true);
  });
});

describe('tripCardsFromFeatures', () => {
  const index = buildRouteIndex(line);

  test('sorts by distance along the route, not from the origin', () => {
    const cards = tripCardsFromFeatures(
      [slim(1, -122.47, 48.7), slim(2, -122.33, 47.7)],
      origin,
      index,
    );

    expect(cards.map((c) => c.id)).toEqual([2, 1]);
    expect(cards[0]!.routeKm).toBeLessThan(cards[1]!.routeKm);
  });

  test('starts every card as a placeholder', () => {
    const [only] = tripCardsFromFeatures([slim(7, -122.4, 48.1)], origin, index);

    expect(only).toMatchObject({ id: 7, name: 'Campground', hydrated: false, agency: 'WA Parks' });
    expect(only!.distKm).toBeGreaterThan(0);
  });

  test('drops features with no id or no usable point', () => {
    const cards = tripCardsFromFeatures(
      [
        { type: 'Feature', geometry: { type: 'Point', coordinates: [-122, 48] }, properties: {} },
        { ...slim(3, -122.4, 48.1), geometry: { type: 'Point', coordinates: ['x', 48] as never } },
        slim(4, -122.4, 48.1),
      ],
      origin,
      index,
    );

    expect(cards.map((c) => c.id)).toEqual([4]);
  });

  test('accepts an id carried in properties', () => {
    const cards = tripCardsFromFeatures(
      [{ type: 'Feature', geometry: { type: 'Point', coordinates: [-122.4, 48.1] }, properties: { id: 9 } }],
      origin,
      index,
    );

    expect(cards.map((c) => c.id)).toEqual([9]);
  });

  test('handles a missing list and a missing origin', () => {
    expect(tripCardsFromFeatures(undefined, origin, index)).toEqual([]);
    expect(tripCardsFromFeatures([slim(1, -122.4, 48.1)], null, index)[0]!.distKm).toBe(0);
  });
});

describe('hydrateCard', () => {
  test('folds in the fields a card shows', () => {
    const hydrated = hydrateCard(card(), {
      name: 'Bay View State Park',
      typeLabel: 'Standard campground',
      state: 'WA',
      sites: '46',
      season: 'Open year-round',
      reservable: true,
      rating_reviews: [4.6, 812],
    });

    expect(hydrated).toMatchObject({
      name: 'Bay View State Park',
      sub: 'Standard campground',
      location: 'WA',
      sites: 46,
      hydrated: true,
      rating: [4.6, 812],
    });
  });

  test('keeps the placeholder name when the detail has none', () => {
    expect(hydrateCard(card(), {}).name).toBe('Campground');
  });

  test('keeps the slim agency when the detail omits it', () => {
    expect(hydrateCard(card({ agency: 'WA Parks' }), { name: 'X' }).agency).toBe('WA Parks');
  });

  test('falls back to country when there is no state', () => {
    expect(hydrateCard(card(), { country: 'Canada' }).location).toBe('Canada');
  });

  test('ignores a site count that is not a number', () => {
    expect(hydrateCard(card(), { sites: 'lots' }).sites).toBeNull();
  });
});

describe('parseRating', () => {
  test('reads both shapes', () => {
    expect(parseRating([4.5, 100])).toEqual([4.5, 100]);
    expect(parseRating('[4.5,100]')).toEqual([4.5, 100]);
  });

  test('refuses anything else, without throwing', () => {
    expect(parseRating('not json')).toBeNull();
    expect(parseRating(undefined)).toBeNull();
    expect(parseRating(4.5)).toBeNull();
    expect(parseRating(['4.5'])).toBeNull();
  });
});

describe('compactSeasonLabel', () => {
  test('says the useful thing in the space available', () => {
    expect(compactSeasonLabel('Open through October 25', undefined)).toBe('Open through October 25');
    expect(compactSeasonLabel('Open May–Oct (boat access)', undefined)).toBe('Open May–Oct');
  });

  test('collapses any year-round phrasing to one word', () => {
    expect(compactSeasonLabel('Open year-round', undefined)).toBe('Year-round');
    expect(compactSeasonLabel('year round (boat access)', undefined)).toBe('Year-round');
  });

  test('names a first-come campground when there is no season', () => {
    expect(compactSeasonLabel(null, false)).toBe('First-come');
    expect(compactSeasonLabel(null, true)).toBe('');
    expect(compactSeasonLabel(undefined, undefined)).toBe('');
  });

  test('truncates rather than blowing the card width', () => {
    const label = compactSeasonLabel('Open from the middle of May until early October', undefined);

    expect(label).toHaveLength(27);
    expect(label.endsWith('…')).toBe(true);
  });
});

describe('visibleCards', () => {
  const cards = [card({ id: 1, agency: 'WA Parks' }), card({ id: 2, agency: '' })];

  test('shows everything with nothing hidden', () => {
    expect(
      visibleCards(cards, { hiddenAgencies: [], campgroundsHidden: false }).map((c) => c.id),
    ).toEqual([1, 2]);
  });

  test('drops the agencies the legend switched off', () => {
    expect(
      visibleCards(cards, { hiddenAgencies: ['WA Parks'], campgroundsHidden: false }).map(
        (c) => c.id,
      ),
    ).toEqual([2]);
  });

  test('an agency-less card is hidden as Uncategorized', () => {
    expect(
      visibleCards(cards, { hiddenAgencies: ['Uncategorized'], campgroundsHidden: false }).map(
        (c) => c.id,
      ),
    ).toEqual([1]);
  });

  test('shows nothing when campgrounds are off entirely', () => {
    expect(visibleCards(cards, { hiddenAgencies: [], campgroundsHidden: true })).toEqual([]);
  });
});
