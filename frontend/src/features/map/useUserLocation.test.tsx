import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createTestQueryClient } from '@/test/query-client';
import { act, render, screen, waitFor } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { useMapStore } from '@/stores/mapStore';
import { FakeGeolocateControl, FakeMap } from '@/test/fake-map';

let instance: FakeMap;
class TestMap extends FakeMap {
  constructor(readonly options: unknown) {
    super();
    instance = this;
  }
}

/** The control the hook installed, so a test can fire its events. */
let geolocate: FakeGeolocateControl;
class TestGeolocateControl extends FakeGeolocateControl {
  constructor(options: unknown) {
    super(options);
    geolocate = this;
  }
}

/**
 * The puck's marker, recorded rather than rendered.
 *
 * The real `Marker` attaches itself to a live map's container and reads its
 * transform; what matters here is the element it was given and where it was put.
 */
interface MarkerRecord {
  element: HTMLElement;
  lngLat: [number, number] | null;
  added: number;
  removed: number;
}
const markers: MarkerRecord[] = [];
class TestMarker {
  record: MarkerRecord;
  constructor(options: { element: HTMLElement }) {
    this.record = { element: options.element, lngLat: null, added: 0, removed: 0 };
    markers.push(this.record);
  }
  setLngLat(lngLat: [number, number]) {
    this.record.lngLat = lngLat;
    return this;
  }
  addTo() {
    this.record.added += 1;
    // The real marker appends its element to the map's container, and the puck's
    // accessible name is part of its contract.
    document.body.appendChild(this.record.element);
    return this;
  }
  remove() {
    this.record.removed += 1;
    this.record.element.remove();
    return this;
  }
}

vi.mock('maplibre-gl', () => ({
  Map: TestMap,
  Marker: TestMarker,
  setWorkerUrl: vi.fn(),
  GeolocateControl: TestGeolocateControl,
}));
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));

// Imported after the mock, because both of these reach `maplibre-gl`: a static
// import would evaluate the module before the hoisted factory's classes exist.
const { USER_LOCATION_CLASS } = await import('@/map/user-location');
const { MapProvider } = await import('./MapProvider');
const { useUserLocation } = await import('./useUserLocation');

/** A button standing in for `MapControlButtons`' real one, so a test can fire
    the hook's returned `locate` the same way a click would. */
function Harness() {
  const { locate } = useUserLocation();
  return (
    <button type="button" onClick={locate}>
      locate
    </button>
  );
}

const mount = () =>
  render(
    <AppProviders client={createTestQueryClient()}>
      <MapProvider>
        <Harness />
      </MapProvider>
    </AppProviders>,
  );

const position = (longitude: number, latitude: number, accuracy = 30) =>
  ({ coords: { longitude, latitude, accuracy } }) as GeolocationPosition;

const puck = () => document.querySelector(`.${USER_LOCATION_CLASS}`);

beforeEach(() => {
  markers.length = 0;
  useMapStore.getState().reset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('the controls', () => {
  test('the geolocate control is added, bottom-right', () => {
    mount();

    // Zoom is no longer a MapLibre control at all — MapControlButtons calls
    // map.zoomIn()/zoomOut() directly. Only geolocate is still MapLibre's,
    // added (but hidden — see map.css) so trigger() has something to call.
    expect(instance.controls).toHaveLength(1);
    expect(instance.controls[0]!.position).toBe('bottom-right');
  });

  test('locate() triggers the hidden geolocate control', () => {
    mount();

    act(() => screen.getByRole('button', { name: 'locate' }).click());

    expect(geolocate.triggerCalls).toBe(1);
  });

  test('locate-me asks for one low-accuracy fix', () => {
    mount();

    const options = geolocate.options as {
      trackUserLocation: boolean;
      showUserLocation: boolean;
      positionOptions: PositionOptions;
    };

    expect(options.trackUserLocation).toBe(false);
    expect(options.positionOptions).toEqual({ enableHighAccuracy: false, timeout: 8000 });
    // We draw the puck, so MapLibre's own dot would be a second one flickering under
    // ours for the length of the fetch.
    expect(options.showUserLocation).toBe(false);
  });

  test('comes off with the page', () => {
    const view = mount();

    view.unmount();

    expect(instance.controls).toHaveLength(0);
    expect(geolocate.listenerCount('geolocate')).toBe(0);
    expect(geolocate.listenerCount('error')).toBe(0);
  });

  test('a style reload leaves it alone', async () => {
    mount();

    await act(async () => {
      instance.fire('style.load');
    });

    expect(instance.controls).toHaveLength(1);
  });
});

describe('a fix', () => {
  test('goes into the store, with its accuracy', () => {
    mount();

    act(() => geolocate.fire('geolocate', position(-122.4, 37.8)));

    expect(useMapStore.getState().userLocation).toEqual({
      lng: -122.4,
      lat: 37.8,
      accuracy: 30,
    });
  });

  test('puts the puck on the map', () => {
    mount();

    act(() => geolocate.fire('geolocate', position(-122.4, 37.8)));

    expect(markers).toHaveLength(1);
    expect(markers[0]!.lngLat).toEqual([-122.4, 37.8]);
    expect(puck()).not.toBeNull();
    expect(screen.getByRole('img', { name: 'Your location' })).toBeInTheDocument();
  });

  test('a second fix moves the same puck', () => {
    mount();

    act(() => geolocate.fire('geolocate', position(-122.4, 37.8)));
    act(() => geolocate.fire('geolocate', position(-121.9, 37.3)));

    expect(markers).toHaveLength(1);
    expect(markers[0]!.lngLat).toEqual([-121.9, 37.3]);
  });

  test('a location from anywhere else gets a puck too', async () => {
    mount();

    act(() => useMapStore.getState().setUserLocation({ lng: -120, lat: 39 }));

    await waitFor(() => expect(puck()).not.toBeNull());
    expect(markers[0]!.lngLat).toEqual([-120, 39]);
  });

  test('the puck goes with the page', () => {
    const view = mount();
    act(() => geolocate.fire('geolocate', position(-122.4, 37.8)));

    view.unmount();

    expect(markers[0]!.removed).toBe(1);
    expect(puck()).toBeNull();
  });
});

describe('a failure', () => {
  test('clears the location and takes the puck away', async () => {
    mount();
    act(() => geolocate.fire('geolocate', position(-122.4, 37.8)));

    act(() => geolocate.fire('error', { code: 2 }));

    expect(useMapStore.getState().userLocation).toBeNull();
    await waitFor(() => expect(puck()).toBeNull());
  });

  test('a denied permission says how to grant it', async () => {
    mount();

    act(() => geolocate.fire('error', { code: 1 }));

    expect(await screen.findByText("We can't use your location")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Your browser blocked the request, so we can't centre the map on you. Searching for a place works just as well.",
      ),
    ).toBeInTheDocument();
  });

  test('any other failure just says so', async () => {
    mount();

    act(() => geolocate.fire('error', { code: 3 }));

    expect(await screen.findByText("Couldn't get your location")).toBeInTheDocument();
  });
});
