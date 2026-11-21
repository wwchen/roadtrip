import type { Meta, StoryObj } from '@storybook/react-vite';
import { SearchDropdown } from './SearchDropdown';
import type { SearchResult } from './search-results';
import './topbar.css';

const PANEL_WIDTH_PX = 420;

const RESULTS: SearchResult[] = [
  { kind: 'CG', name: 'Bowman Bay', sub: 'WA', lng: -122.65, lat: 48.41, source: 'poi', poiId: 7, category: 'campground' },
  { kind: 'SC', name: 'Burlington Supercharger', sub: 'WA', lng: -122.33, lat: 48.47, source: 'poi', poiId: 31, category: 'charger' },
  { kind: 'PF', name: 'Planet Fitness Bellingham', sub: 'WA', lng: -122.47, lat: 48.75, source: 'poi', poiId: 52, category: 'gym' },
  { kind: 'PLACE', name: 'Bow, Washington', sub: '', lng: -122.42, lat: 48.56, source: 'geocode' },
  { kind: 'ADDR', name: '1600 Bowman Bay Rd, Anacortes', sub: '', lng: -122.65, lat: 48.41, source: 'geocode' },
  { kind: 'REGION', name: 'British Columbia, Canada', sub: '', lng: -125.0, lat: 54.0, source: 'geocode' },
];

const meta = {
  title: 'Trip/SearchDropdown',
  component: SearchDropdown,
  args: { results: RESULTS, activeIndex: -1, onPick: () => {}, errors: [] },
  decorators: [
    (Story) => (
      <div className="tb-panel" style={{ position: 'relative', inset: 'auto', width: PANEL_WIDTH_PX }}>
        <Story />
      </div>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'The results under the search box: POI hits first, then geocoded places, each with ' +
          'its kind chip. A source that failed reports its own line above whatever the other ' +
          'one found.',
      },
    },
  },
} satisfies Meta<typeof SearchDropdown>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Results: Story = {};

export const KeyboardSelection: Story = { args: { activeIndex: 3 } };

export const PlacesOnly: Story = { args: { results: RESULTS.filter((result) => result.source === 'geocode') } };

export const OneSourceFailed: Story = {
  args: {
    results: RESULTS.filter((result) => result.source === 'poi'),
    errors: ['Place search failed: upstream_unreachable: geocoder timed out after 5s · HTTP 502'],
  },
};

export const EverythingFailed: Story = {
  args: {
    results: [],
    errors: [
      'Campground search failed: internal_error: search index unavailable · HTTP 500',
      'Place search failed: upstream_unreachable: geocoder timed out after 5s · HTTP 502',
    ],
  },
};
