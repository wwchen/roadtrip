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
          'ends: the first click is the start, the next later click is the end, and a ' +
          'click on or before the start begins again.',
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
