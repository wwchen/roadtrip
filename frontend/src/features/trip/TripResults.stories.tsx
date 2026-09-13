import { useEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MapRuntimeContext, type MapContextValue } from '@/map/context';
import { useMapStore } from '@/stores/mapStore';
import { TripResults } from './TripResults';
import type { SiteCountsById } from './useSiteCounts';
import type { TripCard } from './trip-cards';
import './topbar.css';

/** The panel is fixed to the map's corner in the app; the catalog pins it in flow at its real width. */
const PANEL_WIDTH = 420;

/**
 * The list reads the map only to fly to a card, so a null map is enough — the
 * click becomes a store write and nothing else.
 */
const NO_MAP: MapContextValue = {
  map: null,
  styleEpoch: 1,
  basemapKey: 'streets',
  setBasemap: () => {},
  satellite: false,
  setSatellite: () => {},
};

const NO_SITE_COUNTS: SiteCountsById = new Map();

const card = (over: Partial<TripCard> & Pick<TripCard, 'id' | 'name'>): TripCard => ({
  sub: '',
  location: 'CA',
  agency: 'USDA Forest Service',
  lng: -120.05,
  lat: 38.93,
  routeKm: null,
  distKm: 0,
  checkable: true,
  rating: null,
  hydrated: true,
  ...over,
});

/** Three campgrounds as the detail endpoint describes them, nearest the map centre first. */
const IN_VIEW: TripCard[] = [
  card({ id: 1, name: 'Fallen Leaf', rating: 4.7, distKm: 5.4 }),
  card({ id: 2, name: 'D. L. Bliss', agency: 'California State Parks', rating: 4.8, distKm: 13.7 }),
  card({ id: 3, name: 'Zephyr Cove', location: 'NV', agency: 'Private', checkable: false, distKm: 2.7 }),
];

const SITE_COUNTS: SiteCountsById = new Map([
  ['1', { total: 206 }],
  ['2', { total: 165 }],
  ['3', { total: 170 }],
]);

const ALONG_ROUTE: TripCard[] = [
  card({ id: 11, name: 'Nevada Beach', sub: 'Standard campground', location: 'NV', routeKm: 4.9 }),
  card({ id: 12, name: 'Meeks Bay', sub: 'Standard campground', routeKm: 18.2 }),
  card({ id: 13, name: 'William Kent', sub: 'Standard campground', routeKm: 26.5 }),
];

function Panel({
  hiddenAgencies = [],
  children,
}: {
  hiddenAgencies?: string[];
  children: ReactNode;
}) {
  // The legend's filter is app-wide store state, so a story sets it the way the legend would.
  useEffect(() => {
    useMapStore.setState({ hiddenAgencies, hiddenOverlays: [] });
    return () => useMapStore.setState({ hiddenAgencies: [], hiddenOverlays: [] });
  }, [hiddenAgencies]);

  return (
    <MapRuntimeContext.Provider value={NO_MAP}>
      <div className="tb-panel" style={{ position: 'relative', inset: 'auto', width: PANEL_WIDTH }}>
        {children}
      </div>
    </MapRuntimeContext.Provider>
  );
}

const meta = {
  title: 'Trip/TripResults',
  parameters: {
    docs: {
      description: {
        component:
          'The topbar’s campground list. With a route it lists the corridor in driving ' +
          'order under the corridor slider; without one it lists what is in view, nearest ' +
          'the map centre first, with each card’s site count and whether its availability ' +
          'can be checked online. The head counts the whole view even when the rendered ' +
          'list is capped, and the checkable count appears only once every visible card ' +
          'has hydrated.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** The pre-existing list: driving order, distance along the route, the corridor slider. */
export const AlongRoute: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="route"
        cards={ALONG_ROUTE}
        loading={false}
        corridorMiles={5}
        onCorridorMilesChange={() => {}}
      />
    </Panel>
  ),
};

/** Below the campground zoom gate the server sends no pins, so the list says what to do. */
export const InViewZoomedOut: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="viewport"
        cards={[]}
        siteCounts={NO_SITE_COUNTS}
        campgroundsRequested={false}
        totalInView={0}
      />
    </Panel>
  ),
};

/** Zoomed in over nothing. */
export const InViewEmpty: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="viewport"
        cards={[]}
        siteCounts={NO_SITE_COUNTS}
        campgroundsRequested
        totalInView={0}
      />
    </Panel>
  ),
};

/** Pins have landed, details have not: placeholder names, no counts, no checkable tally yet. */
export const InViewHydrating: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="viewport"
        cards={IN_VIEW.map((c) => ({ ...c, name: 'Campground', location: '', hydrated: false }))}
        siteCounts={NO_SITE_COUNTS}
        campgroundsRequested
        totalInView={3}
      />
    </Panel>
  ),
};

/** The settled list: agency line, site count, rating where the catalog has one, and the not-checkable tag. */
export const InView: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="viewport"
        cards={IN_VIEW}
        siteCounts={SITE_COUNTS}
        campgroundsRequested
        totalInView={3}
      />
    </Panel>
  ),
};

/** An agency switched off in the legend: the head reads "2 of 3" and the checkable count survives. */
export const InViewFiltered: Story = {
  render: () => (
    <Panel hiddenAgencies={['California State Parks']}>
      <TripResults
        variant="viewport"
        cards={IN_VIEW}
        siteCounts={SITE_COUNTS}
        campgroundsRequested
        totalInView={3}
      />
    </Panel>
  ),
};

/** A dense view: only the nearest cards render, and the head still counts the whole viewport. */
export const InViewCapped: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="viewport"
        cards={IN_VIEW}
        siteCounts={SITE_COUNTS}
        campgroundsRequested
        totalInView={128}
      />
    </Panel>
  ),
};
