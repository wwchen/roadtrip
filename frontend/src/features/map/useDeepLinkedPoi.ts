// Opening the map on a shared pin: `/map?poi=<id>`.
//
// Port of the POI half of `restoreSharedLinkFromUrl` / `openPoiById` /
// `openSharedPoiFeature` in web/topbar.js. (The `?route=` half is Phase 4e; it
// restores a whole trip, which needs the topbar's stop list to exist first.)
//
// `poi-url.ts` writes the parameter; this reads it. Both halves are needed for the
// link to be a link — without this the URL updates as you click pins but pasting one
// back opens a bare map, which is how the parameter behaved in 4c before this hook
// and is what the smoke suite's `/?poi=…` load catches.
//
// Three things happen on restore, and the order is the original's: make the pin's
// overlay visible, move the camera to it, open the drawer. The first matters because
// a shared link can point at a category — or, for a campground, an agency — the
// recipient has switched off, and a drawer for an invisible pin reads as a bug.
import { useEffect, useRef } from 'react';
import { poiFromUrl } from '@/lib/poi-url';
import { usePoiDetail } from '@/queries/poi-detail';
import { geomCenter, hasCoordinates, zoomForBbox } from '@/lib/geo';
import { featureAgency } from '@/map/agencies';
import { overlayForCategory } from '@/map/overlays';
import { useMapStore } from '@/stores/mapStore';
import { useMapContext } from './MapProvider';

/** Zoom for a shared point pin. A campground or a charger is a place, not a region. */
const SHARED_POINT_ZOOM = 13;
/** `flyTo` speed, matching the vanilla restore — brisk enough not to feel like a tour. */
const SHARED_FLY_SPEED = 1.6;
/** Categories whose geometry is an area, so the camera frames it instead of zooming in. */
const AREA_CATEGORIES = ['national-park', 'state-park'];

/**
 * Restore the POI named in the URL, once per page load.
 *
 * Reads through `usePoiDetail`, which is the same query key the drawer uses, so the
 * hydration is one request shared with the drawer rather than a second fetch.
 *
 * Deliberately fires only once. The vanilla version guarded with a `restored` flag
 * for the same reason: the camera move is a courtesy on arrival, and re-running it
 * whenever the detail re-settles would yank the map out from under someone who had
 * since panned away.
 */
export function useDeepLinkedPoi(): void {
  const { map, styleEpoch } = useMapContext();
  const selectPoi = useMapStore((s) => s.selectPoi);
  const setOverlayHidden = useMapStore((s) => s.setOverlayHidden);
  const setAgencyHidden = useMapStore((s) => s.setAgencyHidden);

  // Captured on the first render, before anything can rewrite the URL: the drawer
  // itself calls `showPoiInUrl`, so reading `window.location` later would be reading
  // our own writes.
  const deepLinkId = useRef<string | null>(null);
  if (deepLinkId.current === null) deepLinkId.current = poiFromUrl();

  const id = deepLinkId.current;
  const { data } = usePoiDetail(id && id !== '' ? id : null);

  // Selecting is independent of the map: the drawer renders over the canvas and
  // does not need a style, so a shared link shows its panel even while tiles load.
  const selected = useRef(false);
  useEffect(() => {
    if (!id || selected.current) return;
    selected.current = true;
    selectPoi(id);
  }, [id, selectPoi]);

  const restored = useRef(false);
  useEffect(() => {
    // `styleEpoch` is this port's `isMapReadyForSharedLink`: `flyTo` before the
    // style is up is silently dropped, which is the bug the vanilla
    // `restoreAfterMapReady` dance existed to avoid.
    if (!data || !map || !styleEpoch || restored.current) return;

    const properties = data.properties;
    const geometry = data.geometry as Parameters<typeof geomCenter>[0];
    // `hasCoordinates` and not just a finite check on the centroid: `geomCenter`
    // answers `[0, 0]` for a geometry it could not read, which is finite, so the
    // vanilla guard flew to null island for a POI whose geometry failed to load.
    const [lng, lat, bbox] = geomCenter(geometry);
    if (!hasCoordinates(geometry) || !Number.isFinite(lng) || !Number.isFinite(lat)) return;
    restored.current = true;

    // A shared campground filters by agency rather than by layer, so un-hiding the
    // overlay alone would still leave it filtered out of the source.
    const overlay = overlayForCategory(properties.category);
    if (overlay) setOverlayHidden(overlay.key, false);
    if (properties.category === 'campground') setAgencyHidden(featureAgency(data), false);

    const isArea = AREA_CATEGORIES.includes(String(properties.category));
    map.flyTo({
      center: [lng, lat],
      zoom: isArea ? zoomForBbox(bbox) : SHARED_POINT_ZOOM,
      speed: SHARED_FLY_SPEED,
    });
  }, [data, map, styleEpoch, setOverlayHidden, setAgencyHidden]);
}
