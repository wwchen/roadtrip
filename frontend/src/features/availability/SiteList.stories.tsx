import type { Meta, StoryObj } from '@storybook/react-vite';
import { stayEndDate } from '@/lib/watch-windows';
import { SiteList, type SiteListProps } from './SiteList';
import { STORY_CAMPSITES, STORY_OPEN_DAY, STORY_RESERVATION_URL_TEMPLATES } from './story-fixtures';
import './availability.css';

const PANEL_WIDTH_PX = 480;

const BASE: SiteListProps = {
  state: 'success',
  campsites: STORY_CAMPSITES,
  reservationUrlTemplates: STORY_RESERVATION_URL_TEMPLATES,
  error: null,
  expanded: true,
  onToggle: () => {},
  onRetry: () => {},
  selectedDay: STORY_OPEN_DAY,
  selectedEndDate: stayEndDate(STORY_OPEN_DAY.date),
};

const meta = {
  title: 'Availability/SiteList',
  component: SiteList,
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
          'The "Available sites" accordion for a selected day with openings. Each row deep-links ' +
          'into the booking site for that night. It renders nothing for a day with no openings, ' +
          'which the day panel covers instead.',
      },
    },
  },
} satisfies Meta<typeof SiteList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Expanded: Story = {};

export const Collapsed: Story = { args: { expanded: false } };

export const WithoutBookingLinks: Story = { args: { reservationUrlTemplates: {} } };

export const SitesNotInCatalog: Story = { args: { campsites: [] } };

export const Loading: Story = { args: { state: 'loading', campsites: [] } };

export const Failed: Story = {
  args: { state: 'error', campsites: [], error: 'internal_error: campsite catalog query timed out · HTTP 500' },
};
