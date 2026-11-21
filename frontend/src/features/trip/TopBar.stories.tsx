import { useEffect, useState, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, fireEvent, within } from 'storybook/test';
import type { CampgroundSummary } from '@/api/campground-api';
import type { StopSlot } from '@/domain/trip/stops';
import { setVisibleRouteParam } from '@/lib/share-links';
import { MapRuntimeContext, type MapContextValue } from '@/map/context';
import type { Viewport } from '@/stores/mapStore';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { useStayCheckStore } from '@/stores/stayCheckStore';
import { useTripStore, type TripMode, type TripStop } from '@/stores/tripStore';
import {
  StoryApi,
  answer,
  isolatedDocs,
  storyJson,
  storyPending,
  type StoryRoute,
  type StoryRoutes,
} from '@/test/story-api';
import { TopBar } from './TopBar';
import './topbar.css';

const FRAME_HEIGHT_PX = 680;
const DOCS_FRAME_HEIGHT_PX = 720;
const LEG_DISTANCE_M = 134_000;
const LEG_DURATION_S = 6_100;
const SEARCH_QUERY = 'Bow';

const HTTP_UNPROCESSABLE = 422;
const HTTP_INTERNAL_ERROR = 500;

const NO_MAP: MapContextValue = {
  map: null,
  styleEpoch: 1,
  basemapKey: 'streets',
  setBasemap: () => {},
  satellite: false,
  setSatellite: () => {},
};

const TAHOE_VIEWPORT: Viewport = { bbox: [-120.3, 38.8, -119.8, 39.2], zoom: 10 };

const SEATTLE: TripStop = { name: 'Seattle, WA', lng: -122.33, lat: 47.6, kind: 'PLACE' };
const BOWMAN_BAY: TripStop = { name: 'Bowman Bay', lng: -122.65, lat: 48.41, kind: 'CG' };
const PORT_ANGELES: TripStop = { name: 'Port Angeles, WA', lng: -123.43, lat: 48.12, kind: 'PLACE' };

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

const SUMMARIES: CampgroundSummary[] = [
  summary({ id: 21, name: 'Fallen Leaf', site_counts: { tent: 132, rv: 74 }, site_total: 206, rating: { average: 4.7, count: 40 } }),
  summary({ id: 22, name: 'D. L. Bliss', agency: 'California State Parks', lng: -120.1, lat: 38.98, site_counts: { tent: 165 }, site_total: 165 }),
  summary({ id: 23, name: 'Zephyr Cove', region: 'NV', agency: 'Private', lng: -119.95, lat: 39.0, site_counts: { rv: 110, tent: 60 }, site_total: 170, availability_supported: false }),
];

const SUMMARY_BY_ID = new Map(SUMMARIES.map((row) => [row.id, row]));

const CORRIDOR_POIS = [
  { id: 31, name: 'Deception Pass', state: 'WA', lng: -122.64, lat: 48.4, agency: 'Washington State Parks' },
  { id: 32, name: 'Larrabee', state: 'WA', lng: -122.49, lat: 48.65, agency: 'Washington State Parks' },
  { id: 33, name: 'Bay View', state: 'WA', lng: -122.47, lat: 48.48, agency: 'Washington State Parks' },
];

const routeLine = (stops: readonly TripStop[]) => ({
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: stops.map((stop) => [stop.lng, stop.lat]) },
      properties: {
        distance_m: LEG_DISTANCE_M * (stops.length - 1),
        duration_s: LEG_DURATION_S * (stops.length - 1),
        legs: stops.slice(1).map(() => ({ distance_m: LEG_DISTANCE_M, duration_s: LEG_DURATION_S })),
      },
    },
  ],
});

const corridorPins = {
  type: 'FeatureCollection',
  features: CORRIDOR_POIS.map((poi) => ({
    type: 'Feature',
    id: poi.id,
    geometry: { type: 'Point', coordinates: [poi.lng, poi.lat] },
    properties: { id: poi.id, category: 'campground', agency: poi.agency },
  })),
};

const corridorDetailRoutes: StoryRoutes = Object.fromEntries(
  CORRIDOR_POIS.map((poi) => [
    `/api/pois/${poi.id}`,
    answer({
      type: 'Feature',
      id: poi.id,
      geometry: { type: 'Point', coordinates: [poi.lng, poi.lat] },
      properties: {
        category: 'campground',
        name: poi.name,
        state: poi.state,
        agency: poi.agency,
        typeLabel: 'State park campground',
        availability_supported: true,
      },
    }),
  ]),
);

const campgroundDetails: StoryRoute = (_url, init) => {
  const { campground_ids: ids } = JSON.parse(String(init?.body)) as { campground_ids: number[] };
  return storyJson({ campgrounds: ids.flatMap((id) => SUMMARY_BY_ID.get(id) ?? []) });
};

interface Overrides {
  route?: StoryRoute;
  campgroundSearch?: StoryRoute;
}

