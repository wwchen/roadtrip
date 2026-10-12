import type { Meta, StoryObj } from '@storybook/react-vite';
import { addLocalDays, localToday, localYmd } from '@/lib/local-date';
import { DateWindowFields } from './DateWindowFields';
import './topbar.css';

const meta = {
  title: 'Trip/DateWindowFields',
  parameters: {
    docs: {
      description: {
        component:
          'The Check availability step\'s date entry. One `MonthCalendar` picks both ' +
          'ends, and the Start/End control above it says which end the next pick sets: ' +
          'picking walks start → end → start, and choosing End first changes only the end.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
  render: () => <DateWindowFields initial={null} onSave={() => {}} onCancel={() => {}} />,
};

/** Days from today, so the window is never in the calendar's disabled past. */
const fromToday = (days: number) => localYmd(addLocalDays(localToday(), days));

export const Prefilled: Story = {
  render: () => (
    <DateWindowFields
      initial={{ start: fromToday(10), end: fromToday(12) }}
      onSave={() => {}}
      onCancel={() => {}}
    />
  ),
};
