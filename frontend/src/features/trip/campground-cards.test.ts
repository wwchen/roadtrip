import { describe, expect, test } from 'vitest';
import type { CampgroundSummary } from '@/api/campground-api';
import { cardsFromSummaries, countLine } from './campground-cards';

const summary = (over: Partial<CampgroundSummary> & Pick<CampgroundSummary, 'id'>): CampgroundSummary => ({
  campground_id: over.id * 10,
  name: `Camp ${over.id}`,
  lng: -120,
  lat: 39,
  availability_supported: true,
  amenities: [],
  site_counts: { tent: 48, rv: 33 },
  site_total: 81,
  ...over,
});

describe('cardsFromSummaries', () => {
  test('keeps the server order, skips ids without a summary, and hydrates the card', () => {
    const byId = new Map([
      [2, summary({ id: 2, name: 'Nevada Beach', region: 'NV', agency: 'USDA Forest Service', rating: { average: 4.6, count: 12 } })],
      // 1 degree of latitude away from the map centre, ~111 km — far enough that
      // a bug collapsing distance to the centre itself would not go unnoticed.
      [1, summary({ id: 1, availability_supported: false, lat: 40 })],
    ]);

    const cards = cardsFromSummaries([2, 3, 1], byId, { lng: -120, lat: 39 });

    expect(cards.map((c) => c.id)).toEqual([2, 1]);
    expect(cards[0]).toMatchObject({
      name: 'Nevada Beach',
      location: 'NV',
      agency: 'USDA Forest Service',
      rating: 4.6,
      checkable: true,
      hydrated: true,
      routeKm: null,
    });
    expect(cards[1]?.checkable).toBe(false);
    expect(cards[0]?.distKm).toBeCloseTo(0, 5);
    expect(cards[1]?.distKm).toBeGreaterThan(100);
    expect(cards[1]?.distKm).toBeLessThan(120);
  });
});

describe('countLine', () => {
  test('is the plain total with no type selected', () => {
    expect(countLine(summary({ id: 1 }), null)).toBe('81 sites');
  });

  test('is type-qualified with the total when they differ', () => {
    expect(countLine(summary({ id: 1 }), 'tent')).toBe('48 tent sites · 81 total');
  });

  test('keeps acronyms uppercase', () => {
    expect(countLine(summary({ id: 1 }), 'rv')).toBe('33 RV sites · 81 total');
  });

  test('drops the total when every site is of the kind', () => {
    expect(countLine(summary({ id: 1, site_counts: { tent: 81 } }), 'tent')).toBe('81 tent sites');
  });

  test('says zero of the kind when the campground has none', () => {
    expect(countLine(summary({ id: 1 }), 'cabin')).toBe('0 cabin sites · 81 total');
  });

  test('renders nothing for an empty catalog', () => {
    expect(countLine(summary({ id: 1, site_counts: {}, site_total: 0 }), 'tent')).toBeNull();
  });
});
