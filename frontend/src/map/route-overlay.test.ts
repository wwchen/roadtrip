// The route line and corridor fill, against the recorder fake.
//
// The assertions are about what the code asked the map to do — which layer, in
// which order, above which anchor — because that is the part we own. Paint order
// is the substance here: it is what decides whether the route is visible.
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

  // The corridor is a tint under the labels; the line is on top of everything.
  // Both orderings are the point of the overlay, not decoration.
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

  // A second route shares nothing with the first, so install replaces rather than
  // diffing — and must not leave a duplicate source behind.
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

  // MapLibre throws on a missing layer id, and between a basemap change and the
  // reinstall there are no app layers at all — so this window is real.
  test('is safe on a map with nothing installed', () => {
    const { map } = withMap();

    expect(() => removeRouteOverlay(map)).not.toThrow();
  });
});

describe('the corridor slider path', () => {
  // setData per tick, not a reinstall: the user is watching the fill breathe.
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

  // fitBounds on a degenerate box zooms to maximum on null island.
  test('answers null for a line with nothing to fit', () => {
    expect(routeBounds({ type: 'LineString', coordinates: [] })).toBeNull();
    expect(routeBounds(null)).toBeNull();
  });

  // The whole pair is skipped, not just the bad number: pairing one point's
  // latitude with another's longitude would invent a position that is not on the
  // route.
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

  // Every coordinate unusable is not a box.
  test('answers null when no coordinate survives', () => {
    expect(
      routeBounds({ type: 'LineString', coordinates: [[Number.NaN, Number.NaN]] }),
    ).toBeNull();
  });
});
