import { AuthRow } from '@/features/account/AuthRow';
import { AlertsPanel } from '@/features/alerts/AlertsPanel';
import { MapProvider } from '@/features/map/MapProvider';
import { MapView } from '@/features/map/MapView';
import { MapPlaceSurface } from '@/features/place/PlaceSurface';
import { TopBar } from '@/features/trip/TopBar';

/** The page-owned composition boundary between otherwise independent map features. */
export function MapPage() {
  return (
    <MapProvider>
      <TopBar alerts={<AlertsPanel />} />
      <AuthRow />
      <MapView />
      <MapPlaceSurface />
    </MapProvider>
  );
}
