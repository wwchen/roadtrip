import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Map as MapLibreMap, setWorkerUrl } from 'maplibre-gl';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { MapRuntimeContext, type MapContextValue } from '@/map/context';
export { useMapContext } from '@/map/context';
import { useThemeStore } from '@/stores/themeStore';
import 'maplibre-gl/dist/maplibre-gl.css';
import './map.css';
import {
  SATELLITE_LAYER_ID,
  SATELLITE_SOURCE_ID,
  basemapStyle,
  forgetBasemapKey,
  initialBasemapKey,
  rememberBasemapKey,
  satelliteSource,
  storedBasemapKey,
} from './basemaps';

/**
 * The map's opening view: the continental US, as the vanilla map opens it
 * (`web/app.js`). Phase 4a's values said they matched and did not — they opened on
 * California at z5, which also made the first POI fetch a different request.
 */
const INITIAL_CENTER: [number, number] = [-98.5, 39.5];
const INITIAL_ZOOM = 3.6;

/**
 * MapLibre 6 ships its worker as a sibling of the library module and derives the
 * default URL from `import.meta.url`. Once Vite moves the library into a hashed
 * chunk, that sibling is not copied automatically: the browser requests the
 * nonexistent `/assets/maplibre-gl-worker.mjs`, leaving every vector-tile and
 * GeoJSON update pending forever. `?worker&url` makes Vite bundle the worker and
 * its shared module into one content-hashed asset, then gives MapLibre that URL.
 */
setWorkerUrl(maplibreWorkerUrl);

/**
 * The map instance, and the style lifecycle everything else hangs off.
 *
 * MapLibre is imperative and owns its own DOM, so this is the escape hatch the plan
 * prescribes: React owns *when* the map exists and what the current basemap is; the
 * map owns its canvas. Nothing here renders map content — layers and markers are
 * installed by hooks that wait on `styleReady`.
 *
 * **The style lifecycle is the whole point of this component.** Changing basemap
 * calls `setStyle(..., { diff: false })`, and that full reload *destroys every source
 * and layer we added*. The vanilla app handled this with a `style.load` listener that
 * called `reinstallOverlays()` — a module-level registry of re-install callbacks.
 * Here the same fact is expressed as state: `styleReady` drops to false on a basemap
 * change and returns to true when the new style has loaded, so overlay hooks
 * reinstall by ordinary effect dependency rather than through a global hook.
 *
 * `diff: false` is deliberate and load-bearing, not a performance choice. The default
 * incremental merge keeps our sources in place but does NOT fire `style.load`, so the
 * reinstall never runs and the overlays end up half-attached to a style that no longer
 * describes them.
 */
export function MapProvider({ children }: { children: ReactNode }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const [map, setMap] = useState<MapLibreMap | null>(null);
  const [styleReady, setStyleReady] = useState(false);
  const mode = useThemeStore((s) => s.mode);
  const [basemapKey, setBasemapKey] = useState(() => initialBasemapKey(mode));
  // Read by the create-once effect, which must not depend on `basemapKey` —
  // the basemap is applied via setStyle, never by recreating the map.
  const basemapKeyRef = useRef(basemapKey);
  const [satellite, setSatellite] = useState(false);

  // Created once. The basemap is applied through setStyle rather than by
  // recreating the map, so this effect must not depend on basemapKey.
  useEffect(() => {
    const container = containerRef.current;
    if (!container || mapRef.current) return;

    const instance = new MapLibreMap({
      container,
      // The state this is seeded from, not a second read of localStorage — two
      // derivations of one value can only ever drift.
      style: basemapStyle(basemapKeyRef.current),
      center: INITIAL_CENTER,
      zoom: INITIAL_ZOOM,
    });

    // `style.load` rather than `load`: it fires again after every setStyle, which
    // is exactly when overlays need reinstalling.
    const onStyleLoad = () => setStyleReady(true);
    instance.on('style.load', onStyleLoad);

    mapRef.current = instance;
    setMap(instance);

    return () => {
      instance.off('style.load', onStyleLoad);
      instance.remove();
      mapRef.current = null;
      setMap(null);
      setStyleReady(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The style-reload mechanics, shared by an explicit pick and the mode effect
  // below. Deliberately does not persist anything — `rememberBasemapKey` is
  // split out and called only by `changeBasemap`, because the mode effect and
  // `resetBasemap` resolve a key rather than choose one, and storing what they
  // resolved would pin "auto" to that key on first use.
  const applyBasemap = useCallback((key: string) => {
    setBasemapKey(key);
    basemapKeyRef.current = key;
    const instance = mapRef.current;
    if (!instance) return;
    // Overlays are about to be destroyed by the reload, so stop anything from
    // touching them before the new style announces itself.
    setStyleReady(false);
    instance.setStyle(basemapStyle(key), { diff: false });
  }, []);

  const changeBasemap = useCallback(
    (key: string) => {
      rememberBasemapKey(key);
      applyBasemap(key);
    },
    [applyBasemap],
  );

  const resetBasemap = useCallback(() => {
    forgetBasemapKey();
    applyBasemap(initialBasemapKey(mode));
  }, [mode, applyBasemap]);

  // Re-style on every mode change, even when the key is unchanged: the overlays
  // read their colours through `tokens.ts`, whose cache the theme store has just
  // reset, and a full setStyle is what reinstalls them. Skipped on the first run,
  // when the map was created with the right style already.
  const appliedMode = useRef(mode);
  useEffect(() => {
    if (appliedMode.current === mode) return;
    appliedMode.current = mode;
    if (!map) return;
    applyBasemap(initialBasemapKey(mode));
  }, [mode, map, applyBasemap]);

  // Satellite is an underlay, reinstalled on every style load because the reload
  // wipes it along with everything else.
  useEffect(() => {
    if (!map || !styleReady) return;

    if (!satellite) {
      if (map.getLayer(SATELLITE_LAYER_ID)) map.removeLayer(SATELLITE_LAYER_ID);
      return;
    }
    if (map.getLayer(SATELLITE_LAYER_ID)) return;

    if (!map.getSource(SATELLITE_SOURCE_ID)) map.addSource(SATELLITE_SOURCE_ID, satelliteSource);
    // Inserted just above the basemap's background so roads, parks and labels still
    // draw on top of the imagery.
    const firstNonBackground = map.getStyle().layers?.find((l) => l.type !== 'background');
    map.addLayer(
      { id: SATELLITE_LAYER_ID, type: 'raster', source: SATELLITE_SOURCE_ID },
      firstNonBackground?.id,
    );
  }, [map, styleReady, satellite]);

  const value = useMemo<MapContextValue>(
    () => ({
      map,
      styleReady,
      basemapKey,
      setBasemap: changeBasemap,
      isAutoBasemap: storedBasemapKey() === null,
      resetBasemap,
      satellite,
      setSatellite,
    }),
    [map, styleReady, basemapKey, changeBasemap, resetBasemap, satellite],
  );

  return (
    <MapRuntimeContext.Provider value={value}>
      {/* The provider owns its own frame rather than trusting each host page to
          size one. MapLibre measures its container, so an unsized ancestor yields a
          0x0 canvas — a map that initialises cleanly, passes every test, and draws
          nothing. Children render after the canvas and above it, which is what makes
          the drawer, topbar and controls overlay the map. */}
      <div className="rt-map-shell">
        <div ref={containerRef} className="rt-map-canvas" data-testid="map-canvas" />
        {children}
      </div>
    </MapRuntimeContext.Provider>
  );
}
