import { describe, expect, test } from 'vitest';
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { MultiPolygon, Polygon } from 'geojson';
import { token } from '@tokens';
import { FakeMap } from '@/test/fake-map';
import {
  REGION_FILL_LAYER_ID,
  REGION_LINE_LAYER_ID,
  REGION_SOURCE_ID,
  installRegionBoundary,
  removeRegionBoundary,
  type RegionBoundary,
} from './region-boundary';

const utah: Polygon = {
  type: 'Polygon',
  coordinates: [
    [
      [-114.05, 37],
      [-109.04, 37],
      [-109.04, 42],
      [-114.05, 42],
      [-114.05, 37],
    ],
  ],
};

const boundary: RegionBoundary = { name: 'Utah', geometry: utah, style: 'ADMIN' };

const withMap = () => {
  const fake = new FakeMap();
  return { fake, map: fake as unknown as MapLibreMap };
};

describe('installing a region boundary', () => {
  test('draws a fill and an outline from one source', () => {
    const { fake, map } = withMap();

    installRegionBoundary(map, boundary);

    expect(fake.sources.get(REGION_SOURCE_ID)?.data).toEqual({
      type: 'Feature',
      geometry: utah,
      properties: { name: 'Utah' },
    });
    expect(fake.layer(REGION_FILL_LAYER_ID)?.source).toBe(REGION_SOURCE_ID);
    expect(fake.layer(REGION_LINE_LAYER_ID)?.source).toBe(REGION_SOURCE_ID);
  });

  test('puts the fill under the basemap"s first symbol layer', () => {
    const { fake, map } = withMap();

    installRegionBoundary(map, boundary);

    // Place labels have to stay readable through the tint.
    expect(fake.layer(REGION_FILL_LAYER_ID)?.before).toBe('place-labels');
  });

  test('puts the outline under the anchor it is given', () => {
    const { fake, map } = withMap();

    installRegionBoundary(map, boundary, 'cg-points');

    expect(fake.layer(REGION_LINE_LAYER_ID)?.before).toBe('cg-points');
  });

  test('paints a park in its own palette rather than the administrative one', () => {
    const { fake, map } = withMap();

    installRegionBoundary(map, { ...boundary, style: 'NP' });

    expect(fake.layer(REGION_FILL_LAYER_ID)?.paint?.['fill-color']).toBe(token('--rt-map-np-fill'));
    expect(fake.layer(REGION_LINE_LAYER_ID)?.paint?.['line-color']).toBe(
      token('--rt-map-np-stroke'),
    );
  });

  test('replaces the previous region rather than stacking on it', () => {
    const { fake, map } = withMap();
    const nevada: MultiPolygon = { type: 'MultiPolygon', coordinates: [utah.coordinates] };

    installRegionBoundary(map, boundary);
    installRegionBoundary(map, { name: 'Nevada', geometry: nevada, style: 'ADMIN' });

    expect(fake.layers.filter((l) => l.id === REGION_FILL_LAYER_ID)).toHaveLength(1);
    expect(fake.sources.get(REGION_SOURCE_ID)?.data).toMatchObject({ geometry: nevada });
  });
});

describe('removing a region boundary', () => {
  test('takes both layers and the source', () => {
    const { fake, map } = withMap();

    installRegionBoundary(map, boundary);
    removeRegionBoundary(map);

    expect(fake.layer(REGION_FILL_LAYER_ID)).toBeUndefined();
    expect(fake.layer(REGION_LINE_LAYER_ID)).toBeUndefined();
    expect(fake.sources.has(REGION_SOURCE_ID)).toBe(false);
  });

  test('is a no-op when nothing was installed', () => {
    // The state between a basemap change and the reinstall: MapLibre throws on a
    // missing id, so the guards are the whole point of this one.
    const { map } = withMap();

    expect(() => removeRegionBoundary(map)).not.toThrow();
  });
});
