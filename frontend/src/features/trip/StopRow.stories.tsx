import type { Meta, StoryObj } from '@storybook/react-vite';
import { LOCATING_NAME } from '@/domain/trip/current-location';
import type { StopSlot } from '@/domain/trip/stops';
import type { TripMode, TripStop } from '@/stores/tripStore';
import { StopRow } from './StopRow';
import './topbar.css';

const PANEL_WIDTH_PX = 420;

const SEATTLE: TripStop = { name: 'Seattle, WA', lng: -122.33, lat: 47.6, kind: 'PLACE' };
const BOWMAN_BAY: TripStop = { name: 'Bowman Bay', lng: -122.65, lat: 48.41, kind: 'CG' };
const PORT_ANGELES: TripStop = { name: 'Port Angeles, WA', lng: -123.43, lat: 48.12, kind: 'PLACE' };
const LOCATING: TripStop = { name: LOCATING_NAME, lng: 0, lat: 0, kind: 'PLACE', pending: true };

function Rows({ stops, mode }: { stops: readonly StopSlot[]; mode: TripMode }) {
  const count = Math.max(stops.length, 1);
  return (
    <div className="tb-panel" style={{ position: 'relative', inset: 'auto', width: PANEL_WIDTH_PX }}>
      <div className="tb-stops">
        {Array.from({ length: count }, (_, index) => {
          const stop = stops[index] ?? null;
          return (
            <StopRow
              key={index}
              index={index}
              count={count}
              stop={stop}
              mode={mode}
              value={stop?.name ?? ''}
              draggable={mode === 'directions' && count >= 2}
              autoFocus={false}
              onFocusHandled={() => {}}
              onChange={() => {}}
              onFocus={() => {}}
              onKeyDown={() => {}}
              onRemove={() => {}}
              onUseCurrentLocation={() => {}}
              onReorder={() => {}}
            />
          );
        })}
      </div>
    </div>
  );
}

const meta = {
  title: 'Trip/StopRow',
  parameters: {
    docs: {
      description: {
        component:
          'One row of the trip planner: the role marker, the search box, use-my-location, and ' +
          'a clear or remove button that appears only when it would do something. Directions ' +
          'rows drag to reorder.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const BrowseEmpty: Story = { render: () => <Rows stops={[]} mode="browse" /> };

export const BrowseFilled: Story = { render: () => <Rows stops={[BOWMAN_BAY]} mode="browse" /> };

export const DirectionsAwaitingDestination: Story = {
  render: () => <Rows stops={[SEATTLE, null]} mode="directions" />,
};

export const DirectionsWithStop: Story = {
  render: () => <Rows stops={[SEATTLE, BOWMAN_BAY, PORT_ANGELES]} mode="directions" />,
};

export const LocatingOrigin: Story = {
  render: () => <Rows stops={[LOCATING, BOWMAN_BAY]} mode="directions" />,
};
