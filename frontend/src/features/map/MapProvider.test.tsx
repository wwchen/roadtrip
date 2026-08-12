// The style lifecycle, which is the whole reason MapProvider exists.
//
// A basemap change calls setStyle(..., { diff: false }), and that full reload destroys
// every source and layer the app added. The vanilla app coped with a module-level
// `reinstallOverlays()` registry driven by a `style.load` listener; here the same fact
// is `styleEpoch` state, so overlays reinstall by ordinary effect dependency. If that
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
   * That matters because the dark default (`carto-dark`) is inline: the reset and
   * the reload then land in ONE React batch, so any signal that cannot represent
   * "loaded again" is indistinguishable from "never changed".
   */
  setStyle(style: unknown, options: unknown) {
    this.setStyleCalls.push({ style, options });
    this.addedSources.clear();
    this.addedLayers = [];
    if (typeof style === 'object' && style !== null) this.emit('style.load');
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
  // Rendered as a boolean because that is what the readiness assertions are about;
  // the epoch's own value is asserted through `ctx` where a test needs it.
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
  //
  // Pinned on a style URL specifically. That gap is only OBSERVABLE when the new
  // style has to be fetched; an inline style closes it inside `setStyle` itself,
  // which is the case the epoch below exists for.
  test('drops style-ready until a fetched style loads', async () => {
    renderMap();
    await loadStyle();
    expect(screen.getByTestId('ready')).toHaveTextContent('true');

    await act(async () => {
      ctx.setBasemap('openfreemap-bright');
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

  // The exact thing the style signal exists for: a basemap change wipes the
  // underlay, and it has to come back by itself.
  test('reinstalls itself after a fetched basemap change wipes it', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setSatellite(true);
    });
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();

    await act(async () => {
      ctx.setBasemap('openfreemap-bright');
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
   * The mechanism, in one sentence: the dark default is an inline style, so
   * `style.load` fires synchronously inside `setStyle`, so the reset and the reload
   * land in one React batch — and a boolean that goes true -> false -> true within a
   * single batch is, to React, a boolean that never changed. Every effect keyed on
   * it therefore skipped the reinstall, after `diff: false` had already destroyed
   * the layers.
   *
   * The satellite underlay stands in for the overlay hooks here: it is the one
   * consumer of the signal that lives in this component, and it reinstalls through
   * exactly the same effect-dependency mechanism they do.
   */
  test('reinstalls itself when a mode change loads an inline style synchronously', async () => {
    renderMap();
    await loadStyle();
    await act(async () => {
      ctx.setSatellite(true);
    });
    expect(instance.getLayer(SATELLITE_LAYER_ID)).toBeDefined();

    await act(async () => {
      useThemeStore.getState().setChoice('dark');
    });

    // No hand-fired style.load: the inline style already announced itself inside
    // setStyle. If the reinstall needs a nudge from the test, it is broken.
    expect(instance.setStyleCalls).toHaveLength(1);
    expect(instance.setStyleCalls[0].style).toBe(BASEMAPS[DARK_BASEMAP].style);
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
