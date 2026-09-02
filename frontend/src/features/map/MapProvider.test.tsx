// MapLibre needs WebGL, which jsdom lacks, so the instance is faked. The fake
// records the calls that matter and lets tests fire `style.load` by hand.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useThemeStore } from '@/stores/themeStore';
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
  /** Fire `style.load` inside `setStyle`, as the real map does for a style it
   *  need not fetch. See the note on `setStyle`. */
  loadsStyleSynchronously = false;
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

  /**
   * Models the two shapes `setStyle` actually has, because the difference is
   * load-bearing and a fake that ignored it hid a real bug for a whole branch.
   *
   * Either way the reload destroys every source and layer the app added. What
   * differs is WHEN the new style announces itself: a style URL has to be fetched,
   * so `style.load` lands in a later task and the test fires it by hand; an inline
   * StyleSpecification needs no fetch, so MapLibre fires `style.load` synchronously
   * inside this very call. Verified against the real library on :8765 — setStyle at
   * t=4611.3ms, style.load at t=4615.7ms, same synchronous block.
   *
   * That matters because the reload then lands in ONE React batch with whatever
   * triggered it, so any signal that cannot represent "loaded again" is
   * indistinguishable from "never changed".
   *
   * Every basemap is a style URL now, so no registry entry reaches the synchronous
   * path any more; `loadsStyleSynchronously` reproduces it directly rather than
   * leaving the regression uncovered until some future basemap goes inline again.
   */
  setStyle(style: unknown, options: unknown) {
    this.setStyleCalls.push({ style, options });
    this.addedSources.clear();
    this.addedLayers = [];
    if (this.loadsStyleSynchronously || (typeof style === 'object' && style !== null))
      this.emit('style.load');
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
  // A boolean here; the epoch's own value is asserted through `ctx`.
  return <span data-testid="ready">{String(Boolean(ctx.styleEpoch))}</span>;
}

const renderMap = () =>
  render(
    <MapProvider>
      <Probe />
    </MapProvider>,
  );

/** MapLibre announces a loaded style; the provider turns that into a new `styleEpoch`. */
const loadStyle = async () => {
  await act(async () => {
    instance.emit('style.load');
  });
};

beforeEach(() => {
  window.localStorage.clear();
});

// The theme store is a module singleton, never reset between test files: a test
// that moves the mode off 'light' has to put it back.
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
    window.localStorage.setItem(BASEMAP_STORAGE_KEY, 'carto-voyager');
    renderMap();
    expect(ctx.basemapKey).toBe('terrain');
    expect(instance.options.style).toBe(BASEMAPS.terrain.style.light);
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
      ctx.setBasemap('terrain');
    });

    expect(instance.setStyleCalls).toHaveLength(1);
    expect(instance.setStyleCalls[0].style).toBe(BASEMAPS.terrain.style.light);
    // diff:false is load-bearing: the default merge keeps our sources but never
    // fires style.load, so the reinstall would never run.
    expect(instance.setStyleCalls[0].options).toEqual({ diff: false });
  });

  // Pinned on a style URL: the gap is only observable when the style must be
  // fetched, since an inline style closes it inside `setStyle` itself.
  test('drops style-ready until a fetched style loads', async () => {
    renderMap();
    await loadStyle();
    expect(screen.getByTestId('ready')).toHaveTextContent('true');

    await act(async () => {
      ctx.setBasemap('outdoors');
    });
    expect(screen.getByTestId('ready')).toHaveTextContent('false');

    await loadStyle();
    expect(screen.getByTestId('ready')).toHaveTextContent('true');
  });

  test('remembers the choice', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setBasemap('terrain');
    });

    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('terrain');
  });

  test('does not recreate the map', async () => {
    renderMap();
    await loadStyle();
    const first = instance;

    await act(async () => {
      ctx.setBasemap('outdoors');
    });

    expect(instance).toBe(first);
    expect(first.removed).toBe(false);
  });
});

describe('following the theme', () => {
  test('opens on the same cartography in dark, with its dark tiles', () => {
    useThemeStore.getState().setChoice('dark');
    renderMap();
    expect(ctx.basemapKey).toBe(DEFAULT_BASEMAP);
    expect(instance.options.style).toBe(BASEMAPS[DEFAULT_BASEMAP].style.dark);
  });

  // The bug: brightness used to be a basemap, so switching theme swapped the whole
  // cartography. It must now swap only the tiles of the map you already chose.
  test('a mode change keeps the cartography and re-styles to its other tiles', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setBasemap('terrain');
    });
    await loadStyle();
    const before = instance.setStyleCalls.length;

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    expect(ctx.basemapKey).toBe('terrain');
    expect(instance.setStyleCalls).toHaveLength(before + 1);
    expect(instance.setStyleCalls[before].style).toBe(BASEMAPS.terrain.style.dark);
  });

  test('a mode change does not persist anything', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBeNull();
  });

  test('an explicit pick survives a mode change', async () => {
    renderMap();
    await loadStyle();

    await act(async () => {
      ctx.setBasemap('terrain');
    });

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    expect(ctx.basemapKey).toBe('terrain');
    expect(window.localStorage.getItem(BASEMAP_STORAGE_KEY)).toBe('terrain');
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

  test('reinstalls itself after a fetched basemap change wipes it', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setSatellite(true);
    });
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();

    await act(async () => {
      ctx.setBasemap('outdoors');
    });
    // The fake's setStyle destroyed everything the app added, as the real one does.
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeUndefined();

    await loadStyle();

    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();
  });

  /**
   * The regression this whole epoch exists for, reproduced end to end.
   *
   * On :8765 as a `system` user, flipping the OS to dark swapped the basemap and
   * silently dropped EVERY overlay — superchargers, campgrounds, Planet Fitness,
   * state lines — with the legend still showing their counts. Nothing was logged.
   *
   * The mechanism, in one sentence: an inline style is applied in place, so
   * `style.load` fires synchronously inside `setStyle`, so the reset and the reload
   * land in one React batch — and a boolean that goes true -> false -> true within a
   * single batch is, to React, a boolean that never changed. Every effect keyed on
   * it therefore skipped the reinstall, after `diff: false` had already destroyed
   * the layers.
   *
   * The dark default reached this path when it was a raster inline style. Nothing
   * in the registry does any more, so the fake is told to load synchronously.
   *
   * The satellite underlay stands in for the overlay hooks here: it is the one
   * consumer of the signal that lives in this component, and it reinstalls through
   * exactly the same effect-dependency mechanism they do.
   */
  test('reinstalls itself when a style loads synchronously', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setSatellite(true);
    });
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();
    const before = instance.setStyleCalls.length;

    instance.loadsStyleSynchronously = true;
    await act(async () => {
      ctx.setBasemap('terrain');
    });

    // No hand-fired style.load: the style already announced itself inside setStyle.
    // If the reinstall needs a nudge from the test, it is broken.
    expect(instance.setStyleCalls).toHaveLength(before + 1);
    expect(instance.setStyleCalls[before].style).toBe(BASEMAPS.terrain.style.light);
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
