import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { addLocalDays, localToday, localYmd } from '@/lib/local-date';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';
import './topbar.css';

const PANEL_WIDTH = 420;

/** Days from today, so a seeded window is never one the date fields drop as past. */
const fromToday = (days: number) => localYmd(addLocalDays(localToday(), days));

function Panel({ seed, open: initialOpen = true }: { seed?: () => void; open?: boolean }) {
  const [open, setOpen] = useState(initialOpen);
  // The panel renders only once the store is seeded: the date fields read the
  // window once, at their mount.
  const [seeded, setSeeded] = useState(false);
  useEffect(() => {
    useCampgroundFilterStore.getState().reset();
    seed?.();
    setSeeded(true);
    return () => useCampgroundFilterStore.getState().reset();
  }, [seed]);
  if (!seeded) return null;
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
          'date window. Every control applies as it changes; the dates once both ends are picked.',
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
    <Panel seed={() => useCampgroundFilterStore.getState().setDateWindow({ start: fromToday(10), end: fromToday(12) })} />
  ),
};
