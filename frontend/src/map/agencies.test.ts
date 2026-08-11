import { describe, expect, test } from 'vitest';
import type { PinFeature } from './pins';
import {
  UNCATEGORIZED_AGENCY,
  agencyCounts,
  featureAgency,
  hiddenAgencyFilter,
  sortedAgencies,
} from './agencies';

const campground = (agency?: string | null, id = 1): PinFeature => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [-121, 40] },
  properties: agency === undefined ? { category: 'campground' } : { category: 'campground', agency: agency as string },
});

describe('featureAgency', () => {
  test('reads the agency', () => {
    expect(featureAgency(campground('US Forest Service'))).toBe('US Forest Service');
  });

  test('trims it', () => {
    expect(featureAgency(campground('  BC Parks '))).toBe('BC Parks');
  });

  test('falls back to the sentinel for missing, empty and blank agencies', () => {
    expect(featureAgency(campground())).toBe(UNCATEGORIZED_AGENCY);
    expect(featureAgency(campground(''))).toBe(UNCATEGORIZED_AGENCY);
    expect(featureAgency(campground('   '))).toBe(UNCATEGORIZED_AGENCY);
    expect(featureAgency(null)).toBe(UNCATEGORIZED_AGENCY);
  });
});

describe('agencyCounts', () => {
  test('counts campgrounds per agency', () => {
    const counts = agencyCounts([
      campground('US Forest Service', 1),
      campground('US Forest Service', 2),
      campground('BC Parks', 3),
    ]);

    expect(counts.get('US Forest Service')).toBe(2);
    expect(counts.get('BC Parks')).toBe(1);
  });

  test('agency-less campgrounds land under the sentinel row', () => {
    expect(agencyCounts([campground(), campground('')]).get(UNCATEGORIZED_AGENCY)).toBe(2);
  });

  test('ignores anything that is not a campground', () => {
    const supercharger = {
      type: 'Feature',
      id: 9,
      geometry: { type: 'Point', coordinates: [-121, 40] },
      properties: { category: 'tesla_supercharger' },
    } as PinFeature;

    expect(agencyCounts([supercharger]).size).toBe(0);
  });

  test('an empty viewport has no rows', () => {
    expect(agencyCounts([]).size).toBe(0);
  });
});

describe('sortedAgencies', () => {
  test('orders rows alphabetically', () => {
    const counts = agencyCounts([
      campground('US Forest Service', 1),
      campground('BC Parks', 2),
      campground('National Park Service', 3),
    ]);

    expect(sortedAgencies(counts)).toEqual([
      'BC Parks',
      'National Park Service',
      'US Forest Service',
    ]);
  });
});

describe('hiddenAgencyFilter', () => {
  test('nothing hidden means no filter', () => {
    expect(hiddenAgencyFilter([])).toBeNull();
  });

  test('excludes the named agencies', () => {
    expect(hiddenAgencyFilter(['BC Parks', 'US Forest Service'])).toEqual([
      'all',
      ['!', ['in', ['get', 'agency'], ['literal', ['BC Parks', 'US Forest Service']]]],
    ]);
  });

  test('hiding the sentinel requires the property to be present', () => {
    expect(hiddenAgencyFilter([UNCATEGORIZED_AGENCY])).toEqual(['all', ['has', 'agency']]);
  });

  test('combines both clauses', () => {
    expect(hiddenAgencyFilter(['BC Parks', UNCATEGORIZED_AGENCY])).toEqual([
      'all',
      ['!', ['in', ['get', 'agency'], ['literal', ['BC Parks']]]],
      ['has', 'agency'],
    ]);
  });
});
