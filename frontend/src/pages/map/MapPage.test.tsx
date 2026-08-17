import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { FlatPoiFeature } from '@/lib/poi';

const CAMPGROUND_ID = 42;

vi.mock('@/features/account/AuthRow', () => ({ AuthRow: () => <span>auth slot</span> }));
vi.mock('@/features/alerts/AlertsPanel', () => ({ AlertsPanel: () => <span>alerts slot</span> }));
vi.mock('@/features/map/MapProvider', () => ({
  MapProvider: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));
vi.mock('@/features/map/MapView', () => ({ MapView: () => <span>map view</span> }));
vi.mock('@/features/trip/TopBar', () => ({
  TopBar: ({ alerts }: { alerts?: ReactNode }) => <header>{alerts}</header>,
}));
vi.mock('@/features/availability/AvailabilityWeek', () => ({
  AvailabilityWeek: ({ feature }: { feature: FlatPoiFeature }) => (
    <span>availability for {String(feature.id)}</span>
  ),
}));
vi.mock('@/features/drawer/PoiDrawer', () => ({
  PoiDrawer: ({
    renderCampgroundAvailability,
  }: {
    renderCampgroundAvailability?: (feature: FlatPoiFeature) => ReactNode;
  }) => (
    <aside>
      drawer
      {renderCampgroundAvailability?.({
        type: 'Feature',
        id: CAMPGROUND_ID,
        geometry: { type: 'Point', coordinates: [0, 0] },
        properties: { category: 'campground' },
      })}
    </aside>
  ),
}));

const { MapPage } = await import('./MapPage');

test('composes the map page features and campground availability', () => {
  render(<MapPage />);

  expect(screen.getByText('alerts slot')).toBeInTheDocument();
  expect(screen.getByText('auth slot')).toBeInTheDocument();
  expect(screen.getByText('map view')).toBeInTheDocument();
  expect(screen.getByText('drawer')).toBeInTheDocument();
  expect(screen.getByText(`availability for ${CAMPGROUND_ID}`)).toBeInTheDocument();
});
