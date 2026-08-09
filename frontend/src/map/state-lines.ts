// State and provincial boundary lines.
//
// Port of `installStateLines` from web/layers.js. The one overlay that is not
// POIs and not bbox-driven: a small static file, fetched once, reinstalled on
// every style load like everything else.
//
// It lives beside the pin overlays rather than in the registry because it shares
// none of their shape — no hit layer, no legend row, no filter, no per-pan data.
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';
import { token } from '@tokens';

/** Served out of the retained legacy static tree; see StaticSiteRoutes.kt. */
export const STATE_LINES_URL = '/data/us-states.geojson';

export const STATE_LINES_SOURCE_ID = 'states';
export const STATE_LINES_LAYER_ID = 'state-lines';

export function installStateLines(map: MapLibreMap, states: FeatureCollection): void {
  if (map.getLayer(STATE_LINES_LAYER_ID)) map.removeLayer(STATE_LINES_LAYER_ID);
  if (map.getSource(STATE_LINES_SOURCE_ID)) map.removeSource(STATE_LINES_SOURCE_ID);

  map.addSource(STATE_LINES_SOURCE_ID, { type: 'geojson', data: states });
  map.addLayer({
    id: STATE_LINES_LAYER_ID,
    type: 'line',
    source: STATE_LINES_SOURCE_ID,
    paint: {
      'line-color': token('--rt-map-route-alt'),
      'line-width': ['interpolate', ['linear'], ['zoom'], 3, 0.6, 6, 1.0, 10, 1.4],
      'line-opacity': 0.55,
    },
  });
}
