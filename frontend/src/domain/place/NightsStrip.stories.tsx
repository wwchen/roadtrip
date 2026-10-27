import { useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AvailabilityDay } from '@/api/availability-api';
import type { AvailabilityStatus } from '@/lib/availability-status';
import { NightSites } from './NightSites';
import { NightsStrip } from './NightsStrip';
import { dayFull, dayOpen } from './stay-fixtures';

const PHONE_FRAME: CSSProperties = { width: 390, padding: 16, boxSizing: 'border-box' };
const DESKTOP_FRAME: CSSProperties = { width: 720, padding: 24, boxSizing: 'border-box' };
const WEEK = ['2026-10-25', '2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31'];
const OPEN_SITES = [3, 5, 2, 4, 6, 1, 3];
const SHUT_NIGHTS = new Set(['2026-10-27', '2026-10-28']);
const GAP_NIGHTS = new Set(['2026-10-27', '2026-10-28']);

const STATED_NIGHTS = ['first_come', 'closed', 'past'] as const;

const dayStated = (date: string, status: AvailabilityStatus): AvailabilityDay => ({
  date,
  status,
  watchable: false,
  cells: { '1': { status, watchable: false } },
});

const openWeek = WEEK.map((date, index) => dayOpen(date, OPEN_SITES[index] ?? 1));
const statedWeek = WEEK.map((date, index) => {
  const stated = STATED_NIGHTS[index % STATED_NIGHTS.length];
  return index % 2 === 0 ? dayOpen(date, OPEN_SITES[index] ?? 1) : dayStated(date, stated);
});
const twoShutWeek = WEEK.map((date, index) =>
  SHUT_NIGHTS.has(date) ? dayFull(date) : dayOpen(date, OPEN_SITES[index] ?? 1),
);
const gapWeek = openWeek.filter((day) => !GAP_NIGHTS.has(day.date));

function Demo({ days }: { days: readonly AvailabilityDay[] }) {
  const [selected, setSelected] = useState<string | null>(null);
  const day = days.find((candidate) => candidate.date === selected) ?? null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <NightsStrip days={days} dates={WEEK} selected={selected} onSelect={setSelected} />
      <NightSites day={day} />
    </div>
  );
}

const meta = {
  title: 'Place/NightsStrip',
  component: Demo,
  args: { days: openWeek },
  decorators: [
    (Story, { parameters }) => (
      <div style={parameters.desktop ? DESKTOP_FRAME : PHONE_FRAME}>
        <Story />
      </div>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'Which nights, one button each. A night\'s state is its own day\'s rollup, read off ' +
          'the wire; its count and site ids come from that day\'s cells. A night with no day, ' +
          'or a day with no cells, is unknown whatever a rollup says, since there is nothing to ' +
          'count or name. ' +
          'An unknown night is drawn and disabled, so it cannot borrow its neighbour\'s answer. ' +
          'Picking a night names the sites open on that night alone, which is a per-night ' +
          'fact; a site list for the whole stay would be a stay claim, and only the stay ' +
          'check can make one.',
      },
    },
  },
} satisfies Meta<typeof Demo>;

export default meta;
type Story = StoryObj<typeof meta>;

export const FullWeekOpen: Story = {};
export const TwoShutNights: Story = { args: { days: twoShutWeek } };
export const GapInDays: Story = { args: { days: gapWeek } };
export const WeekOfUnknowns: Story = { args: { days: [] } };
export const StatedNights: Story = { args: { days: statedWeek } };
export const Desktop: Story = { parameters: { desktop: true } };
