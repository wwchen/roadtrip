import { AuthRow } from '@/features/account/AuthRow';
import { AlertsPanel } from '@/features/alerts/AlertsPanel';
import { AvailabilityWeek } from '@/features/availability/AvailabilityWeek';
import { PoiDrawer } from '@/features/drawer/PoiDrawer';
import { MapProvider } from '@/features/map/MapProvider';
import { MapView } from '@/features/map/MapView';
import { TopBar } from '@/features/trip/TopBar';

/** The page-owned composition boundary between otherwise independent map features. */
export function MapPage() {
  return (
    <MapProvider>
      <TopBar alerts={<AlertsPanel />} auth={<AuthRow />} />
      <MapView />
      <PoiDrawer
        renderCampgroundAvailability={(feature) => <AvailabilityWeek feature={feature} />}
      />
    </MapProvider>
  );
}
