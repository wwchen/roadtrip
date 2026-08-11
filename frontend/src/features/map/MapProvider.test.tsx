// The style lifecycle, which is the whole reason MapProvider exists.
//
// A basemap change calls setStyle(..., { diff: false }), and that full reload destroys
// every source and layer the app added. The vanilla app coped with a module-level
// `reinstallOverlays()` registry driven by a `style.load` listener; here the same fact
// is `styleReady` state, so overlays reinstall by ordinary effect dependency. If that
// signal is wrong, every overlay in Phase 4b–4e silently ends up attached to a style
// that no longer describes it — so it is pinned here, before any of them exist.
//
// MapLibre needs WebGL, which jsdom has none of, so the instance is faked. The fake
// records the calls that matter and lets tests fire `style.load` by hand.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useThemeStore } from '@/stores/themeStore';
import { BASEMAPS, BASEMAP_STORAGE_KEY, DARK_BASEMAP, DEFAULT_BASEMAP } from './basemaps';

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

// useThemeStore is a module singleton, never reset between test files — any test
// that moves the mode off its 'light' default has to put it back, or later tests
// (in this file and any file that shares the module registry) inherit the wrong
// starting mode.
afterEach(() => {
  useThemeStore.getState().setChoice('light');
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

  // MapLibre measures its container, so an unsized ancestor gives a 0x0 canvas: a
  // map that initialises cleanly, passes every test, and draws nothing. The provider
  // supplies the sized frame itself so a host page cannot forget to.
  test('wraps the canvas in its own sized frame', () => {
    renderMap();
    expect(screen.getByTestId('map-canvas').parentElement).toHaveClass('rt-map-shell');
  });

  // Children render above the canvas — that is what lets the drawer and topbar
  // overlay the map rather than sit below it.
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

  // Overlays must not touch the map before the style exists.
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

  // The reload destroys every source and layer we added, so nothing may consider
  // itself installed until the new style announces itself.
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

describe('following the theme', () => {
  test('opens on the dark default when dark mode has nothing remembered', () => {
    useThemeStore.getState().setChoice('dark');
    renderMap();
    expect(ctx.basemapKey).toBe(DARK_BASEMAP);
    expect(instance.options.style).toBe(BASEMAPS[DARK_BASEMAP].style);
  });

  test('reports auto until a basemap is explicitly picked', () => {
    renderMap();
    expect(ctx.isAutoBasemap).toBe(true);
  });

  test('re-styles to the dark default when the mode changes after mount', async () => {
    renderMap();
    await loadStyle();
    expect(instance.setStyleCalls).toHaveLength(0);

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    expect(instance.setStyleCalls).toHaveLength(1);
    expect(instance.setStyleCalls[0].style).toBe(BASEMAPS[DARK_BASEMAP].style);
    expect(ctx.basemapKey).toBe(DARK_BASEMAP);
  });

  // The trap this task's brief calls out by name: a mode change resolves a key,
  // it does not choose one. Persisting it would pin "auto" to whatever mode was
  // active the first time it fired.
  test('does not persist the key the mode effect resolves', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBeNull();
    expect(ctx.isAutoBasemap).toBe(true);
  });

  // An explicit pick outranks the mode: it must survive a later mode change
  // rather than being overwritten by the mode's default.
  test('an explicit pick survives a mode change', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setBasemap('osm');
    });
    expect(ctx.isAutoBasemap).toBe(false);

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    expect(ctx.basemapKey).toBe('osm');
    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('osm');
  });

  test('resetBasemap forgets the explicit pick and returns to the mode default', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setBasemap('osm');
    });
    expect(ctx.isAutoBasemap).toBe(false);

    await act(async () => {
      ctx.resetBasemap();
    });

    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBeNull();
    expect(ctx.isAutoBasemap).toBe(true);
    expect(ctx.basemapKey).toBe(DEFAULT_BASEMAP);
  });
});

describe('satellite underlay', () => {
  test('is off until asked for', async () => {
    renderMap();
    await loadStyle();
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeUndefined();
  });

  // Inserted above the background so roads, parks and labels still draw on top.
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

  // The exact thing the styleReady signal exists for: a basemap change wipes the
  // underlay, and it has to come back by itself.
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
  // A silent null would surface as a map that simply never shows anything.
  test('throws outside the provider', () => {
    const Outside = () => {
      useMapContext();
      return null;
    };
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Outside />)).toThrow(/inside <MapProvider>/);
  });
});