function routes(stops: readonly TripStop[], { route, campgroundSearch }: Overrides): StoryRoutes {
  return {
    '/api/route': route ?? answer(routeLine(stops)),
    '/api/pois/on-route': answer(corridorPins),
    '/api/pois/search': answer({
      results: [{ id: 7, name: 'Bowman Bay', category: 'campground', region: 'WA', lng: -122.65, lat: 48.41 }],
    }),
    '/api/geocode': answer({
      results: [
        { id: 'g1', place_name: 'Bow, Washington', place_type: 'place', lng: -122.42, lat: 48.56 },
        { id: 'g2', place_name: 'Bowmanville, Ontario', place_type: 'place', lng: -78.69, lat: 43.91 },
      ],
    }),
    '/api/campgrounds/search': campgroundSearch ?? answer({
      campground_ids: SUMMARIES.map((row) => row.id),
      total_in_boundary: SUMMARIES.length,
      total_matching: SUMMARIES.length,
      truncated: false,
    }),
    '/api/campgrounds/details': campgroundDetails,
    ...corridorDetailRoutes,
  };
}

interface TripProps {
  stops?: readonly StopSlot[];
  mode?: TripMode;
  viewport?: Viewport | null;
  overrides?: Overrides;
}

function Seeded({ stops, mode, viewport, children }: Required<Omit<TripProps, 'overrides'>> & { children: ReactNode }) {
  const [seeded, setSeeded] = useState(false);
  useEffect(() => {
    useTripStore.setState({ stops: [...stops], mode });
    useMapStore.setState({
      viewport,
      campgroundsRequested: viewport !== null,
      hiddenAgencies: [],
      hiddenOverlays: [],
      viewportCampgrounds: [],
    });
    setSeeded(true);
    return () => {
      useTripStore.getState().reset();
      useCampgroundFilterStore.getState().reset();
      useStayCheckStore.getState().reset();
      useMapStore.setState({ viewport: null, campgroundsRequested: false, campgroundFilterIds: null });
      setVisibleRouteParam(null);
    };
  }, [stops, mode, viewport]);
  return seeded ? <>{children}</> : null;
}

function Trip({ stops = [], mode = 'browse', viewport = TAHOE_VIEWPORT, overrides = {} }: TripProps) {
  const [table] = useState(() => routes(stops.filter((stop): stop is TripStop => stop !== null), overrides));
  return (
    <StoryApi routes={table}>
      <Seeded stops={stops} mode={mode} viewport={viewport}>
        <MapRuntimeContext.Provider value={NO_MAP}>
          <div style={{ position: 'relative', minHeight: FRAME_HEIGHT_PX }}>
            <TopBar />
          </div>
        </MapRuntimeContext.Provider>
      </Seeded>
    </StoryApi>
  );
}

const meta = {
  title: 'Trip/TopBar',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'The trip planner panel over the map: the stop rows, the actions row, the search ' +
          'dropdown, the route status line, and the results list — what is in view while ' +
          'browsing, the campgrounds along the corridor once a trip is routed. Each story ' +
          'runs the real hooks against a stubbed API and seeded trip and map stores.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const BrowseInView: Story = { render: () => <Trip /> };

export const BrowseZoomedOut: Story = { render: () => <Trip viewport={null} /> };

export const BrowseSearchFailed: Story = {
  render: () => (
    <Trip
      overrides={{
        campgroundSearch: answer(
          { error: 'internal_error', detail: 'campground search timed out' },
          HTTP_INTERNAL_ERROR,
        ),
      }}
    />
  ),
};

export const Typing: Story = {
  render: () => <Trip />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByText(SUMMARIES[0].name);
    const search = await canvas.findByRole('textbox', { name: 'Search a place or pin…' });
    fireEvent.focus(search);
    fireEvent.change(search, { target: { value: SEARCH_QUERY } });
    await expect(await canvas.findByRole('listbox', { name: 'Search results' })).toBeVisible();
  },
};

export const DirectionsAwaitingDestination: Story = {
  render: () => <Trip stops={[SEATTLE, null]} mode="directions" />,
};

export const Routed: Story = {
  render: () => <Trip stops={[SEATTLE, BOWMAN_BAY]} mode="directions" />,
};

export const RoutedWithStop: Story = {
  render: () => <Trip stops={[SEATTLE, BOWMAN_BAY, PORT_ANGELES]} mode="directions" />,
};

export const ComputingRoute: Story = {
  render: () => <Trip stops={[SEATTLE, BOWMAN_BAY]} mode="directions" overrides={{ route: storyPending }} />,
};

export const RouteFailed: Story = {
  render: () => (
    <Trip
      stops={[SEATTLE, PORT_ANGELES]}
      mode="directions"
      overrides={{
        route: answer(
          { error: 'no_route', detail: 'no drivable route between these stops' },
          HTTP_UNPROCESSABLE,
        ),
      }}
    />
  ),
};
