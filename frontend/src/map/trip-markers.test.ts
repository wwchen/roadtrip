import { beforeEach, describe, expect, test, vi } from 'vitest';
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { TripStop } from '@/stores/tripStore';

interface FakeMarkerRecord {
  element: HTMLElement;
  lngLat: [number, number] | null;
  added: boolean;
  removed: boolean;
}

const records: FakeMarkerRecord[] = [];

vi.mock('maplibre-gl', () => {
  class FakeMarker {
    private record: FakeMarkerRecord;

    constructor(options: { element: HTMLElement }) {
      this.record = { element: options.element, lngLat: null, added: false, removed: false };
      records.push(this.record);
    }

    setLngLat(lngLat: [number, number]) {
      this.record.lngLat = lngLat;
      return this;
    }

    addTo() {
      this.record.added = true;
      return this;
    }

    remove() {
      this.record.removed = true;
      return this;
    }
  }
  return { Marker: FakeMarker };
});

const { createTripMarkerRegistry, removeTripMarkers, syncTripMarkers } = await import(
  './trip-markers'
);

const map = {} as MapLibreMap;
const stop = (name: string, lng: number, lat: number): TripStop => ({ name, lng, lat });
const live = () => records.filter((r) => r.added && !r.removed);

beforeEach(() => {
  records.length = 0;
});

describe('syncTripMarkers', () => {
  test('places one marker per located stop', () => {
    const registry = createTripMarkerRegistry();

    syncTripMarkers(map, registry, [stop('A', -122, 47), stop('B', -121, 48)]);

    expect(live()).toHaveLength(2);
    expect(live().map((r) => r.lngLat)).toEqual([
      [-122, 47],
      [-121, 48],
    ]);
  });

  test('labels the ends and numbers the vias', () => {
    const registry = createTripMarkerRegistry();

    syncTripMarkers(map, registry, [
      stop('A', -122, 47),
      stop('V', -121.5, 47.5),
      stop('B', -121, 48),
    ]);

    expect(live().map((r) => r.element.textContent)).toEqual(['A', '1', 'C']);
    expect(live().map((r) => r.element.dataset.role)).toEqual(['origin', 'via', 'destination']);
  });

  test('an empty slot gets no marker, and does not shift the others', () => {
    const registry = createTripMarkerRegistry();

    syncTripMarkers(map, registry, [stop('A', -122, 47), null, stop('B', -121, 48)]);

    expect(live()).toHaveLength(2);
    // Three slots, so the destination is still the third.
    expect(live().map((r) => r.element.textContent)).toEqual(['A', 'C']);
    expect(registry.markers).toHaveLength(3);
    expect(registry.markers[1]).toBeNull();
  });

  test('a stop still locating gets no marker', () => {
    const registry = createTripMarkerRegistry();

    syncTripMarkers(map, registry, [
      { name: 'Locating you…', lng: 0, lat: 0, pending: true },
      stop('B', -121, 48),
    ]);

    expect(live()).toHaveLength(1);
    expect(live()[0]!.element.textContent).toBe('B');
  });

  test('a shorter trip drops the markers it no longer has stops for', () => {
    const registry = createTripMarkerRegistry();
    syncTripMarkers(map, registry, [
      stop('A', -122, 47),
      stop('V', -121.5, 47.5),
      stop('B', -121, 48),
    ]);

    syncTripMarkers(map, registry, [stop('A', -122, 47), stop('B', -121, 48)]);

    expect(live()).toHaveLength(2);
    expect(registry.markers).toHaveLength(2);
  });

  test('a reorder relabels rather than moving a marker', () => {
    const registry = createTripMarkerRegistry();
    const a = stop('A', -122, 47);
    const b = stop('B', -121, 48);
    syncTripMarkers(map, registry, [a, b]);

    syncTripMarkers(map, registry, [b, a]);

    expect(live().map((r) => [r.element.textContent, r.lngLat])).toEqual([
      ['A', [-121, 48]],
      ['B', [-122, 47]],
    ]);
  });
});

describe('removeTripMarkers', () => {
  test('removes every marker and empties the registry', () => {
    const registry = createTripMarkerRegistry();
    syncTripMarkers(map, registry, [stop('A', -122, 47), stop('B', -121, 48)]);

    removeTripMarkers(registry);

    expect(live()).toHaveLength(0);
    expect(registry.markers).toEqual([]);
  });

  test('is safe on an empty registry', () => {
    expect(() => removeTripMarkers(createTripMarkerRegistry())).not.toThrow();
  });
});
