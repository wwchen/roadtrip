import { describe, expect, test } from 'vitest';
import { distanceKm, formatDistance, geomCenter, hasCoordinates } from './geo';

describe('distanceKm', () => {
  test('one degree of latitude is ~111.195 km', () => {
    expect(distanceKm(0, 0, 1, 0)).toBeCloseTo(111.19492664455873, 6);
  });

  test('is zero for the same point', () => {
    expect(distanceKm(37.7749, -122.4194, 37.7749, -122.4194)).toBe(0);
  });

  test('is symmetric', () => {
    const a = distanceKm(37.7749, -122.4194, 34.0522, -118.2437);
    const b = distanceKm(34.0522, -118.2437, 37.7749, -122.4194);
    expect(a).toBeCloseTo(b, 10);
  });

  test('matches the known SF–LA great-circle distance', () => {
    expect(distanceKm(37.7749, -122.4194, 34.0522, -118.2437)).toBeCloseTo(559, 0);
  });

  test('shrinks a degree of longitude with latitude', () => {
    expect(distanceKm(60, 0, 60, 1)).toBeLessThan(distanceKm(0, 0, 0, 1));
  });
});

describe('formatDistance', () => {
  test.each([
    [0, '0 m away'],
    [0.5, '500 m away'],
    [0.9999, '1000 m away'],
    [1, '1.0 km away'],
    [2.5, '2.5 km away'],
    [9.99, '10.0 km away'],
    [10, '10 km away'],
    [15.4, '15 km away'],
    [1234, '1234 km away'],
  ])('%s km reads as %s', (km, expected) => {
    expect(formatDistance(km)).toBe(expected);
  });
});

describe('geomCenter', () => {
  test('a Point centres on itself', () => {
    expect(geomCenter({ type: 'Point', coordinates: [10, 20] })).toEqual([
      10,
      20,
      [
        [10, 20],
        [10, 20],
      ],
    ]);
  });

  test('a Polygon centres on its bbox midpoint', () => {
    expect(
      geomCenter({
        type: 'Polygon',
        coordinates: [
          [
            [0, 0],
            [2, 0],
            [2, 4],
            [0, 4],
            [0, 0],
          ],
        ],
      }),
    ).toEqual([
      1,
      2,
      [
        [0, 0],
        [2, 4],
      ],
    ]);
  });

  test('a MultiPolygon descends through both levels of nesting', () => {
    expect(
      geomCenter({
        type: 'MultiPolygon',
        coordinates: [
          [
            [
              [0, 0],
              [1, 1],
            ],
          ],
          [
            [
              [3, 3],
              [5, 5],
            ],
          ],
        ],
      })[0],
    ).toBe(2.5);
  });

  test('a GeometryCollection spans all member geometries', () => {
    expect(
      geomCenter({
        type: 'GeometryCollection',
        geometries: [
          { type: 'Point', coordinates: [0, 0] },
          { type: 'Point', coordinates: [4, 8] },
        ],
      }),
    ).toEqual([
      2,
      4,
      [
        [0, 0],
        [4, 8],
      ],
    ]);
  });

  test.each([
    ['null', null],
    ['undefined', undefined],
    ['a geometry with no coordinates', { type: 'Point' }],
    ['empty coordinates', { type: 'Polygon', coordinates: [] }],
    ['an empty GeometryCollection', { type: 'GeometryCollection', geometries: [] }],
  ])('falls back to the origin for %s rather than NaN', (_label, geom) => {
    expect(geomCenter(geom)).toEqual([
      0,
      0,
      [
        [0, 0],
        [0, 0],
      ],
    ]);
  });
});

// The guard that tells the origin fallback apart from a real point at [0, 0] —
// which matters because the fallback passes every finite check a camera-moving
// caller would otherwise make.
describe('hasCoordinates', () => {
  test.each([
    ['a Point', { type: 'Point', coordinates: [10, 20] }],
    ['a point at the origin', { type: 'Point', coordinates: [0, 0] }],
    ['a Polygon', { type: 'Polygon', coordinates: [[[0, 1], [2, 3], [4, 5]]] }],
    [
      'a GeometryCollection with one non-empty part',
      {
        type: 'GeometryCollection',
        geometries: [{ type: 'Polygon', coordinates: [] }, { type: 'Point', coordinates: [1, 2] }],
      },
    ],
  ])('is true for %s', (_label, geom) => {
    expect(hasCoordinates(geom)).toBe(true);
  });

  test.each([
    ['null', null],
    ['undefined', undefined],
    ['a geometry with no coordinates', { type: 'Point' }],
    ['empty coordinates', { type: 'Polygon', coordinates: [] }],
    ['nested empties', { type: 'MultiPolygon', coordinates: [[[]]] }],
    ['an empty GeometryCollection', { type: 'GeometryCollection', geometries: [] }],
    ['a GeometryCollection of empties', {
      type: 'GeometryCollection',
      geometries: [{ type: 'Point', coordinates: [] }],
    }],
    // A provider that ships nulls where numbers belong reads as "no geometry",
    // not as a coordinate — geomCenter would answer [0, 0] for it.
    ['null coordinates', { type: 'Point', coordinates: [null, null] }],
  ])('is false for %s', (_label, geom) => {
    expect(hasCoordinates(geom as Parameters<typeof hasCoordinates>[0])).toBe(false);
  });

  test('is false for everything geomCenter answers with the origin for', () => {
    for (const geom of [null, undefined, { type: 'Point' }, { type: 'Polygon', coordinates: [] }]) {
      expect(hasCoordinates(geom)).toBe(false);
      expect(geomCenter(geom)[0]).toBe(0);
    }
  });
});
