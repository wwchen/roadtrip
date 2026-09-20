import { useEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { CampgroundSummary } from '@/api/campground-api';
import { MapRuntimeContext, type MapContextValue } from '@/map/context';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { cardsFromSummaries, type InViewCard } from './campground-cards';
import { inViewCopy, listCopy, routeListCopy } from '@/lib/strings';
import type { ResultsList } from './results-list';
import { TripResults } from './TripResults';
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

const summary = (over: Partial<CampgroundSummary> & Pick<CampgroundSummary, 'id' | 'name'>): CampgroundSummary => ({
  campground_id: over.id,
  region: 'CA',
  agency: 'USDA Forest Service',
  lng: -120.05,
  lat: 38.93,
  availability_supported: true,
  amenities: [],
  site_counts: {},
  site_total: 0,
  ...over,
});

/** Three campgrounds as the details endpoint describes them, nearest the map centre first. */
const SUMMARIES: CampgroundSummary[] = [
  summary({
    id: 1,
    name: 'Fallen Leaf',
    site_counts: { tent: 132, rv: 74 },
    site_total: 206,
    max_people: 8,
    rating: { average: 4.7, count: 40 },
    amenities: [
      { key: 'toilets', label: 'Toilets', present: true },
      { key: 'showers', label: 'Showers', present: true },
    ],
  }),
  summary({
    id: 2,
    name: 'D. L. Bliss',
    agency: 'California State Parks',
    site_counts: { tent: 165 },
    site_total: 165,
    max_people: 8,
    rating: { average: 4.8, count: 22 },
  }),
  summary({
    id: 3,
    name: 'Zephyr Cove',
    region: 'NV',
    agency: 'Private',
    site_counts: { rv: 110, tent: 60 },
    site_total: 170,
    availability_supported: false,
  }),
];

const BY_ID = new Map(SUMMARIES.map((s) => [s.id, s]));
const IDS = SUMMARIES.map((s) => s.id);

/** Cards in the order the search would return them; no centre, so the wire order stands. */
const IN_VIEW: InViewCard[] = cardsFromSummaries(IDS, BY_ID, null);

const ALONG_ROUTE: TripCard[] = [
  { id: 11, name: 'Nevada Beach', sub: 'Standard campground', location: 'NV', agency: '', lng: -120.05, lat: 38.93, routeKm: 4.9, distKm: 0, checkable: true, rating: null, hydrated: true },
  { id: 12, name: 'Meeks Bay', sub: 'Standard campground', location: '', agency: '', lng: -120.05, lat: 38.93, routeKm: 18.2, distKm: 0, checkable: true, rating: null, hydrated: true },
  { id: 13, name: 'William Kent', sub: 'Standard campground', location: '', agency: '', lng: -120.05, lat: 38.93, routeKm: 26.5, distKm: 0, checkable: true, rating: null, hydrated: true },
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

/** Seeds the filter store so a story's facet rows render, and resets it on unmount. */
function FilteredPanel({ children }: { children: ReactNode }) {
  useEffect(() => {
    const { setSiteType, setGroupSize, toggleAmenity, reset } = useCampgroundFilterStore.getState();
    setSiteType('tent');
    setGroupSize(4);
    toggleAmenity('toilets');
    return () => reset();
  }, []);

  return <Panel>{children}</Panel>;
}

const meta = {
  title: 'Trip/TripResults',
  parameters: {
    docs: {
      description: {
        component:
          'The topbar’s campground list. With a route it lists the corridor in driving ' +
          'order under the corridor slider; without one it lists what is in view, from a ' +
          'boundary search over the campground catalog, with each card’s count line, rating, ' +
          'checkable flag, and a facet row for the active filters.',
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
        list={{ kind: 'ready', cards: ALONG_ROUTE, total: ALONG_ROUTE.length }}
        corridorMiles={5}
        onCorridorMilesChange={() => {}}
      />
    </Panel>
  ),
};

/** A routed trip whose corridor holds nothing: widen it, or move the route. */
export const AlongRouteEmpty: Story = {
  render: () => (
    <Panel>
      <TripResults
        variant="route"
        list={{ kind: 'empty', message: routeListCopy.none }}
        corridorMiles={5}
        onCorridorMilesChange={() => {}}
      />
    </Panel>
  ),
};

/**
 * Every state the viewport list can be in, as its owner hands them over. The
 * component renders three shapes — a sentence, the layer-off card, or cards —
 * and picks none of them itself.
 */
const inViewStory = (list: ResultsList<InViewCard>, wrap: (children: ReactNode) => ReactNode = (c) => <Panel>{c}</Panel>): Story => ({
  render: () => <>{wrap(<TripResults variant="viewport" list={list} />)}</>,
});

/** Below the campground zoom gate the server sends no pins, so the list says what to do. */
export const InViewZoomedOut = inViewStory({ kind: 'empty', message: inViewCopy.zoomIn });

/** Zoomed in over nothing. */
export const InViewEmpty = inViewStory({ kind: 'empty', message: inViewCopy.none });

/** The search has answered but the summaries have not landed yet. */
export const InViewLoading = inViewStory({ kind: 'empty', message: inViewCopy.loading });

/** The search itself failed: not the same answer as an empty view. */
export const InViewFailed = inViewStory({ kind: 'empty', message: inViewCopy.failed });

/** Every campground in view belongs to an agency the legend has switched off. */
export const InViewAllAgenciesHidden = inViewStory({
  kind: 'empty',
  message: listCopy.allAgenciesHidden,
});

/** The Campgrounds layer itself is off: the one empty state that carries its own switch. */
export const InViewLayerOff = inViewStory({ kind: 'layer-off', inView: 503 });

/** The settled list: agency line, count line, rating where the catalog has one, and the not-checkable tag. */
export const InView = inViewStory({ kind: 'ready', cards: IN_VIEW, total: IN_VIEW.length });

/** An agency switched off in the legend: the head reads "2 of 3" and the checkable count survives. */
export const InViewFiltered = inViewStory(
  { kind: 'ready', cards: IN_VIEW.slice(0, 2), total: 3 },
  (children) => <FilteredPanel>{children}</FilteredPanel>,
);

/** A dense view: only the nearest cards render, and the head still counts every match. */
export const InViewCapped = inViewStory({ kind: 'ready', cards: IN_VIEW, total: 60 });
