import type { Meta, StoryObj } from '@storybook/react-vite';
import type { AvailabilityDay } from '@/api/availability-api';
import { DayDetail, type DayDetailProps } from './DayDetail';
import { STORY_FULL_DAY } from './story-fixtures';
import './availability.css';

const PANEL_WIDTH_PX = 480;

const CLOSED_DAY: AvailabilityDay = {
  ...STORY_FULL_DAY,
  status: 'closed',
  watchable: false,
  cells: Object.fromEntries(
    Object.keys(STORY_FULL_DAY.cells).map((id) => [id, { status: 'closed', watchable: false }]),
  ),
};

const BASE: DayDetailProps = {
  day: STORY_FULL_DAY,
  watching: false,
  unavailable: null,
  busy: false,
  onToggleWatch: () => {},
  onRetryWatches: () => {},
  onSignIn: () => {},
};

const meta = {
  title: 'Availability/DayDetail',
  component: DayDetail,
  args: BASE,
  decorators: [
    (Story) => (
      <div style={{ maxWidth: PANEL_WIDTH_PX }}>
        <Story />
      </div>
    ),
  ],
  parameters: {
    docs: {
      description: {
        component:
          'The panel under the grid for a selected day with nothing open: a watch toggle when ' +
          'one can be set, and otherwise the one sentence that says why not.',
      },
    },
  },
} satisfies Meta<typeof DayDetail>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CanWatch: Story = {};

export const Watching: Story = { args: { watching: true } };

export const Saving: Story = { args: { busy: true } };

export const SignedOut: Story = { args: { unavailable: 'signed-out' } };

export const CheckingAlerts: Story = { args: { unavailable: 'loading' } };

export const AlertCheckFailed: Story = { args: { unavailable: 'failed' } };

export const AlertsUnsupported: Story = { args: { unavailable: 'unsupported' } };

export const NothingToWatch: Story = { args: { day: CLOSED_DAY } };
