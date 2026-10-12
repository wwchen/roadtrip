import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { MonthCalendar } from '@ui';

const meta = {
  title: 'Ui/MonthCalendar',
  parameters: {
    docs: {
      description: {
        component:
          'A month of days with month paging. Dates in and out are `YYYY-MM-DD`; the ' +
          'parent owns the selection, so the same grid serves a single-day pick (the ' +
          'availability week jump) and a range (the trip panel’s date window). It sizes ' +
          'itself but draws no surface — its caller supplies the box. The grid is one Tab ' +
          'stop: arrows move by day and week, Home/End to the week’s ends, PageUp/PageDown ' +
          'by month and, with Shift, by year.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** One picked day, as the week-jump popover uses it. */
export const SingleDay: Story = {
  render: () => {
    const [picked, setPicked] = useState<string | null>('2026-09-14');
    return <MonthCalendar initialMonth="2026-09-14" today="2026-09-08" min="2026-09-08" selectedStart={picked} onPick={setPicked} />;
  },
};

/** A picked range: both ends selected, the nights between tinted. */
export const Range: Story = {
  render: () => (
    <MonthCalendar
      initialMonth="2026-09-11"
      today="2026-09-08"
      min="2026-09-08"
      selectedStart="2026-09-11"
      selectedEnd="2026-09-15"
      onPick={() => {}}
    />
  ),
};

/** A booking horizon: days past `max` are disabled. */
export const WithMax: Story = {
  render: () => <MonthCalendar initialMonth="2026-09-14" today="2026-09-08" min="2026-09-08" max="2026-09-24" onPick={() => {}} />,
};
