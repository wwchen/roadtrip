import { beforeEach, describe, expect, test } from 'vitest';
import {
  MAX_GROUP_SIZE,
  MIN_GROUP_SIZE,
  selectActiveFilterCount,
  selectFilterDto,
  useCampgroundFilterStore,
} from './campgroundFilterStore';

const filters = () => useCampgroundFilterStore.getState();

beforeEach(() => filters().reset());

describe('initial state', () => {
  test('nothing is active and no dates are set', () => {
    expect(filters()).toMatchObject({ siteType: null, groupSize: null, amenities: [], dateWindow: null });
    expect(selectActiveFilterCount(filters())).toBe(0);
    expect(selectFilterDto(filters())).toBeUndefined();
  });
});

describe('filters', () => {
  test('site type, group size and amenities each count as one active filter', () => {
    filters().setSiteType('tent');
    filters().setGroupSize(4);
    filters().toggleAmenity('toilets');
    filters().toggleAmenity('showers');

    expect(selectActiveFilterCount(filters())).toBe(3);
    expect(selectFilterDto(filters())).toEqual({ site_type: 'tent', group_size: 4, amenities: ['toilets', 'showers'] });
  });

  test('toggling an amenity twice removes it and the dto omits the empty list', () => {
    filters().toggleAmenity('toilets');
    filters().toggleAmenity('toilets');

    expect(filters().amenities).toEqual([]);
    expect(selectFilterDto(filters())).toBeUndefined();
  });

  test('group size is clamped to the stepper range', () => {
    filters().setGroupSize(MAX_GROUP_SIZE + 5);
    expect(filters().groupSize).toBe(MAX_GROUP_SIZE);

    filters().setGroupSize(MIN_GROUP_SIZE - 1);
    expect(filters().groupSize).toBeNull();
  });

  test('clearFilters keeps the date window', () => {
    filters().setSiteType('rv');
    filters().setDateWindow({ start: '2026-09-11', end: '2026-09-13' });

    filters().clearFilters();

    expect(filters().siteType).toBeNull();
    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
  });
});
