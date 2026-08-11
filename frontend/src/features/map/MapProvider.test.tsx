import { beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { BASEMAPS, BASEMAP_STORAGE_KEY, DEFAULT_BASEMAP } from './basemaps';

interface StyleLayer {
  id: string;
  type: string;
}

/** The last fake instance built, so tests can drive it. */
let instance: FakeMap;
const setWorkerUrl = vi.fn();

class FakeMap {
  handlers = new Map<string, Array<() => void>>();
  setStyleCalls: Array<{ style: unknown; options: unknown }> = [];
  addedSources = new Set<string>();
  addedLayers: Array<{ id: string; before?: string }> = [];
  removed = false;
  styleLayers: StyleLayer[] = [
    { id: 'background', type: 'background' },
    { id: 'roads', type: 'line' },
  ];

  constructor(public options: { style: unknown }) {
    instance = this;
  }

  on(event: string, fn: () => void) {
    const list = this.handlers.get(event) ?? [];
    list.push(fn);
    this.handlers.set(event, list);
  }

  off(event: string, fn: () => void) {
    this.handlers.set(event, (this.handlers.get(event) ?? []).filter((f) => f !== fn));
  }

  emit(event: string) {
    for (const fn of this.handlers.get(event) ?? []) fn();
  }

  setStyle(style: unknown, options: unknown) {
    this.setStyleCalls.push({ style, options });
  }

  getStyle() {
    return { layers: this.styleLayers };
  }

  getSource(id: string) {
    return this.addedSources.has(id) ? {} : undefined;
  }

  addSource(id: string) {
    this.addedSources.add(id);
  }

  getLayer(id: string) {
    return this.addedLayers.some((l) => l.id === id) ? {} : undefined;
  }

  addLayer(layer: { id: string }, before?: string) {
    this.addedLayers.push({ id: layer.id, before });
  }

  removeLayer(id: string) {
    this.addedLayers = this.addedLayers.filter((l) => l.id !== id);
  }

  remove() {
    this.removed = true;
  }
}

vi.mock('maplibre-gl', () => ({ Map: FakeMap, setWorkerUrl }));
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));

const { MapProvider, useMapContext } = await import('./MapProvider');
const { SATELLITE_LAYER_ID, SATELLITE_SOURCE_ID } = await import('./basemaps');

/** Exposes the context so tests can read and drive it. */
let ctx: ReturnType<typeof useMapContext>;

function Probe() {
  ctx = useMapContext();
  return <span data-testid="ready">{String(ctx.styleReady)}</span>;
}

const renderMap = () =>
  render(
    <MapProvider>
      <Probe />
    </MapProvider>,
  );

/** MapLibre announces a loaded style; the provider turns that into `styleReady`. */
const loadStyle = async () => {
  await act(async () => {
    instance.emit('style.load');
  });
};

beforeEach(() => {
  window.localStorage.clear();
});

describe('setup', () => {
  test('points MapLibre at the worker asset emitted by Vite', () => {
    expect(setWorkerUrl).toHaveBeenCalledOnce();
    expect(setWorkerUrl).toHaveBeenCalledWith(expect.stringContaining('maplibre-gl-worker'));
  });

  test('renders a container for the map to own', () => {
    renderMap();
    expect(screen.getByTestId('map-canvas')).toBeInTheDocument();
  });

  test('wraps the canvas in its own sized frame', () => {
    renderMap();
    expect(screen.getByTestId('map-canvas').parentElement).toHaveClass('rt-map-shell');
  });

  test('renders children inside the frame, after the canvas', () => {
    renderMap();
    const shell = screen.getByTestId('map-canvas').parentElement!;
    expect(shell).toContainElement(screen.getByTestId('ready'));
    expect(shell.firstElementChild).toBe(screen.getByTestId('map-canvas'));
  });

  test('opens on the remembered basemap', () => {
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'osm');
    renderMap();
    expect(ctx.basemapKey).toBe('osm');
    expect(instance.options.style).toBe(BASEMAPS.osm.style);
  });

  test('opens on the default when nothing is remembered', () => {
    renderMap();
    expect(ctx.basemapKey).toBe(DEFAULT_BASEMAP);
  });

  test('is not style-ready until MapLibre says so', async () => {
    renderMap();
    expect(screen.getByTestId('ready')).toHaveTextContent('false');

    await loadStyle();

    expect(screen.getByTestId('ready')).toHaveTextContent('true');
  });

  test('disposes the map on unmount', () => {
    const { unmount } = renderMap();
    unmount();
    expect(instance.removed).toBe(true);
  });
});

describe('changing basemap', () => {
  test('forces a full style reload, not an incremental diff', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setBasemap('carto-dark');
    });

    expect(instance.setStyleCalls).toHaveLength(1);
    expect(instance.setStyleCalls[0].style).toBe(BASEMAPS['carto-dark'].style);
    // diff:false is load-bearing: the default merge keeps our sources but never
    // fires style.load, so the reinstall would never run.
    expect(instance.setStyleCalls[0].options).toEqual({ diff: false });
  });

  test('drops style-ready until the new style loads', async () => {
    renderMap();
    await loadStyle();
    expect(screen.getByTestId('ready')).toHaveTextContent('true');

    await act(async () => {
      ctx.setBasemap('carto-dark');
    });
    expect(screen.getByTestId('ready')).toHaveTextContent('false');

    await loadStyle();
    expect(screen.getByTestId('ready')).toHaveTextContent('true');
  });

  test('remembers the choice', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setBasemap('carto-dark');
    });

    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('carto-dark');
  });

  test('does not recreate the map', async () => {
    renderMap();
    await loadStyle();
    const first = instance;

    await act(async () => {
      ctx.setBasemap('osm');
    });

    expect(instance).toBe(first);
    expect(first.removed).toBe(false);
  });
});

describe('satellite underlay', () => {
  test('is off until asked for', async () => {
    renderMap();
    await loadStyle();
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeUndefined();
  });

  test('inserts above the basemap background, not on top of everything', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setSatellite(true);
    });

    expect(instance.addedSources.has(SATELLITE_SOURCE_ID)).toBe(true);
    const added = instance.addedLayers.find((l) => l.id === SATELLITE_LAYER_ID);
    expect(added?.before).toBe('roads');
  });

  test('turning it off removes the layer', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setSatellite(true);
    });

    await act(async () => {
      ctx.setSatellite(false);
    });

    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeUndefined();
  });

  test('reinstalls itself after a basemap change wipes it', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setSatellite(true);
    });
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();

    await act(async () => {
      ctx.setBasemap('carto-dark');
    });
    // The reload destroyed everything the app added.
    instance.addedLayers = [];
    instance.addedSources.clear();

    await loadStyle();

    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();
  });
});

describe('useMapContext', () => {
  test('throws outside the provider', () => {
    const Outside = () => {
      useMapContext();
      return null;
    };
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Outside />)).toThrow(/inside <MapProvider>/);
  });
});
