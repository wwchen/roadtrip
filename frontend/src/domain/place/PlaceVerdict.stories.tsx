import type { CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { StayAnswer } from '@/api/availability-api';
import { PlaceVerdict } from './PlaceVerdict';
import { summary } from './stay-fixtures';

const PHONE_FRAME: CSSProperties = { width: 390, padding: 16, boxSizing: 'border-box' };
const DESKTOP_FRAME: CSSProperties = { width: 720, padding: 24, boxSizing: 'border-box' };
const STALE_AGE_MS = 3 * 60 * 60 * 1000;
const NAME = 'Upper Pines';
const ORIGIN = { label: 'here', minutes: 250 };

const meta = {
  title: 'Place/PlaceVerdict',
  component: PlaceVerdict,
  args: { name: NAME, origin: ORIGIN, answer: summary() },
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
          'The answer to "can I camp here", rendered the same way on a map card and in the ' +
          'sheet. Every line is the server\'s decision phrased by `stayCopy`: a whole-stay ' +
          'claim comes only from `open_sites`, the count of sites bookable every night, and ' +
          'a status this build has never heard of reads as an error rather than as a blank.',
      },
    },
  },
} satisfies Meta<typeof PlaceVerdict>;

export default meta;
type Story = StoryObj<typeof meta>;

const outcome = (over: Omit<StayAnswer, 'poi_id'>): StayAnswer => ({ poi_id: 1, ...over });

export const Open: Story = { args: { answer: summary({ status: 'open', open_sites: 3 }) } };
export const FewLeft: Story = { args: { answer: summary({ status: 'few_left', open_sites: 1 }) } };
export const Full: Story = { args: { answer: summary({ status: 'full', open_sites: 0 }) } };
export const ClosedForSeason: Story = { args: { answer: summary({ status: 'closed_for_season', open_sites: 0 }) } };
export const ClosedWithReopeningDate: Story = {
  args: { answer: summary({ status: 'closed_for_season', open_sites: 0 }), reopensOn: '2027-05-14' },
};
export const NoData: Story = { args: { answer: summary({ status: 'no_data', open_sites: 0 }) } };
export const Refused: Story = {
  args: { answer: outcome({ outcome: 'refused', reason: 'beyond_booking_horizon' }) },
};
export const TimedOut: Story = { args: { answer: outcome({ outcome: 'timed_out' }) } };
export const Failed: Story = { args: { answer: outcome({ outcome: 'failed', reason: 'upstream_5xx' }) } };
export const Stale: Story = {
  args: {
    answer: summary({ from_cache: true, observed_at: new Date(Date.now() - STALE_AGE_MS).toISOString() }),
  },
};
export const Pending: Story = { args: { answer: null } };
export const WithReasonsAndFacts: Story = {
  args: { reasons: ['Near water', 'Shade'], priceFrom: 'From $28', fits: 'Fits 8 people' },
};
export const NoDriveTime: Story = { args: { origin: null } };
export const Desktop: Story = { parameters: { desktop: true } };
