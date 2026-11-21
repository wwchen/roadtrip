import { useEffect, useState, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { QueryClient } from '@tanstack/react-query';
import { flattenHydratedPoi, type PoiFeature } from '@/lib/poi';
import { clearPoiFromUrl } from '@/lib/poi-url';
import { queryKeys } from '@/queries/keys';
import { useMapStore } from '@/stores/mapStore';
import { StoryApi, answer, isolatedDocs, storyPending, type StoryRoute } from '@/test/story-api';
import { PoiDrawer } from './PoiDrawer';

const DOCS_FRAME_HEIGHT_PX = 720;
const POI_ID = 151;
const DETAIL_PATH = `/api/pois/${POI_ID}`;
const HTTP_INTERNAL_ERROR = 500;
const HTTP_BAD_GATEWAY = 502;
const LOADED_LONG_AGO_MS = 0;

const poi = (properties: Record<string, unknown>): PoiFeature => ({
  type: 'Feature',
  id: POI_ID,
  geometry: { type: 'Point', coordinates: [-122.81, 43.99] },
  properties,
});

const CAMPGROUND = poi({
  category: 'campground',
  name: 'Jasper State Recreation Site',
  agency: 'Oregon State Parks',
  state: 'Oregon',
  amenities: [
    { key: 'water', label: 'Water', present: true },
    { key: 'showers', label: 'No showers', present: false },
    { key: 'toilets', label: 'Flush toilets', present: true, detail: 'flush' },
  ],
  activities: ['Fishing', 'Hiking'],
  rating: { average: 4.3, count: 87 },
  price: { minimum: 24, maximum: 29, currency: 'USD' },
  schedule: { check_in: '14:00', check_out: '11:00' },
  phone: '+1 (541) 937-1173',
  description: '<p>Day-use park on the Middle Fork Willamette River.</p>',
});

const CHARGER = poi({
  category: 'tesla_supercharger',
  name: 'Sutherlin, OR',
  street: '116 Clover Leaf Loop',
  city: 'Sutherlin',
  state: 'OR',
  postcode: '97479',
  stall_count: 51,
  power_kilowatt: 250,
  twenty_four_seven: true,
  open_to_non_teslas: true,
});

const UNREGISTERED = poi({ category: 'ferry_terminal', name: 'Anacortes Ferry Terminal' });

function Selected({ children }: { children: ReactNode }) {
  const [selected, setSelected] = useState(false);
  useEffect(() => {
    useMapStore.setState({ selectedPoiId: POI_ID });
    setSelected(true);
    return () => {
      useMapStore.setState({ selectedPoiId: null });
      clearPoiFromUrl();
    };
  }, []);
  return selected ? <>{children}</> : null;
}

function Drawer({ detail, cached }: { detail: StoryRoute; cached?: PoiFeature }) {
  const [table] = useState(() => ({ [DETAIL_PATH]: detail }));
  const seed = (client: QueryClient) => {
    if (!cached) return;
    const flat = flattenHydratedPoi(structuredClone(cached));
    client.setQueryData(queryKeys.pois.detail(POI_ID), flat, { updatedAt: LOADED_LONG_AGO_MS });
  };
  return (
    <StoryApi routes={table} seed={seed}>
      <Selected>
        <PoiDrawer />
      </Selected>
    </StoryApi>
  );
}

const meta = {
  title: 'Drawer/PoiDrawer',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'The drawer for the selected pin: it hydrates the POI by id and hands it to the page ' +
          'the registry names for its category. A failed first load takes the panel over; a ' +
          'failed refresh keeps the page and says so above it.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Campground: Story = { render: () => <Drawer detail={answer(CAMPGROUND)} /> };

export const Charger: Story = { render: () => <Drawer detail={answer(CHARGER)} /> };

export const Loading: Story = { render: () => <Drawer detail={storyPending} /> };

export const LoadFailed: Story = {
  render: () => (
    <Drawer detail={answer({ error: 'internal_error', detail: 'poi detail query timed out' }, HTTP_INTERNAL_ERROR)} />
  ),
};

export const RefreshFailed: Story = {
  render: () => (
    <Drawer
      cached={CAMPGROUND}
      detail={answer({ error: 'upstream_unreachable', detail: 'ridb.recreation.gov timed out' }, HTTP_BAD_GATEWAY)}
    />
  ),
};

export const NoDetailView: Story = { render: () => <Drawer detail={answer(UNREGISTERED)} /> };
