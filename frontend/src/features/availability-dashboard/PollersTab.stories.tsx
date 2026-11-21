import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { StoryApi, answer, isolatedDocs, storyPending, type StoryRoute, type StoryRoutes } from '@/test/story-api';
import { PollersTab } from './PollersTab';
import { STORY_POLLERS, STORY_POLLERS_LIST, STORY_POLLERS_SUMMARY, storyTabRoute } from './story-fixtures';
import './dashboard.css';

const DOCS_FRAME_HEIGHT_PX = 560;
const HTTP_TOO_MANY_REQUESTS = 429;
const HTTP_INTERNAL_ERROR = 500;
const COOLDOWN_SECONDS = 42;
const [ACTIVE_POLLER, BUSY_POLLER] = STORY_POLLERS;

interface Overrides {
  list?: StoryRoute;
  summary?: StoryRoute;
}

function routes({ list, summary }: Overrides): StoryRoutes {
  return {
    '/api/availability/pollers': list ?? answer(STORY_POLLERS_LIST),
    '/api/availability/pollers/summary': summary ?? answer(STORY_POLLERS_SUMMARY),
    [`/api/availability/pollers/${ACTIVE_POLLER.id}/force`]: answer({
      poller_id: ACTIVE_POLLER.id,
      next_run_at: ACTIVE_POLLER.next_run_at,
    }),
    [`/api/availability/pollers/${BUSY_POLLER.id}/force`]: answer(
      { poller_id: BUSY_POLLER.id, retry_after_sec: COOLDOWN_SECONDS },
      HTTP_TOO_MANY_REQUESTS,
    ),
  };
}

function Tab({ overrides = {} }: { overrides?: Overrides }) {
  const [table] = useState(() => routes(overrides));
  return (
    <StoryApi routes={table}>
      <PollersTab route={storyTabRoute('pollers')} />
    </StoryApi>
  );
}

const meta = {
  title: 'Availability dashboard/PollersTab',
  parameters: {
    docs: {
      ...isolatedDocs(DOCS_FRAME_HEIGHT_PX),
      description: {
        component:
          'The ops dashboard’s poller list: the status counters, the active filter, and a row per ' +
          'poller with "check now". The first row queues a check; the second answers with a cooldown.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Pollers: Story = { render: () => <Tab /> };

export const Loading: Story = {
  render: () => <Tab overrides={{ list: storyPending, summary: storyPending }} />,
};

export const NoPollers: Story = {
  render: () => <Tab overrides={{ list: answer({ ...STORY_POLLERS_LIST, total: 0, pollers: [] }) }} />,
};

export const Failed: Story = {
  render: () => (
    <Tab
      overrides={{
        list: answer({ error: 'internal_error', detail: 'poller query timed out' }, HTTP_INTERNAL_ERROR),
        summary: answer({ error: 'internal_error', detail: 'poller query timed out' }, HTTP_INTERNAL_ERROR),
      }}
    />
  ),
};
