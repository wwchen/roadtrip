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
          'The date window\'s two fields. The calendar shows only while a field is ' +
          'selected; each pick fills that end and moves on to the one still missing, and ' +
          'the window is reported as soon as both are set.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
  render: () => <DateWindowFields initial={null} onChange={() => {}} />,
};

/** Days from today, so the window is never in the calendar's disabled past. */
const fromToday = (days: number) => localYmd(addLocalDays(localToday(), days));

export const Prefilled: Story = {
  render: () => (
    <DateWindowFields
      initial={{ start: fromToday(10), end: fromToday(12) }}
      onChange={() => {}}
    />
  ),
};
