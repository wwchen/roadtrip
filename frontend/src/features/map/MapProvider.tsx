import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Map as MapLibreMap } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import {
  SATELLITE_LAYER_ID,
  SATELLITE_SOURCE_ID,
  basemapStyle,
  initialBasemapKey,
  rememberBasemapKey,
  satelliteSource,
} from './basemaps';

/** The map's opening view, as the vanilla map had it. */
const INITIAL_CENTER: [number, number] = [-119.5, 37.5];
const INITIAL_ZOOM = 5;

export interface MapContextValue {
  /** The live MapLibre instance, or null before the first render completes. */
  map: MapLibreMap | null;
  /**
   * True once the style has loaded and overlays may be installed.
   *
   * Every overlay install has to wait for this, and it goes FALSE again on a basemap
   * change — see `setBasemap`.
   */
  styleReady: boolean;
  basemapKey: string;
  setBasemap: (key: string) => void;
  satellite: boolean;
  setSatellite: (on: boolean) => void;
}

const MapContext = createContext<MapContextValue | null>(null);

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
  const [basemapKey, setBasemapKey] = useState(initialBasemapKey);
  const [satellite, setSatellite] = useState(false);

  // Created once. The basemap is applied through setStyle rather than by
  // recreating the map, so this effect must not depend on basemapKey.
  useEffect(() => {
    const container = containerRef.current;
    if (!container || mapRef.current) return;

    const instance = new MapLibreMap({
      container,
      style: basemapStyle(initialBasemapKey()),
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

  const changeBasemap = (key: string) => {
    setBasemapKey(key);
    rememberBasemapKey(key);
    const instance = mapRef.current;
    if (!instance) return;
    // Overlays are about to be destroyed by the reload, so stop anything from
    // touching them before the new style announces itself.
    setStyleReady(false);
    instance.setStyle(basemapStyle(key), { diff: false });
  };

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

  return (
    <MapContext.Provider
      value={{ map, styleReady, basemapKey, setBasemap: changeBasemap, satellite, setSatellite }}
    >
      <div ref={containerRef} className="rt-map-canvas" data-testid="map-canvas" />
      {children}
    </MapContext.Provider>
  );
}

/**
 * The map context.
 *
 * Throws when used outside the provider rather than returning null: every caller
 * needs the instance, and a silent null would surface as a map that simply never
 * shows anything.
 */
export function useMapContext(): MapContextValue {
  const value = useContext(MapContext);
  if (!value) throw new Error('useMapContext must be used inside <MapProvider>');
  return value;
}
