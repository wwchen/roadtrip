import { describe, expect, test, vi } from 'vitest';
import type { LineString, Polygon } from 'geojson';
import { CORRIDOR_DEFAULT_MILES } from '@/stores/tripStore';
import { clampCorridorMiles, computeCorridor, routeLine, serverCorridor } from './trip-corridor';

/** Seattle → Boston, roughly, at one degree of longitude per vertex. */
const longLine: LineString = {
  type: 'LineString',
  coordinates: Array.from({ length: 50 }, (_, i) => [-122 + i, 47 + (i % 3)]),
};

const shortLine: LineString = {
  type: 'LineString',
  coordinates: [
    [-122.33, 47.6],
    [-122.65, 48.41],
  ],
};

const routeResponse = (extra: unknown[] = []) => ({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', geometry: shortLine, properties: { distance_m: 1000 } }, ...extra],
});

const ringLength = (geometry: Polygon | { coordinates: unknown }): number =>
  JSON.stringify(geometry.coordinates).split('],').length;

describe('clampCorridorMiles', () => {
  test('holds the slider range', () => {
    expect(clampCorridorMiles(1)).toBe(5);
    expect(clampCorridorMiles(500)).toBe(100);
  });

  test('snaps to the slider step', () => {
    expect(clampCorridorMiles(37)).toBe(35);
    expect(clampCorridorMiles(38)).toBe(40);
  });

  test('defaults a value that is not a number', () => {
    expect(clampCorridorMiles(Number.NaN)).toBe(CORRIDOR_DEFAULT_MILES);
  });
});

describe('computeCorridor', () => {
  test('buffers a line into a polygon', () => {
    const corridor = computeCorridor(shortLine, 5);

    expect(corridor?.type === 'Polygon' || corridor?.type === 'MultiPolygon').toBe(true);
  });

  test('a wider radius makes a bigger polygon', () => {
    const narrow = computeCorridor(shortLine, 5)!;
    const wide = computeCorridor(shortLine, 100)!;

    const spread = (geometry: typeof narrow): number => {
      const lngs = JSON.stringify(geometry.coordinates)
        .match(/-?\d+\.?\d*/g)!
        .map(Number);
      return Math.max(...lngs) - Math.min(...lngs);
    };
    expect(spread(wide)).toBeGreaterThan(spread(narrow));
  });

  test('keeps a cross-country corridor well under the backend"s vertex cap', () => {
    const corridor = computeCorridor(longLine, 100)!;

    expect(ringLength(corridor)).toBeLessThan(2000);
  });

  test('answers null for a line with no coordinates', () => {
    expect(computeCorridor({ type: 'LineString', coordinates: [] }, 5)).toBeNull();
    expect(computeCorridor(null, 5)).toBeNull();
    expect(computeCorridor(undefined, 5)).toBeNull();
  });

  test('survives a turf failure', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    // A line whose coordinates are not numbers gets past the length check and
    // fails inside turf.
    expect(
      computeCorridor({ type: 'LineString', coordinates: ['x', 'y'] as never }, 5),
    ).toBeNull();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});

describe('serverCorridor', () => {
  test('finds the corridor feature by role', () => {
    const polygon: Polygon = {
      type: 'Polygon',
      coordinates: [
        [
          [-122, 47],
          [-121, 47],
          [-121, 48],
          [-122, 47],
        ],
      ],
    };

    expect(
      serverCorridor(routeResponse([{ properties: { role: 'corridor' }, geometry: polygon }])),
    ).toEqual(polygon);
  });

  test('answers null when the response carries none', () => {
    expect(serverCorridor(routeResponse())).toBeNull();
    expect(serverCorridor(null)).toBeNull();
  });

  test('ignores a corridor role on a non-polygon geometry', () => {
    expect(
      serverCorridor(routeResponse([{ properties: { role: 'corridor' }, geometry: shortLine }])),
    ).toBeNull();
  });
});

describe('routeLine', () => {
  test('takes the first feature"s line', () => {
    expect(routeLine(routeResponse())).toEqual(shortLine);
  });

  test('answers null for a response with no line', () => {
    expect(routeLine({ features: [] })).toBeNull();
    expect(routeLine(null)).toBeNull();
  });
});
