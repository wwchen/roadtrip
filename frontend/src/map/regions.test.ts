import { describe, expect, test } from 'vitest';
import type { FeatureCollection } from 'geojson';
import { ADMIN_REGION_STYLE, boundaryFromCollection, regionNameOf } from './regions';

const square = (x: number) => [
  [
    [x, 37],
    [x + 1, 37],
    [x + 1, 42],
    [x, 42],
    [x, 37],
  ],
];

const states: FeatureCollection = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      properties: { name: 'Nevada' },
      geometry: { type: 'Polygon', coordinates: square(-119) },
    },
    {
      type: 'Feature',
      properties: { name: 'Utah' },
      geometry: { type: 'Polygon', coordinates: square(-114) },
    },
    {
      type: 'Feature',
      properties: { name: 'A capital city' },
      geometry: { type: 'Point', coordinates: [-111, 40] },
    },
  ],
};

describe('regionNameOf', () => {
  test('takes the region"s own name out of a qualified place name', () => {
    expect(regionNameOf('Utah, United States')).toBe('Utah');
  });

  test('leaves an unqualified name alone', () => {
    expect(regionNameOf('Utah')).toBe('Utah');
  });
});

describe('boundaryFromCollection', () => {
  test('finds the region"s polygon by name', () => {
    const boundary = boundaryFromCollection(states, 'Utah, United States', ADMIN_REGION_STYLE);

    expect(boundary?.name).toBe('Utah');
    expect(boundary?.geometry.coordinates).toEqual(square(-114));
    expect(boundary?.style).toBe('ADMIN');
  });

  test('matches regardless of case and surrounding space', () => {
    expect(boundaryFromCollection(states, '  nevada , United States', ADMIN_REGION_STYLE)?.name).toBe(
      'Nevada',
    );
  });

  test('returns null for a region the collection does not carry', () => {
    // The common case, and the honest one: park boundaries are not ingested at
    // all, so most regions resolve to no geometry and simply do not draw.
    expect(boundaryFromCollection(states, 'Zion National Park', ADMIN_REGION_STYLE)).toBeNull();
  });

  test('ignores a feature that is not an area', () => {
    // A point named like a region would otherwise be handed to a fill layer.
    expect(boundaryFromCollection(states, 'A capital city', ADMIN_REGION_STYLE)).toBeNull();
  });

  test('returns null when the collection has not loaded', () => {
    expect(boundaryFromCollection(undefined, 'Utah', ADMIN_REGION_STYLE)).toBeNull();
  });

  test('returns null for an empty name', () => {
    expect(boundaryFromCollection(states, '  ,  ', ADMIN_REGION_STYLE)).toBeNull();
  });
});
