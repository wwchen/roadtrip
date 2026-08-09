import '@ui/styles.css';
import { mountPage } from '@/app/mount';
import { MapProvider } from '@/features/map/MapProvider';
import { MapView } from '@/features/map/MapView';

// The map app: the shell and basemap lifecycle, the viewport POI loop, the pin
// overlays and legend, the POI drawer with its availability grid, and the
// topbar/trip planner. `MapProvider` owns the MapLibre instance; `MapView`
// composes everything React drives it with.
//
// This is what `/` serves. `npm run dev` (:5173) is the fastest way to see a
// change, with the backend up for /api and /data.
mountPage(
  <MapProvider>
    <MapView />
  </MapProvider>,
);
