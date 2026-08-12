// Installing, painting and filtering the map's overlays.
//
// The React half of the imperative escape hatch: `src/map/overlays.ts` knows what
// to do to the map, and this decides when.
//
// Five effects instead of one, on purpose — each keyed on exactly what it
// depends on, so a pan does not rebuild layers and a legend click does not
// re-fetch:
//
//   1. keep the newest data reachable from the install effect
//   2. install     [map, styleEpoch]              — and reinstall after a basemap change
//   3. paint       [map, styleEpoch, buckets]     — setData only, layers untouched
//   4. visibility  [map, styleEpoch, hidden…]     — the legend's on/off toggles
//   5. filter      [map, styleEpoch, hiddenAgencies] — the campground legend
//   6. handlers    [map, styleEpoch, …actions]    — clicks and the cursor
//
// `styleEpoch` is in every one of them because `setStyle({ diff: false })`
// destroys every source and layer the app added; see `MapProvider`.
import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { FeatureCollection } from 'geojson';
import type { MapLayerMouseEvent } from 'maplibre-gl';
import { jsonGetOk } from '@/api/http';
import { queryKeys } from '@/queries/keys';
import { hiddenAgencyFilter } from '@/map/agencies';
import {
  POINT_OVERLAYS,
  firstInstalledPinLayerId,
  hitLayerIdOf,
  installPointOverlay,
  installedHitLayerIds,
  overlaySpec,
  setOverlayData,
  setOverlayFilter,
  setOverlayVisible,
} from '@/map/overlays';
import { pinFeatureId, type PinFeature } from '@/map/pins';
import { STATE_LINES_URL, installStateLines } from '@/map/state-lines';
import { useMapStore } from '@/stores/mapStore';
import { useMapContext } from './MapProvider';
import type { ViewportPois } from './useViewportPois';

export function useMapOverlays(pois: ViewportPois): void {
  const { map, styleEpoch } = useMapContext();
  const hiddenOverlays = useMapStore((s) => s.hiddenOverlays);
  const hiddenAgencies = useMapStore((s) => s.hiddenAgencies);
  const selectPoi = useMapStore((s) => s.selectPoi);
  const clearSelectedPoi = useMapStore((s) => s.clearSelectedPoi);

  /**
   * The data an install should start from.
   *
   * A ref, and synced in an effect declared BEFORE the install effect so it is
   * already current when that runs. Without it a basemap change would install
   * empty layers and only fill them in the paint effect below — one frame of a map
   * with no pins on it, which is exactly the flash the vanilla reinstall-from-cache
   * avoided.
   */
  const latest = useRef(pois.buckets);
  useEffect(() => {
    latest.current = pois.buckets;
  }, [pois.buckets]);

  useEffect(() => {
    if (!map || !styleEpoch) return;
    for (const spec of POINT_OVERLAYS) installPointOverlay(map, spec, latest.current[spec.key]);
  }, [map, styleEpoch]);

  useEffect(() => {
    if (!map || !styleEpoch) return;
    for (const spec of POINT_OVERLAYS) setOverlayData(map, spec, pois.buckets[spec.key]);
  }, [map, styleEpoch, pois.buckets]);

  useEffect(() => {
    if (!map || !styleEpoch) return;
    for (const spec of POINT_OVERLAYS) {
      setOverlayVisible(map, spec, !hiddenOverlays.includes(spec.key));
    }
  }, [map, styleEpoch, hiddenOverlays]);

  // Campgrounds have no on/off toggle — 50+ managing agencies cannot be a
  // checkbox — so their legend is a filter instead of a visibility switch.
  useEffect(() => {
    if (!map || !styleEpoch) return;
    setOverlayFilter(map, overlaySpec('cg'), hiddenAgencyFilter(hiddenAgencies));
  }, [map, styleEpoch, hiddenAgencies]);

  useEffect(() => {
    if (!map || !styleEpoch) return;
    const canvas = map.getCanvas();
    const unbind: Array<() => void> = [];

    for (const spec of POINT_OVERLAYS) {
      // Bound to the hit layer, not the pin layer: it sits on top, MapLibre
      // dispatches to the topmost matching layer, and it is the one with a
      // finger-sized radius.
      const hitLayer = hitLayerIdOf(spec);

      const onClick = (event: MapLayerMouseEvent) => {
        const id = pinFeatureId(event.features?.[0] as PinFeature | undefined);
        // The drawer hydrates from the id through GET /api/pois/{id}; the slim
        // pin carries nothing else worth handing on. Phase 4c renders it.
        if (id != null) selectPoi(id);
      };
      const onEnter = () => {
        canvas.style.cursor = 'pointer';
      };
      const onLeave = () => {
        canvas.style.cursor = '';
      };

      map.on('click', hitLayer, onClick);
      map.on('mouseenter', hitLayer, onEnter);
      map.on('mouseleave', hitLayer, onLeave);
      unbind.push(() => {
        map.off('click', hitLayer, onClick);
        map.off('mouseenter', hitLayer, onEnter);
        map.off('mouseleave', hitLayer, onLeave);
      });
    }

    /**
     * A click that hit no pin closes the drawer.
     *
     * The layer handlers above run for their own clicks; this asks after the fact
     * whether anything pickable was under the cursor, which is cheaper than
     * subscribing to every layer and correct regardless of handler order. It also
     * clears the browse-mode pin, or an empty-space click would close the drawer
     * and leaves route state to the trip planner.
     */
    const onMapClick = (event: MapLayerMouseEvent) => {
      const layers = installedHitLayerIds(map);
      const hits = layers.length > 0 ? map.queryRenderedFeatures(event.point, { layers }) : [];
      if (hits.length > 0) return;
      clearSelectedPoi();
    };
    map.on('click', onMapClick);
    unbind.push(() => map.off('click', onMapClick));

    return () => {
      for (const off of unbind) off();
    };
  }, [map, styleEpoch, selectPoi, clearSelectedPoi]);
}

/**
 * State and provincial boundary lines.
 *
 * A static file rather than a bbox query, so it is fetched once and reinstalled on
 * every style load like everything else. A failure leaves the map without
 * boundaries and is not otherwise surfaced — same as the vanilla loader, which
 * logged and carried on.
 */
export function useStateLines(): void {
  const { map, styleEpoch } = useMapContext();

  const { data } = useQuery({
    queryKey: queryKeys.staticGeoJson(STATE_LINES_URL),
    queryFn: ({ signal }) => jsonGetOk<FeatureCollection>(STATE_LINES_URL, { signal }),
    // A build artifact, not live data: never stale, never evicted while the page
    // is open. Re-fetching it on a pan would be pure waste.
    staleTime: Infinity,
    gcTime: Infinity,
  });

  useEffect(() => {
    if (!map || !styleEpoch || !data) return;
    // Under the pins. The boundaries land whenever their fetch resolves, which is
    // normally after the overlays are installed, so the anchor is what keeps a
    // line layer from drawing over every dot.
    installStateLines(map, data, firstInstalledPinLayerId(map));
  }, [map, styleEpoch, data]);
}
