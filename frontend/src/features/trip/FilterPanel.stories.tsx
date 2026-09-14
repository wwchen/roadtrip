import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';
import './topbar.css';

const PANEL_WIDTH = 420;

function Panel({ seed, open: initialOpen = true }: { seed?: () => void; open?: boolean }) {
  const [open, setOpen] = useState(initialOpen);
  useEffect(() => {
    useCampgroundFilterStore.getState().reset();
    seed?.();
    return () => useCampgroundFilterStore.getState().reset();
  }, [seed]);
  return (
    <div className="tb-panel" style={{ position: 'relative', inset: 'auto', width: PANEL_WIDTH }}>
      <FilterPanel open={open} onToggle={() => setOpen((o) => !o)} totalInBoundary={17} totalMatching={13} />
    </div>
  );
}

const meta = {
  title: 'Trip/FilterPanel',
  parameters: {
    docs: {
      description: {
        component:
          'The filter block under the search row: the pill, site type, group size, amenities, and the ' +
          'Check availability banner that is the only gated step.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Closed: Story = { render: () => <Panel open={false} /> };
export const Open: Story = { render: () => <Panel /> };
export const Filtered: Story = {
  render: () => (
    <Panel
      seed={() => {
        const s = useCampgroundFilterStore.getState();
        s.setSiteType('tent');
        s.setGroupSize(4);
        s.toggleAmenity('toilets');
      }}
    />
  ),
};
export const WithDates: Story = {
  render: () => (
    <Panel seed={() => useCampgroundFilterStore.getState().setDateWindow({ start: '2026-09-11', end: '2026-09-13' })} />
  ),
};
