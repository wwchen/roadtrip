import { describe, expect, test } from 'vitest';
import {
  MAX_SEARCH_RESULTS,
  REGION_FIT_PADDING_PX,
  boundsOf,
  cameraForResult,
  geocodeKind,
  geocodeSearchResults,
  isSearchable,
  kindForCategory,
  mergeSearchResults,
  poiSearchResults,
  sectionFor,
  sectionHeaders,
  zoomForResult,
  type SearchResult,
} from './search-results';

const poiRow = (over: Record<string, unknown> = {}) => ({
  id: 1,
  name: 'Upper Pines',
  category: 'campground',
  region: 'CA',
  lng: -119.56,
  lat: 37.73,
  ...over,
});

const geocodeRow = (over: Record<string, unknown> = {}) => ({
  id: 'g1',
  place_name: '123 Main St, Anacortes, WA',
  place_type: 'address',
  lng: -122.61,
  lat: 48.51,
  ...over,
});

describe('kindForCategory', () => {
  test('maps the categories the backend returns', () => {
    expect(kindForCategory('campground')).toBe('CG');
  });

  test('accepts both spellings of the aliased categories', () => {
    expect(kindForCategory('planet_fitness_location')).toBe('PF');
    expect(kindForCategory('planet-fitness')).toBe('PF');
    expect(kindForCategory('tesla_supercharger')).toBe('SC');
    expect(kindForCategory('supercharger')).toBe('SC');
  });

  test('falls back to a plain place', () => {
    expect(kindForCategory('something-new')).toBe('PLACE');
    expect(kindForCategory(undefined)).toBe('PLACE');
    expect(kindForCategory(42)).toBe('PLACE');
  });

  test('does not resolve a prototype member as a kind', () => {
    expect(kindForCategory('toString')).toBe('PLACE');
    expect(kindForCategory('constructor')).toBe('PLACE');
  });
});

describe('poiSearchResults', () => {
  test('carries the id and region through', () => {
    expect(poiSearchResults([poiRow()])).toEqual([
      {
        kind: 'CG',
        name: 'Upper Pines',
        sub: 'CA',
        lng: -119.56,
        lat: 37.73,
        source: 'poi',
        poiId: 1,
        category: 'campground',
      },
    ]);
  });

  test('drops a hit with no usable coordinates', () => {
    expect(poiSearchResults([poiRow({ lng: null }), poiRow({ id: 2 })])).toHaveLength(1);
    expect(poiSearchResults([poiRow({ lat: '' })])).toEqual([]);
    expect(poiSearchResults([poiRow({ lng: undefined })])).toEqual([]);
  });

  test('names an unnamed row rather than rendering undefined', () => {
    expect(poiSearchResults([poiRow({ name: undefined })])[0]!.name).toBe('Unnamed');
  });

  test('handles a missing list', () => {
    expect(poiSearchResults(undefined)).toEqual([]);
  });
});

describe('geocodeSearchResults', () => {
  test('separates an address from a place', () => {
    expect(geocodeSearchResults([geocodeRow()])[0]!.kind).toBe('ADDR');
    expect(geocodeSearchResults([geocodeRow({ place_type: 'place' })])[0]!.kind).toBe('PLACE');
  });

  test('drops an unplaceable result', () => {
    expect(geocodeSearchResults([geocodeRow({ lat: undefined })])).toEqual([]);
  });
});

describe('mergeSearchResults', () => {
  const poi = (name: string): SearchResult => ({
    kind: 'CG',
    name,
    sub: '',
    lng: -119,
    lat: 37,
    source: 'poi',
  });
  const place = (name: string): SearchResult => ({
    kind: 'PLACE',
    name,
    sub: '',
    lng: -122,
    lat: 47,
    source: 'geocode',
  });

  test('puts POIs ahead of places', () => {
    expect(mergeSearchResults([poi('a')], [place('b')]).map((r) => r.name)).toEqual(['a', 'b']);
  });

  test('caps the list', () => {
    const many = Array.from({ length: 20 }, (_, i) => poi(`poi ${i}`));

    expect(mergeSearchResults(many, [place('x')])).toHaveLength(MAX_SEARCH_RESULTS);
  });

  test('works with either source empty', () => {
    expect(mergeSearchResults([], [place('b')]).map((r) => r.name)).toEqual(['b']);
    expect(mergeSearchResults([poi('a')], []).map((r) => r.name)).toEqual(['a']);
  });
});

