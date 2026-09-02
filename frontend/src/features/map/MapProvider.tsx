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
import type { ThemeMode } from '@/lib/theme';
import 'maplibre-gl/dist/maplibre-gl.css';
import './map.css';
import {
  SATELLITE_LAYER_ID,
  SATELLITE_SOURCE_ID,
  basemapStyle,
  initialBasemapKey,
  rememberBasemapKey,
  satelliteSource,
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
 * The map instance and its style lifecycle. React owns when the map exists and
 * which basemap is current; the map owns its canvas. Layers and markers are
 * installed by hooks that wait on `styleEpoch`.
 *
 * `diff: false` is load-bearing, not a performance choice: the default
 * incremental merge does not fire `style.load`, so overlays never reinstall and
 * end up half-attached to a style that no longer describes them.
 */
export function MapProvider({ children }: { children: ReactNode }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const [map, setMap] = useState<MapLibreMap | null>(null);
  const [styleEpoch, setStyleEpoch] = useState(0);
  // Counted outside React state on purpose: `setStyleEpoch((n) => n + 1)` would
  // apply to the 0 already queued in the batch and land back on the generation
  // the reset meant to leave.
  const nextStyleEpoch = useRef(0);
  const mode = useThemeStore((s) => s.mode);
  const [basemapKey, setBasemapKey] = useState(() => initialBasemapKey());
  // Read by the create-once effect, which must not depend on `basemapKey` —
  // the basemap is applied via setStyle, never by recreating the map.
  const basemapKeyRef = useRef(basemapKey);
  // The map is created once and `applyBasemap` is stable, so both read the mode
  // through a ref rather than closing over a value that would go stale.
  const modeRef = useRef(mode);
  modeRef.current = mode;
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
      style: basemapStyle(basemapKeyRef.current, modeRef.current),
      center: INITIAL_CENTER,
      zoom: INITIAL_ZOOM,
    });

    // `style.load` rather than `load`: it fires again after every setStyle, which
    // is exactly when overlays need reinstalling.
    const onStyleLoad = () => setStyleEpoch((nextStyleEpoch.current += 1));
    instance.on('style.load', onStyleLoad);

    mapRef.current = instance;
    setMap(instance);

    return () => {
      instance.off('style.load', onStyleLoad);
      instance.remove();
      mapRef.current = null;
      setMap(null);
      setStyleEpoch(0);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Does not persist: the mode effect re-applies the same cartography rather than
  // choosing one, and storing that would pin a derived value. Only `changeBasemap`
  // remembers.
  const applyBasemap = useCallback((key: string, forMode: ThemeMode) => {
    setBasemapKey(key);
    basemapKeyRef.current = key;
    const instance = mapRef.current;
    if (!instance) return;
    // Stop anything touching the overlays the reload is about to destroy. When the
    // style loads synchronously this 0 never commits on its own — see `styleEpoch`.
    setStyleEpoch(0);
    instance.setStyle(basemapStyle(key, forMode), { diff: false });
  }, []);

  const changeBasemap = useCallback(
    (key: string) => {
      rememberBasemapKey(key);
      applyBasemap(key, modeRef.current);
    },
    [applyBasemap],
  );

  // Re-style on every mode change: the same cartography has different tiles per
  // mode, and overlay colours come from `tokens.ts`, whose cache the theme store
  // just reset — a full setStyle is what reinstalls both.
  const appliedMode = useRef(mode);
  useEffect(() => {
    if (appliedMode.current === mode) return;
    appliedMode.current = mode;
    if (!map) return;
    applyBasemap(basemapKeyRef.current, mode);
  }, [mode, map, applyBasemap]);

  // Satellite is an underlay, reinstalled on every style load because the reload
  // wipes it along with everything else.
  useEffect(() => {
    if (!map || !styleEpoch) return;

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
  }, [map, styleEpoch, satellite]);

  const value = useMemo<MapContextValue>(
    () => ({
      map,
      styleEpoch,
      basemapKey,
      setBasemap: changeBasemap,
      satellite,
      setSatellite,
    }),
    [map, styleEpoch, basemapKey, changeBasemap, satellite],
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
