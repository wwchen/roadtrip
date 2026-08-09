import { beforeEach, describe, expect, test } from 'vitest';
import { token } from '@tokens';
import { FakeMap, asMapLibre } from '@/test/fake-map';
import { pinCollection, type PinFeature } from './pins';
import {
  POINT_OVERLAYS,
  bucketPins,
  hitLayerIdOf,
  installPointOverlay,
  installedHitLayerIds,
  overlaySpec,
  pinLayerIdOf,
  setOverlayData,
  setOverlayFilter,
  setOverlayVisible,
  sourceIdOf,
} from './overlays';

const pin = (category: string, id = 1, agency?: string): PinFeature => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [-121, 40] },
  properties: agency === undefined ? { category } : { category, agency },
});

let fake: FakeMap;
beforeEach(() => {
  fake = new FakeMap();
});

describe('the registry', () => {
  test('derives ids the way the vanilla layers were named', () => {
    const cg = overlaySpec('cg');

    expect(sourceIdOf(cg)).toBe('cg');
    expect(pinLayerIdOf(cg)).toBe('cg-points');
    expect(hitLayerIdOf(cg)).toBe('cg-points-hit');
  });

  test('every overlay claims a category, and no two claim the same one', () => {
    const seen = new Set<string>();
    for (const spec of POINT_OVERLAYS) {
      expect(spec.categories.length).toBeGreaterThan(0);
      for (const category of spec.categories) {
        expect(seen.has(category)).toBe(false);
        seen.add(category);
      }
    }
  });

  test('throws on an unknown key rather than returning undefined', () => {
    // @ts-expect-error -- the point is the runtime guard for a key off the wire.
    expect(() => overlaySpec('parks')).toThrow(/unknown overlay/);
  });
});

describe('bucketPins', () => {
  test('routes each category to its overlay', () => {
    const buckets = bucketPins([
      pin('campground', 1),
      pin('tesla_supercharger', 2),
      pin('planet_fitness_location', 3),
    ]);

    expect(buckets.cg.features.map((f) => f.id)).toEqual([1]);
    expect(buckets.sc.features.map((f) => f.id)).toEqual([2]);
    expect(buckets.pf.features.map((f) => f.id)).toEqual([3]);
  });

  // Both the canonical category name and the alias reach the client depending on
  // which endpoint answered, and the vanilla bucketing accepted both.
  test('accepts the alias category names', () => {
    const buckets = bucketPins([pin('supercharger', 1), pin('planet-fitness', 2)]);

    expect(buckets.sc.features.map((f) => f.id)).toEqual([1]);
    expect(buckets.pf.features.map((f) => f.id)).toEqual([2]);
  });

  test('drops categories no overlay paints', () => {
    const buckets = bucketPins([pin('national-park', 1), pin('campground', 2)]);

    expect(buckets.cg.features.map((f) => f.id)).toEqual([2]);
    for (const spec of POINT_OVERLAYS) {
      expect(buckets[spec.key].features.some((f) => f.id === 1)).toBe(false);
    }
  });

  test('a pin with no properties is dropped, not thrown on', () => {
    const orphan = { type: 'Feature', geometry: null, properties: null } as unknown as PinFeature;

    expect(() => bucketPins([orphan])).not.toThrow();
    expect(bucketPins([orphan]).cg.features).toEqual([]);
  });

  test('always returns a collection per overlay, even an empty one', () => {
    const buckets = bucketPins([]);

    for (const spec of POINT_OVERLAYS) {
      expect(buckets[spec.key]).toEqual({ type: 'FeatureCollection', features: [] });
    }
  });
});