describe('sections', () => {
  const results: SearchResult[] = [
    { kind: 'CG', name: 'a', sub: '', lng: 0, lat: 0, source: 'poi' },
    { kind: 'CG', name: 'b', sub: '', lng: 0, lat: 0, source: 'poi' },
    { kind: 'PLACE', name: 'c', sub: '', lng: 0, lat: 0, source: 'geocode' },
  ];

  test('names each source', () => {
    expect(sectionFor(results[0]!)).toBe('POIs');
    expect(sectionFor(results[2]!)).toBe('Places');
  });

  test('emits a header only where the section changes', () => {
    expect(sectionHeaders(results)).toEqual(['POIs', null, 'Places']);
  });

  test('handles an empty list', () => {
    expect(sectionHeaders([])).toEqual([]);
  });
});

describe('isSearchable', () => {
  test('needs two non-blank characters', () => {
    expect(isSearchable('u')).toBe(false);
    expect(isSearchable(' u ')).toBe(false);
    expect(isSearchable('up')).toBe(true);
    expect(isSearchable('   ')).toBe(false);
  });
});

describe('zoomForResult', () => {
  test('zooms by what the result is', () => {
    expect(zoomForResult({ source: 'poi', kind: 'CG' } as SearchResult)).toBe(13);
    expect(zoomForResult({ source: 'geocode', kind: 'ADDR' } as SearchResult)).toBe(14);
    expect(zoomForResult({ source: 'geocode', kind: 'PLACE' } as SearchResult)).toBe(10);
  });
});

describe('geocodeKind', () => {
  test('separates addresses, regions and everything else', () => {
    expect(geocodeKind('address')).toBe('ADDR');
    expect(geocodeKind('region')).toBe('REGION');
    expect(geocodeKind('country')).toBe('REGION');
    expect(geocodeKind('district')).toBe('REGION');
    // A town is a point at a fixed zoom, not an area to frame.
    expect(geocodeKind('place')).toBe('PLACE');
    expect(geocodeKind(undefined)).toBe('PLACE');
  });
});

describe('boundsOf', () => {
  test('nests the wire"s flat quad', () => {
    expect(boundsOf([-114.05, 37, -109.04, 42])).toEqual([
      [-114.05, 37],
      [-109.04, 42],
    ]);
  });

  test('refuses anything it cannot trust whole', () => {
    expect(boundsOf(undefined)).toBeNull();
    expect(boundsOf([-114.05, 37, -109.04])).toBeNull();
    expect(boundsOf([-114.05, 37, -109.04, 42, 0])).toBeNull();
    expect(boundsOf([Number.NaN, 37, -109.04, 42])).toBeNull();
    // Inverted: east west of west, which would frame the wrong half of the world.
    expect(boundsOf([-109.04, 37, -114.05, 42])).toBeNull();
  });
});

describe('a region result', () => {
  test('carries its extent forward', () => {
    const [result] = geocodeSearchResults([
      geocodeRow({
        place_name: 'Utah, United States',
        place_type: 'region',
        bbox: [-114.05, 37, -109.04, 42],
      }),
    ] as never);

    expect(result?.kind).toBe('REGION');
    expect(result?.bounds).toEqual([
      [-114.05, 37],
      [-109.04, 42],
    ]);
  });

  test('a town keeps no bounds even though the upstream sends one', () => {
    const [result] = geocodeSearchResults([
      geocodeRow({ place_name: 'Moab, Utah', place_type: 'place', bbox: [-109.6, 38.5, -109.4, 38.7] }),
    ] as never);

    expect(result?.kind).toBe('PLACE');
    expect(result?.bounds).toBeUndefined();
  });
});

describe('cameraForResult', () => {
  test('frames a result that knows its own extent', () => {
    const camera = cameraForResult({
      source: 'geocode',
      kind: 'REGION',
      lng: -111,
      lat: 39,
      bounds: [
        [-114.05, 37],
        [-109.04, 42],
      ],
    } as SearchResult);

    expect(camera).toEqual({
      type: 'fit',
      bounds: [
        [-114.05, 37],
        [-109.04, 42],
      ],
      padding: REGION_FIT_PADDING_PX,
    });
  });

  test('flies to a point result at its own zoom', () => {
    const camera = cameraForResult({
      source: 'poi',
      kind: 'CG',
      lng: -119.56,
      lat: 37.73,
    } as SearchResult);

    expect(camera).toEqual({ type: 'fly', center: [-119.56, 37.73], zoom: 13 });
  });
});
