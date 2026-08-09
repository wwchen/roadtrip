import '@ui/styles.css';
import { mountPage } from '@/app/mount';
import { MapProvider } from '@/features/map/MapProvider';
import { MapView } from '@/features/map/MapView';

// The map app, mid-migration. The shell, the basemap lifecycle, the viewport POI
// loop, the pin overlays and the legend are here; the drawer, the map-side
// availability UI and the topbar/trip planner are still Phase 4c-4e.
//
// Ktor does NOT serve this page yet — `migratedPages` in StaticSiteRoutes.kt still
// resolves `/` from the legacy tree, so users keep the vanilla map until the phases
// above land. Reach it with `npm run dev` (:5173) against a running backend.
mountPage(
  <MapProvider>
    <MapView />
  </MapProvider>,
);
