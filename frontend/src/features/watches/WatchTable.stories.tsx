import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { queryKeys } from '@/queries/keys';
import type { Watch, WatchResponse } from '@/api/watches-api';
import { WatchTable } from './WatchTable';
import { ManageWatchCard } from './ManageWatchCard';
import type { MagicLink } from './magicLink';
import './watches.css';

function watch(over: Partial<Watch> & Pick<Watch, 'id' | 'status'>): Watch {
  return {
    targets: [],
    campsite_filters: {},
    start_date: '2026-08-14',
    end_date: '2026-08-16',
    trigger_kinds: ['slack_notify'],
    trigger_config: {},
    stop_when_triggered: true,
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...over,
  };
}

const WATCHES: Watch[] = [
  watch({
    id: 501,
    poi_id: 42,
    status: 'active',
    start_date: '2026-09-20',
    end_date: '2026-09-22',
    trigger_kinds: ['slack_notify', 'atc'],
    last_run_at: '2026-09-13T09:00:00Z',
    last_run_status: 'ok',
  }),
  watch({
    id: 502,
    poi_id: 77,
    status: 'paused',
    start_date: '2026-10-01',
    end_date: '2026-10-03',
    trigger_kinds: ['email_notify'],
  }),
  watch({
    id: 503,
    poi_id: 42,
    status: 'done',
    done_reason: 'triggered',
    start_date: '2026-08-14',
    end_date: '2026-08-16',
    trigger_kinds: ['slack_notify'],
    last_run_at: '2026-08-15T04:00:00Z',
    last_run_status: 'ok',
  }),
  watch({
    id: 504,
    status: 'active',
    start_date: '2026-09-25',
    end_date: '2026-09-26',
    trigger_kinds: [],
    last_run_status: 'failed',
    last_run_error: 'Upstream timed out',
  }),
];

const POI_NAMES = new Map<number, string>([
  [42, 'Bowman Bay'],
  [77, 'Fallen Leaf'],
]);

const meta = {
  title: 'Watches/WatchTable',
  parameters: {
    docs: {
      description: {
        component:
          'The watches page table: every watch, sortable, with pause/resume, edit and a ' +
          'double-confirm delete per row. `ManageWatchCard` below is the single-watch ' +
          'screen a magic-link email lands on instead.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Active, paused, done and a failed last check, across two campgrounds. */
export const Rows: Story = {
  render: () => (
    <WatchTable
      watches={WATCHES}
      poiNames={POI_NAMES}
      onEdit={() => {}}
      onSetStatus={() => {}}
      onDelete={() => {}}
      onNewWatch={() => {}}
    />
  ),
};

/** A mutation in flight: every row's action buttons are disabled. */
export const Busy: Story = {
  render: () => (
    <WatchTable
      watches={WATCHES}
      poiNames={POI_NAMES}
      onEdit={() => {}}
      onSetStatus={() => {}}
      onDelete={() => {}}
      onNewWatch={() => {}}
      busy
    />
  ),
};

/** No watches yet. */
export const NoWatches: Story = {
  render: () => (
    <WatchTable
      watches={[]}
      poiNames={new Map()}
      onEdit={() => {}}
      onSetStatus={() => {}}
      onDelete={() => {}}
      onNewWatch={() => {}}
    />
  ),
};

const MANAGED_WATCH: Watch = watch({
  id: 601,
  poi_id: 42,
  status: 'active',
  start_date: '2026-09-20',
  end_date: '2026-09-22',
  trigger_kinds: ['slack_notify'],
});

const LINK: MagicLink = { watchId: '601', token: 'story-token', stopOnArrival: false };

/** `ManageWatchCard` reads its watch through `useManagedWatch`, keyed on the magic
 *  link's id — not through props — so this seeds the query cache instead. */
function seededManageClient(watch: Watch | null): QueryClient {
  const client = createTestQueryClient();
  if (watch) {
    const response: WatchResponse = { watch };
    client.setQueryData(queryKeys.watches.detail(LINK.watchId), response);
    if (watch.poi_id != null) client.setQueryData(queryKeys.pois.name(watch.poi_id), 'Bowman Bay');
  }
  return client;
}

function ManageDemo({ watch: seeded }: { watch: Watch | null }) {
  const [client] = useState(() => seededManageClient(seeded));
  return (
    <QueryClientProvider client={client}>
      <div style={{ maxWidth: 480 }}>
        <ManageWatchCard link={LINK} />
      </div>
    </QueryClientProvider>
  );
}

/** The magic-link screen for a live watch: pause/resume and stop. */
export const ManageWatchCardActive: Story = {
  render: () => <ManageDemo watch={MANAGED_WATCH} />,
};

/** The same screen for a watch already paused. */
export const ManageWatchCardPaused: Story = {
  render: () => <ManageDemo watch={{ ...MANAGED_WATCH, status: 'paused' }} />,
};
