import { useState, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { queryKeys } from '@/queries/keys';
import type { Watch, WatchListResponse } from '@/api/watches-api';
import { AlertsPanel } from './AlertsPanel';
import { ALERT_STATUSES, WATCH_LIST_LIMIT } from './alert-rows';
import './alerts.css';

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
    trigger_kinds: ['slack_notify'],
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
    trigger_kinds: ['slack_notify', 'atc'],
    last_run_at: '2026-08-15T04:00:00Z',
    last_run_status: 'ok',
  }),
];

const POI_NAMES: Record<number, string> = { 42: 'Bowman Bay', 77: 'Fallen Leaf' };

function watchListResponse(watches: Watch[]): WatchListResponse {
  return { total: watches.length, limit: WATCH_LIST_LIMIT, offset: 0, watches };
}

/**
 * A `QueryClient` seeded before the first render: every list `useAlerts` fetches
 * (one per `ALERT_STATUSES`) and every POI name `useWatchPoiNames` would otherwise
 * fetch already have an answer in the cache, so the panel mounts straight into its
 * settled state with no fetch in flight.
 */
function seededClient(watches: readonly Watch[]): QueryClient {
  const client = createTestQueryClient();
  for (const status of ALERT_STATUSES) {
    client.setQueryData(
      queryKeys.watches.list({ status, limit: WATCH_LIST_LIMIT }),
      watchListResponse(watches.filter((w) => w.status === status)),
    );
  }
  for (const [id, name] of Object.entries(POI_NAMES)) {
    client.setQueryData(queryKeys.pois.name(Number(id)), name);
  }
  return client;
}

function Demo({ watches }: { watches: readonly Watch[] }) {
  const [client] = useState(() => seededClient(watches));
  return (
    <QueryClientProvider client={client}>
      <Nav>
        <AlertsPanel />
      </Nav>
    </QueryClientProvider>
  );
}

/** The panel is a row inside the topbar nav; this gives it that row's flow context. */
function Nav({ children }: { children: ReactNode }) {
  return <div style={{ display: 'flex', padding: 12 }}>{children}</div>;
}

const meta = {
  title: 'Alerts/AlertsPanel',
  parameters: {
    docs: {
      description: {
        component:
          "The nav's availability-alerts row: collapsed to a count, or expanded into " +
          'a table of every watch with per-row pause/resume, edit and delete. Hidden ' +
          'entirely once there is nothing to show — see `Empty` below.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * One active watch, one paused, one done — the bar reads "1 availability alert ·
 * 1 paused · 1 done". Starts collapsed, as it does in the nav; click the bar to
 * expand it into the table of trigger icons, last-checked times and row actions.
 */
export const WithWatches: Story = {
  render: () => <Demo watches={WATCHES} />,
};

/** No watches at all: the panel renders nothing, taking no space in the nav. */
export const Empty: Story = {
  render: () => <Demo watches={[]} />,
};
