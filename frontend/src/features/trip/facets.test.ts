import { describe, expect, test } from 'vitest';
import type { CampgroundSummary } from '@/api/campground-api';
import { facetsFor } from './facets';

const summary = (over: Partial<CampgroundSummary>): CampgroundSummary => ({
  id: 1,
  campground_id: 10,
  name: 'Camp',
  lng: -120,
  lat: 39,
  availability_supported: true,
  amenities: [],
  site_counts: { tent: 5 },
  site_total: 5,
  ...over,
});

const OFF = { siteType: null, groupSize: null, amenities: [] as const };

describe('facetsFor', () => {
  test('no active filter, no facets', () => {
    expect(facetsFor(summary({}), OFF)).toEqual([]);
  });

  test('a listed kind matches; an empty catalog is no data', () => {
    expect(facetsFor(summary({}), { ...OFF, siteType: 'tent' })).toEqual([{ key: 'site_type', label: 'Tent', state: 'match' }]);
    expect(facetsFor(summary({ site_counts: {}, site_total: 0 }), { ...OFF, siteType: 'tent' })).toEqual([
      { key: 'site_type', label: 'Tent', state: 'no-data' },
    ]);
  });

  test('a site type misses when the campground has none of it', () => {
    expect(facetsFor(summary({ site_counts: { rv: 20 }, site_total: 20 }), { ...OFF, siteType: 'tent' })).toEqual([
      { key: 'site_type', label: 'Tent', state: 'miss' },
    ]);
  });

  test('group size reads the cap, or says the data is missing', () => {
    expect(facetsFor(summary({ max_people: 8 }), { ...OFF, groupSize: 4 })).toEqual([
      { key: 'group_size', label: 'Up to 8', state: 'match' },
    ]);
    expect(facetsFor(summary({}), { ...OFF, groupSize: 4 })).toEqual([
      { key: 'group_size', label: 'Group size', state: 'no-data' },
    ]);
  });

  test('group size misses when the cap is smaller than the requested size', () => {
    expect(facetsFor(summary({ max_people: 2 }), { ...OFF, groupSize: 4 })).toEqual([
      { key: 'group_size', label: 'Up to 2', state: 'miss' },
    ]);
  });

  test('an amenity uses the card label when present, the vocabulary when unknown', () => {
    const withToilets = summary({ amenities: [{ key: 'toilets', label: 'Vault toilets', present: true }] });
    expect(facetsFor(withToilets, { ...OFF, amenities: ['toilets', 'showers'] })).toEqual([
      { key: 'toilets', label: 'Vault toilets', state: 'match' },
      { key: 'showers', label: 'Showers', state: 'no-data' },
    ]);
  });

  test('an amenity misses when present is false', () => {
    const noToilets = summary({ amenities: [{ key: 'toilets', label: 'No toilets', present: false }] });
    expect(facetsFor(noToilets, { ...OFF, amenities: ['toilets'] })).toEqual([
      { key: 'toilets', label: 'No toilets', state: 'miss' },
    ]);
  });

  test('facets come in filter order: type, group, amenities', () => {
    const facets = facetsFor(summary({ max_people: 6 }), { siteType: 'tent', groupSize: 2, amenities: ['water'] });
    expect(facets.map((f) => f.key)).toEqual(['site_type', 'group_size', 'water']);
  });
});