describe('installPointOverlay', () => {
  test('adds a source, a pin layer and a hit layer', () => {
    const cg = overlaySpec('cg');

    installPointOverlay(asMapLibre(fake), cg, pinCollection([pin('campground')]));

    expect(fake.sources.get('cg')?.data).toEqual(pinCollection([pin('campground')]));
    expect(fake.layer('cg-points')?.type).toBe('circle');
    expect(fake.layer('cg-points-hit')?.type).toBe('circle');
  });

  // The hit layer is transparent and generous so a pin is tappable on a phone;
  // MapLibre dispatches the click to the topmost layer, which is why handlers
  // bind there and the visual layer never sees one.
  test('the hit layer is invisible and bigger than the pin', () => {
    installPointOverlay(asMapLibre(fake), overlaySpec('pf'));

    expect(fake.layer('pf-points-hit')?.paint).toEqual({
      'circle-radius': 18,
      'circle-opacity': 0,
    });
  });

  // MapLibre paint cannot resolve var(), so colors come through the token bridge
  // — asserted against the bridge rather than a literal, which would also trip
  // the color-token checker.
  test('resolves colors through the token bridge', () => {
    installPointOverlay(asMapLibre(fake), overlaySpec('sc'));

    expect(fake.layer('sc-points')?.paint).toMatchObject({
      'circle-color': token('--rt-layer-supercharger-pin'),
      'circle-stroke-color': token('--rt-map-pin-stroke'),
    });
  });

  // StrictMode runs an effect twice on mount, and a basemap change reinstalls
  // everything — so a second install must not throw "source already exists" or
  // leave a duplicate layer behind.
  test('is idempotent', () => {
    const cg = overlaySpec('cg');

    installPointOverlay(asMapLibre(fake), cg);
    installPointOverlay(asMapLibre(fake), cg);

    expect(fake.layers.filter((l) => l.id === 'cg-points')).toHaveLength(1);
    expect(fake.sources.size).toBe(1);
  });

  test('keeps campgrounds beneath the supercharger pins when both are up', () => {
    installPointOverlay(asMapLibre(fake), overlaySpec('sc'));
    installPointOverlay(asMapLibre(fake), overlaySpec('cg'));

    expect(fake.layer('cg-points')?.before).toBe('sc-points');
  });

  test('does not ask to insert below a layer that is not installed', () => {
    installPointOverlay(asMapLibre(fake), overlaySpec('cg'));

    expect(fake.layer('cg-points')?.before).toBeUndefined();
  });
});

describe('setOverlayData', () => {
  test('swaps the data without rebuilding the layers', () => {
    const cg = overlaySpec('cg');
    installPointOverlay(asMapLibre(fake), cg);
    const layersBefore = fake.layers.length;

    setOverlayData(asMapLibre(fake), cg, pinCollection([pin('campground', 7)]));

    expect(fake.sources.get('cg')?.setDataCalls).toBe(1);
    expect(fake.layers).toHaveLength(layersBefore);
  });

  // The first response can land before the style is ready; that is a no-op, and
  // the install picks the data up from the caller's cache.
  test('is a no-op before the install', () => {
    expect(() =>
      setOverlayData(asMapLibre(fake), overlaySpec('cg'), pinCollection([])),
    ).not.toThrow();
  });
});

describe('visibility and filters', () => {
  test('hiding an overlay hides both its layers', () => {
    const pf = overlaySpec('pf');
    installPointOverlay(asMapLibre(fake), pf);

    setOverlayVisible(asMapLibre(fake), pf, false);

    expect(fake.layer('pf-points')?.layout.visibility).toBe('none');
    expect(fake.layer('pf-points-hit')?.layout.visibility).toBe('none');
  });

  test('a filter applies to the hit layer too, so a hidden pin is unclickable', () => {
    const cg = overlaySpec('cg');
    installPointOverlay(asMapLibre(fake), cg);
    const filter = ['all', ['has', 'agency']] as never;

    setOverlayFilter(asMapLibre(fake), cg, filter);

    expect(fake.layer('cg-points')?.filter).toBe(filter);
    expect(fake.layer('cg-points-hit')?.filter).toBe(filter);
  });

  // Between a basemap change and the reinstall there are no layers at all, and
  // MapLibre throws on an unknown layer id.
  test('both are no-ops when the layers are gone', () => {
    const cg = overlaySpec('cg');
    installPointOverlay(asMapLibre(fake), cg);
    fake.wipeAppLayers();

    expect(() => setOverlayVisible(asMapLibre(fake), cg, false)).not.toThrow();
    expect(() => setOverlayFilter(asMapLibre(fake), cg, null)).not.toThrow();
  });
});

describe('installedHitLayerIds', () => {
  test('lists only the hit layers actually installed', () => {
    installPointOverlay(asMapLibre(fake), overlaySpec('cg'));

    expect(installedHitLayerIds(asMapLibre(fake))).toEqual(['cg-points-hit']);
  });

  test('is empty before anything is installed', () => {
    expect(installedHitLayerIds(asMapLibre(fake))).toEqual([]);
  });
});
