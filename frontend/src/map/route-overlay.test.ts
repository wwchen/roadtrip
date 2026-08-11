import { describe, expect, test } from 'vitest';
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { LineString, Polygon } from 'geojson';
import { token } from '@tokens';
import { FakeMap } from '@/test/fake-map';
import {
  CORRIDOR_LAYER_ID,
  CORRIDOR_SOURCE_ID,
  ROUTE_LAYER_ID,
  ROUTE_SOURCE_ID,
  firstSymbolLayerId,
  installRouteOverlay,
  removeRouteOverlay,
  routeBounds,
  setCorridorData,
} from './route-overlay';

const line: LineString = {
  type: 'LineString',
  coordinates: [
    [-122.33, 47.6],
    [-122.65, 48.41],
    [-121.9, 48.0],
  ],
};

const corridor: Polygon = {
  type: 'Polygon',
  coordinates: [
    [
      [-123, 47],
      [-121, 47],
      [-121, 49],
      [-123, 47],
    ],
  ],
};

const withMap = () => {
  const fake = new FakeMap();
  return { fake, map: fake as unknown as MapLibreMap };
};

describe('installing the route', () => {
  test('adds a line layer in the route colour', () => {
    const { fake, map } = withMap();

    installRouteOverlay(map, line, null);

    expect(fake.sources.get(ROUTE_SOURCE_ID)?.data).toEqual({
      type: 'Feature',
      geometry: line,
      properties: {},
    });
    expect(fake.layer(ROUTE_LAYER_ID)?.paint?.['line-color']).toBe(token('--rt-map-route'));
    expect(fake.layer(ROUTE_LAYER_ID)?.layout['line-join']).toBe('round');
  });

  test('puts the corridor fill under the basemap"s first symbol layer', () => {
    const { fake, map } = withMap();

    installRouteOverlay(map, line, corridor);

    expect(fake.layer(CORRIDOR_LAYER_ID)?.before).toBe('place-labels');
    expect(fake.layer(ROUTE_LAYER_ID)?.before).toBeUndefined();
  });

  test('draws the line above the corridor', () => {
    const { fake, map } = withMap();

    installRouteOverlay(map, line, corridor);

    const ids = fake.layers.map((l) => l.id);
    expect(ids.indexOf(ROUTE_LAYER_ID)).toBeGreaterThan(ids.indexOf(CORRIDOR_LAYER_ID));
  });

  test('skips the fill when there is no corridor', () => {
    const { fake, map } = withMap();

    installRouteOverlay(map, line, null);

    expect(fake.layer(CORRIDOR_LAYER_ID)).toBeUndefined();
    expect(fake.sources.has(CORRIDOR_SOURCE_ID)).toBe(false);
  });

  test('replaces a previous route', () => {
    const { fake, map } = withMap();

    installRouteOverlay(map, line, corridor);
    installRouteOverlay(map, line, corridor);

    expect(fake.layers.filter((l) => l.id === ROUTE_LAYER_ID)).toHaveLength(1);
    expect(fake.layers.filter((l) => l.id === CORRIDOR_LAYER_ID)).toHaveLength(1);
  });

  test('has no anchor to use when the style has no symbol layer', () => {
    const { fake, map } = withMap();
    fake.styleLayers = [{ id: 'background', type: 'background' }];

    expect(firstSymbolLayerId(map)).toBeUndefined();
    installRouteOverlay(map, line, corridor);
    expect(fake.layer(CORRIDOR_LAYER_ID)?.before).toBeUndefined();
  });
});

describe('removing the route', () => {
  test('takes both layers and both sources', () => {
    const { fake, map } = withMap();
    installRouteOverlay(map, line, corridor);

    removeRouteOverlay(map);

    expect(fake.layers).toHaveLength(0);
    expect(fake.sources.size).toBe(0);
  });

  test('is safe on a map with nothing installed', () => {
    const { map } = withMap();

    expect(() => removeRouteOverlay(map)).not.toThrow();
  });
});

describe('the corridor slider path', () => {
  test('repaints in place', () => {
    const { fake, map } = withMap();
    installRouteOverlay(map, line, corridor);

    const wider: Polygon = {
      type: 'Polygon',
      coordinates: [
        [
          [-124, 46],
          [-120, 46],
          [-120, 50],
          [-124, 46],
        ],
      ],
    };
    expect(setCorridorData(map, wider)).toBe(true);
    expect(fake.sources.get(CORRIDOR_SOURCE_ID)?.setDataCalls).toBe(1);
    expect(fake.layers.filter((l) => l.id === CORRIDOR_LAYER_ID)).toHaveLength(1);
  });

  test('reports the miss when the source is not installed', () => {
    const { map } = withMap();

    expect(setCorridorData(map, corridor)).toBe(false);
  });

  test('reports the miss for a corridor that could not be computed', () => {
    const { map } = withMap();
    installRouteOverlay(map, line, corridor);

    expect(setCorridorData(map, null)).toBe(false);
  });
});

describe('routeBounds', () => {
  test('spans every coordinate', () => {
    expect(routeBounds(line)).toEqual([
      [-122.65, 47.6],
      [-121.9, 48.41],
    ]);
  });

  test('answers null for a line with nothing to fit', () => {
    expect(routeBounds({ type: 'LineString', coordinates: [] })).toBeNull();
    expect(routeBounds(null)).toBeNull();
  });

  test('skips a coordinate pair that is not fully numeric', () => {
    expect(
      routeBounds({
        type: 'LineString',
        coordinates: [[Number.NaN, 47], [-122, 48]],
      }),
    ).toEqual([
      [-122, 48],
      [-122, 48],
    ]);
  });

  test('answers null when no coordinate survives', () => {
    expect(
      routeBounds({ type: 'LineString', coordinates: [[Number.NaN, Number.NaN]] }),
    ).toBeNull();
  });
});
