import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { addLocalDays, localToday, localYmd } from '@/lib/local-date';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';
import './topbar.css';

const PANEL_WIDTH = 420;

const AGENCY_COUNTS = new Map([
  ['USDA Forest Service', 21],
  ['California State Parks', 14],
  ['National Park Service', 9],
  ['Bureau of Land Management', 6],
  ['County Parks', 4],
  ['US Army Corps of Engineers', 3],
  ['Private', 2],
  ['Uncategorized', 1],
]);

const WIDE_AGENCY_COUNTS = new Map([
  ['USDA Forest Service', 184],
  ['California State Parks', 96],
  ['National Park Service', 61],
  ['Bureau of Land Management', 47],
  ['Oregon State Parks', 38],
  ['County Parks', 33],
  ['US Army Corps of Engineers', 21],
  ['Washington State Parks', 19],
  ['Private', 17],
  ['Nevada State Parks', 12],
  ['Bureau of Reclamation', 9],
  ['Uncategorized', 2],
]);

/** Days from today, so a seeded window is never one the date fields drop as past. */
const fromToday = (days: number) => localYmd(addLocalDays(localToday(), days));

function Panel({
  seed,
  open: initialOpen = true,
  agencyCounts = AGENCY_COUNTS,
}: {
  seed?: () => void;
  open?: boolean;
  agencyCounts?: ReadonlyMap<string, number>;
}) {
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
      <FilterPanel
        open={open}
        onToggle={() => setOpen((o) => !o)}
        totalInBoundary={60}
        totalMatching={30}
        agencyCounts={agencyCounts}
        checkableIds={[]}
      />
    </div>
  );
}

const meta = {
  title: 'Trip/FilterPanel',
  parameters: {
    docs: {
      description: {
        component:
          'The filter block under the search row: the Filter pill with the count and Clear, the agency ' +
          'pick (show only these, ordered by campgrounds in view), site type, group size, amenities, and ' +
          'the date window. Every control applies as it changes; the dates once both ends are picked.',
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

export const AgencyPicked: Story = {
  render: () => (
    <Panel
      seed={() => {
        const s = useCampgroundFilterStore.getState();
        s.toggleAgency('National Park Service');
        s.toggleAgency('USDA Forest Service');
      }}
    />
  ),
};

/** More than ten agencies in view: the dropdown gets a search box. */
export const ManyAgencies: Story = { render: () => <Panel agencyCounts={WIDE_AGENCY_COUNTS} /> };

export const ClosedWithFilters: Story = {
  render: () => (
    <Panel
      open={false}
      seed={() => {
        const s = useCampgroundFilterStore.getState();
        s.toggleAgency('National Park Service');
        s.setSiteType('tent');
      }}
    />
  ),
};
