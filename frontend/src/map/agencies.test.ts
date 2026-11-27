import { describe, expect, test } from 'vitest';
import type { PinFeature } from './pins';
import {
  UNCATEGORIZED_AGENCY,
  agencyCounts,
  agenciesByCount,
  campgroundLayerFilter,
  excludedAgencyPinIds,
  featureAgency,
  idsWithVisibleAgency,
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

describe('excludedAgencyPinIds and idsWithVisibleAgency', () => {
  const pins = [
    campground('US Forest Service', 1),
    campground('US Forest Service', 2),
    campground('BC Parks', 3),
    campground(undefined, 4),
  ];
  const narrow = (ids: number[], picked: string[]) => idsWithVisibleAgency(ids, excludedAgencyPinIds(pins, picked));

  test('keeps only the ids whose agency is picked', () => {
    expect(narrow([1, 2, 3, 4], ['US Forest Service'])).toEqual([1, 2]);
  });

  test('picking the sentinel keeps the campgrounds with no agency', () => {
    expect(narrow([1, 2, 3, 4], [UNCATEGORIZED_AGENCY])).toEqual([4]);
  });

  test('an id with no pin stays — unknown is not excluded', () => {
    expect(narrow([3, 99], ['US Forest Service'])).toEqual([99]);
  });

  test('narrows before any cap, so a dominant unpicked agency cannot crowd the head', () => {
    const forest = Array.from({ length: 50 }, (_, i) => campground('US Forest Service', i + 1));
    const parks = [campground('BC Parks', 51), campground('BC Parks', 52)];
    const inView = [...forest, ...parks];
    const ids = inView.map((pin) => pin.id as number);

    expect(idsWithVisibleAgency(ids, excludedAgencyPinIds(inView, ['BC Parks']))).toEqual([51, 52]);
  });

  test('an empty pick excludes nothing: one shared empty set and the same array back', () => {
    const ids = [1, 2, 3];
    const excluded = excludedAgencyPinIds(pins, []);

    expect(excluded.size).toBe(0);
    expect(excluded).toBe(excludedAgencyPinIds([], []));
    expect(idsWithVisibleAgency(ids, excluded)).toBe(ids);
  });

  test('a pick that is not in view excludes every campground in view', () => {
    expect(narrow([1, 2, 3, 4], ['Parks Canada'])).toEqual([]);
  });

  test('ignores anything that is not a campground', () => {
    const supercharger = {
      type: 'Feature',
      id: 7,
      geometry: { type: 'Point', coordinates: [-121, 40] },
      properties: { category: 'tesla_supercharger', agency: 'US Forest Service' },
    } as PinFeature;

    expect(excludedAgencyPinIds([supercharger], ['BC Parks']).size).toBe(0);
  });
});

describe('agenciesByCount', () => {
  const counts = new Map([
    ['National Park Service', 9],
    ['USDA Forest Service', 21],
    ['California State Parks', 14],
    ['BC Parks', 9],
  ]);

  test('orders by count, most first, then by name', () => {
    expect(agenciesByCount(counts, [])).toEqual([
      { agency: 'USDA Forest Service', count: 21 },
      { agency: 'California State Parks', count: 14 },
      { agency: 'BC Parks', count: 9 },
      { agency: 'National Park Service', count: 9 },
    ]);
  });

  test('a picked agency no longer in view stays listed with zero, last', () => {
    expect(agenciesByCount(counts, ['Gone', 'BC Parks']).slice(-2)).toEqual([
      { agency: 'National Park Service', count: 9 },
      { agency: 'Gone', count: 0 },
    ]);
  });
});

describe('campgroundLayerFilter', () => {
  test('an empty pick and no id filter means no filter', () => {
    expect(campgroundLayerFilter([], null)).toBeNull();
  });

  test('an id filter with an empty pick is just the in clause', () => {
    expect(campgroundLayerFilter([], new Set([1, 2]))).toEqual(['all', ['in', ['id'], ['literal', [1, 2]]]]);
  });

  test('a pick keeps only the named agencies', () => {
    expect(campgroundLayerFilter(['BC Parks', 'US Forest Service'], null)).toEqual([
      'all',
      ['any', ['in', ['get', 'agency'], ['literal', ['BC Parks', 'US Forest Service']]]],
    ]);
  });

  test('picking the sentinel keeps the features with no agency property', () => {
    expect(campgroundLayerFilter([UNCATEGORIZED_AGENCY], null)).toEqual(['all', ['any', ['!', ['has', 'agency']]]]);
  });

  test('combines the pick and the id clause', () => {
    expect(campgroundLayerFilter(['BC Parks', UNCATEGORIZED_AGENCY], new Set([1, 2]))).toEqual([
      'all',
      ['any', ['in', ['get', 'agency'], ['literal', ['BC Parks']]], ['!', ['has', 'agency']]],
      ['in', ['id'], ['literal', [1, 2]]],
    ]);
  });
});
