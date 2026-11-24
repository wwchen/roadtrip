import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { StoryApi, answer, isolatedDocs, storyPending, type StoryRoute } from '@/test/story-api';
import { RunsTab } from './RunsTab';
import { STORY_RUNS, storyTabRoute } from './story-fixtures';
import type { TabParams } from './useTabRoute';
import './dashboard.css';

const DOCS_FRAME_HEIGHT_PX = 480;
const HTTP_INTERNAL_ERROR = 500;
const [FIRST_RUN] = STORY_RUNS;

function Tab({ runs, params }: { runs: StoryRoute; params?: TabParams }) {
  const [table] = useState(() => ({ '/api/availability/runs': runs }));
  return (
    <StoryApi routes={table}>
      <RunsTab route={storyTabRoute('runs', params)} />
    </StoryApi>
  );
}

const meta = {
  title: 'Availability dashboard/RunsTab',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'Recent poller executions, filterable by status and poller. A poller row’s link lands ' +
          'here already scoped to that poller.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Runs: Story = { render: () => <Tab runs={answer({ runs: STORY_RUNS })} /> };

export const ScopedToPoller: Story = {
  render: () => (
    <Tab
      params={{ poller_id: String(FIRST_RUN.poller_id) }}
      runs={answer({ runs: STORY_RUNS.filter((run) => run.poller_id === FIRST_RUN.poller_id) })}
    />
  ),
};

export const Loading: Story = { render: () => <Tab runs={storyPending} /> };

export const NoRuns: Story = { render: () => <Tab runs={answer({ runs: [] })} /> };

export const Failed: Story = {
  render: () => (
    <Tab runs={answer({ error: 'internal_error', detail: 'runs query timed out' }, HTTP_INTERNAL_ERROR)} />
  ),
};
