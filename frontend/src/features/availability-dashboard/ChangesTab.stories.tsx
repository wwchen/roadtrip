import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { StoryApi, answer, isolatedDocs, storyPending, type StoryRoute } from '@/test/story-api';
import { ChangesTab } from './ChangesTab';
import { STORY_CHANGES, STORY_CHANGES_SUMMARY, STORY_POI_ID, storyTabRoute } from './story-fixtures';
import type { TabParams } from './useTabRoute';
import './dashboard.css';

const DOCS_FRAME_HEIGHT_PX = 720;
const HTTP_INTERNAL_ERROR = 500;
const POI_PARAMS: TabParams = { poi_id: String(STORY_POI_ID) };

interface Overrides {
  changes?: StoryRoute;
  summary?: StoryRoute;
}

function Tab({ params = POI_PARAMS, overrides = {} }: { params?: TabParams; overrides?: Overrides }) {
  const [table] = useState(() => ({
    '/api/availability/changes': overrides.changes ?? answer({ changes: STORY_CHANGES }),
    '/api/availability/changes/summary': overrides.summary ?? answer(STORY_CHANGES_SUMMARY),
  }));
  return (
    <StoryApi routes={table}>
      <ChangesTab route={storyTabRoute('changes', params)} />
    </StoryApi>
  );
}

const meta = {
  title: 'Availability dashboard/ChangesTab',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'Status changes for one campground or one campsite: a per-date summary of how often it ' +
          'was polled and how long openings lasted, the change chart, and the change log. Nothing ' +
          'is requested until a target is set.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Campground: Story = { render: () => <Tab /> };

export const NoTarget: Story = { render: () => <Tab params={{}} /> };

export const Loading: Story = {
  render: () => <Tab overrides={{ changes: storyPending, summary: storyPending }} />,
};

export const NoChanges: Story = {
  render: () => <Tab overrides={{ changes: answer({ changes: [] }), summary: answer({ poi_id: STORY_POI_ID, stats: [] }) }} />,
};

export const Failed: Story = {
  render: () => (
    <Tab
      overrides={{
        changes: answer({ error: 'internal_error', detail: 'changes query timed out' }, HTTP_INTERNAL_ERROR),
      }}
    />
  ),
};
