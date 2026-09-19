import { describe, expect, test } from 'vitest';
import type { PinFeature } from './pins';
import {
  UNCATEGORIZED_AGENCY,
  agencyCounts,
  campgroundLayerFilter,
  featureAgency,
  hiddenAgencyFilter,
  hiddenAgencyPinIds,
  idsWithVisibleAgency,
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

describe('hiddenAgencyPinIds and idsWithVisibleAgency', () => {
  const pins = [
    campground('US Forest Service', 1),
    campground('US Forest Service', 2),
    campground('BC Parks', 3),
    campground(undefined, 4),
  ];
  const narrow = (ids: number[], hidden: string[]) =>
    idsWithVisibleAgency(ids, hiddenAgencyPinIds(pins, hidden));

  test('drops the ids whose agency is switched off', () => {
    expect(narrow([1, 2, 3, 4], ['US Forest Service'])).toEqual([3, 4]);
  });

  test('the sentinel row switches off the campgrounds with no agency', () => {
    expect(narrow([1, 2, 3, 4], [UNCATEGORIZED_AGENCY])).toEqual([1, 2, 3]);
  });

  test('an id with no pin stays — unknown is not hidden', () => {
    expect(narrow([1, 99], ['US Forest Service'])).toEqual([99]);
  });

  test('narrows before any cap, so a dominant hidden agency cannot crowd the head', () => {
    const forest = Array.from({ length: 50 }, (_, i) => campground('US Forest Service', i + 1));
    const parks = [campground('BC Parks', 51), campground('BC Parks', 52)];
    const inView = [...forest, ...parks];
    const ids = inView.map((pin) => pin.id as number);

    expect(idsWithVisibleAgency(ids, hiddenAgencyPinIds(inView, ['US Forest Service']))).toEqual([
      51, 52,
    ]);
  });

  test('nothing hidden is one empty set and the same array back', () => {
    const ids = [1, 2, 3];
    const hidden = hiddenAgencyPinIds(pins, []);

    expect(hidden.size).toBe(0);
    expect(hidden).toBe(hiddenAgencyPinIds([], []));
    expect(idsWithVisibleAgency(ids, hidden)).toBe(ids);
  });

  test('a hidden agency that is not in view leaves the ids alone', () => {
    const ids = [1, 2, 3];
    expect(idsWithVisibleAgency(ids, hiddenAgencyPinIds(pins, ['Parks Canada']))).toBe(ids);
  });

  test('ignores anything that is not a campground', () => {
    const supercharger = {
      type: 'Feature',
      id: 7,
      geometry: { type: 'Point', coordinates: [-121, 40] },
      properties: { category: 'tesla_supercharger', agency: 'US Forest Service' },
    } as PinFeature;

    expect(hiddenAgencyPinIds([supercharger], ['US Forest Service']).size).toBe(0);
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

describe('campgroundLayerFilter', () => {
  test('no hidden agencies and no id filter means no filter', () => {
    expect(campgroundLayerFilter([], null)).toBeNull();
  });

  test('an id filter with nothing hidden is just the in clause', () => {
    expect(campgroundLayerFilter([], new Set([1, 2]))).toEqual([
      'all',
      ['in', ['id'], ['literal', [1, 2]]],
    ]);
  });

  test('combines the hidden-agency clause and the id clause', () => {
    expect(campgroundLayerFilter(['BC Parks'], new Set([1, 2]))).toEqual([
      'all',
      ['!', ['in', ['get', 'agency'], ['literal', ['BC Parks']]]],
      ['in', ['id'], ['literal', [1, 2]]],
    ]);
  });
});
