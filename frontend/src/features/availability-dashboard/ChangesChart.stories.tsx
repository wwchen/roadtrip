import type { Meta, StoryObj } from '@storybook/react-vite';
import { ChangesChart } from './ChangesChart';
import { STORY_CHANGES } from './story-fixtures';
import './dashboard.css';

const meta = {
  title: 'Availability dashboard/ChangesChart',
  component: ChangesChart,
  args: { changes: STORY_CHANGES },
  parameters: {
    docs: {
      description: {
        component:
          'One line per campsite and night, stepping between statuses at each observed change. ' +
          'Drawn with chart.js on a canvas.',
      },
    },
  },
} satisfies Meta<typeof ChangesChart>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Changes: Story = {};

export const OneCampsite: Story = {
  args: { changes: STORY_CHANGES.filter((change) => change.campsite_id === STORY_CHANGES[0].campsite_id) },
};
