import { describe, expect, test } from 'vitest';
import { FILTER_AMENITIES, FILTER_SITE_TYPES, amenityLabel, kindLabel } from './campground-vocab';

describe('campground vocabulary', () => {
  test('exposes the three kinds and four amenities the design names', () => {
    expect(FILTER_SITE_TYPES.map((o) => o.value)).toEqual(['tent', 'rv', 'cabin']);
    expect(FILTER_AMENITIES.map((o) => o.key)).toEqual(['toilets', 'showers', 'water', 'pets_allowed']);
  });

  test('labels are the backend wording, and an unexposed key falls back to itself', () => {
    expect(kindLabel('rv')).toBe('RV');
    expect(amenityLabel('pets_allowed')).toBe('Pets allowed');
    expect(amenityLabel('wifi')).toBe('wifi');
  });
});
