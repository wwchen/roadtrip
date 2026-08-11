// Static boundaries are independent of the bbox-driven POI overlay registry.
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';
import { token } from '@tokens';

/** Served from the repository data mount; see StaticSiteRoutes.kt. */
export const STATE_LINES_URL = '/data/us-states.geojson';

export const STATE_LINES_SOURCE_ID = 'states';
export const STATE_LINES_LAYER_ID = 'state-lines';

/**
 * Install the boundaries, beneath `below` when it is given.
 *
 * `below` keeps late-arriving boundaries from drawing over pins.
 */
export function installStateLines(
  map: MapLibreMap,
  states: FeatureCollection,
  below?: string,
): void {
  if (map.getLayer(STATE_LINES_LAYER_ID)) map.removeLayer(STATE_LINES_LAYER_ID);
  if (map.getSource(STATE_LINES_SOURCE_ID)) map.removeSource(STATE_LINES_SOURCE_ID);

  map.addSource(STATE_LINES_SOURCE_ID, { type: 'geojson', data: states });
  map.addLayer(
    {
      id: STATE_LINES_LAYER_ID,
      type: 'line',
      source: STATE_LINES_SOURCE_ID,
      paint: {
        'line-color': token('--rt-map-route-alt'),
        'line-width': ['interpolate', ['linear'], ['zoom'], 3, 0.6, 6, 1.0, 10, 1.4],
        'line-opacity': 0.55,
      },
    },
    below,
  );
}
