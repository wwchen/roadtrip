import { beforeEach, describe, expect, test } from 'vitest';
import {
  MAX_GROUP_SIZE,
  MIN_GROUP_SIZE,
  selectActiveFilterCount,
  selectCatalogFilterCount,
  selectFilterDto,
  useCampgroundFilterStore,
} from './campgroundFilterStore';

const filters = () => useCampgroundFilterStore.getState();

beforeEach(() => filters().reset());

describe('initial state', () => {
  test('nothing is active and no dates are set', () => {
    expect(filters()).toMatchObject({ siteType: null, groupSize: null, amenities: [], agencies: [], dateWindow: null });
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

    filters().toggleAgency('National Park Service');
    filters().clearFilters();

    expect(filters().siteType).toBeNull();
    expect(filters().agencies).toEqual([]);
    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
  });
});

describe('agencies', () => {
  test('toggling an agency adds it, toggling again removes it', () => {
    filters().toggleAgency('USDA Forest Service');
    filters().toggleAgency('National Park Service');
    expect(filters().agencies).toEqual(['USDA Forest Service', 'National Park Service']);

    filters().toggleAgency('USDA Forest Service');
    expect(filters().agencies).toEqual(['National Park Service']);
  });

  test('an agency pick counts on the badge but never reaches the search request', () => {
    filters().toggleAgency('BC Parks');

    expect(selectActiveFilterCount(filters())).toBe(1);
    expect(selectCatalogFilterCount(filters())).toBe(0);
    expect(selectFilterDto(filters())).toBeUndefined();
  });

  test('the agency pick and the catalog filters add up on the badge', () => {
    filters().toggleAgency('BC Parks');
    filters().toggleAgency('Parks Canada');
    filters().setSiteType('tent');

    expect(selectActiveFilterCount(filters())).toBe(2);
    expect(selectCatalogFilterCount(filters())).toBe(1);
    expect(selectFilterDto(filters())).toEqual({ site_type: 'tent' });
  });

  test('includeAgency leaves an empty pick empty, since every agency already shows', () => {
    filters().includeAgency('WA Parks');
    expect(filters().agencies).toEqual([]);
  });

  test('includeAgency adds a missing agency to a non-empty pick, once', () => {
    filters().toggleAgency('USFS');
    filters().includeAgency('WA Parks');
    filters().includeAgency('WA Parks');
    expect(filters().agencies).toEqual(['USFS', 'WA Parks']);
  });

  test('clearAgencies empties the pick and leaves the catalog filters', () => {
    filters().toggleAgency('USFS');
    filters().setSiteType('rv');
    filters().clearAgencies();
    expect(filters().agencies).toEqual([]);
    expect(filters().siteType).toBe('rv');
  });
});
