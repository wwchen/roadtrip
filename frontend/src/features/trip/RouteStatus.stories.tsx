import type { Meta, StoryObj } from '@storybook/react-vite';
import { GEOLOCATION_FAILED_MESSAGE } from '@/domain/trip/current-location';
import { RouteStatus } from './RouteStatus';
import type { RouteLegLine } from './route-summary';
import './topbar.css';

const PANEL_WIDTH_PX = 420;

const LEGS: RouteLegLine[] = [
  { from: 'Seattle', to: 'Bowman', distance: '134 km', duration: '1 h 42 min' },
  { from: 'Bowman', to: 'Port', distance: '96 km', duration: '2 h 5 min' },
];

const meta = {
  title: 'Trip/RouteStatus',
  component: RouteStatus,
  args: { computing: false, error: null, legs: [] },
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
          'The status line under the topbar actions: route progress, the one error the topbar ' +
          'chose to show, or a line per leg once a trip has three or more stops. It renders ' +
          'nothing when there is nothing to say.',
      },
    },
  },
} satisfies Meta<typeof RouteStatus>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Computing: Story = { args: { computing: true } };

export const Legs: Story = { args: { legs: LEGS } };

export const RoutingFailed: Story = {
  args: { error: 'no_route: no drivable route between these stops · HTTP 422', legs: LEGS },
};

export const LocationFailed: Story = {
  args: { error: GEOLOCATION_FAILED_MESSAGE },
};
