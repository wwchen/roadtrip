import type { Meta, StoryObj } from '@storybook/react-vite';
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import { CalendarPopover } from './CalendarPopover';
import { STORY_WEEK_DATES, STORY_WEEK_START } from './story-fixtures';
import { WeekNav } from './WeekNav';
import './availability.css';

const FRAME_HEIGHT_PX = 420;
const HORIZON_DAYS = 45;
const NARROW_HORIZON_DAYS = 10;
const EARLIEST_LEAD_DAYS = 15;

const WEEK_START = parseLocalYmd(STORY_WEEK_START);
const EARLIEST = addLocalDays(WEEK_START, -EARLIEST_LEAD_DAYS);

function Picker({ maxDate }: { maxDate: Date | null }) {
  return (
    <div style={{ minHeight: FRAME_HEIGHT_PX }}>
      <WeekNav
        startIso={localYmd(WEEK_START)}
        endIso={STORY_WEEK_DATES[STORY_WEEK_DATES.length - 1]}
        showEarliest
        canGoBack
        canGoForward
        onPrev={() => {}}
        onNext={() => {}}
        onEarliest={() => {}}
        onPickDate={() => {}}
        calendar={
          <CalendarPopover
            viewMonth={WEEK_START}
            today={EARLIEST}
            selectedDate={WEEK_START}
            maxDate={maxDate}
            onPick={() => {}}
            onClose={() => {}}
          />
        }
      />
    </div>
  );
}

const meta = {
  title: 'Availability/CalendarPopover',
  parameters: {
    docs: {
      description: {
        component:
          'The month picker the week label opens, anchored under the week nav. Days before the ' +
          'earliest bookable date and past the booking horizon are disabled.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithHorizon: Story = {
  render: () => <Picker maxDate={addLocalDays(WEEK_START, HORIZON_DAYS)} />,
};

export const HorizonThisMonth: Story = {
  render: () => <Picker maxDate={addLocalDays(WEEK_START, NARROW_HORIZON_DAYS)} />,
};

export const NoStatedHorizon: Story = { render: () => <Picker maxDate={null} /> };
