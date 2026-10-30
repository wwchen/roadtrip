import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';

vi.mock('@/features/account/AuthRow', () => ({ AuthRow: () => <span>auth slot</span> }));
vi.mock('@/features/alerts/AlertsPanel', () => ({ AlertsPanel: () => <span>alerts slot</span> }));
vi.mock('@/features/map/MapProvider', () => ({
  MapProvider: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));
vi.mock('@/features/map/MapView', () => ({ MapView: () => <span>map view</span> }));
vi.mock('@/features/trip/TopBar', () => ({
  TopBar: ({ alerts }: { alerts?: ReactNode }) => <header>{alerts}</header>,
}));
vi.mock('@/features/place/PlaceSurface', () => ({
  MapPlaceSurface: () => <aside>place sheet</aside>,
}));

const { MapPage } = await import('./MapPage');

test('composes the map page features and the place sheet', () => {
  render(<MapPage />);

  expect(screen.getByText('alerts slot')).toBeInTheDocument();
  expect(screen.getByText('auth slot')).toBeInTheDocument();
  expect(screen.getByText('map view')).toBeInTheDocument();
  expect(screen.getByText('place sheet')).toBeInTheDocument();
});
