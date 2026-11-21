import type {
  AvailabilityChange,
  AvailabilityPoller,
  AvailabilityRun,
  ChangesSummaryResponse,
  PollersListResponse,
  PollersSummary,
} from '@/api/availability-dashboard-api';
import { tabSearch, type DashboardTab, type TabParams, type TabRoute } from './useTabRoute';

export const STORY_POI_ID = 4242;

export function storyTabRoute(tab: DashboardTab, params: TabParams = {}): TabRoute {
  return {
    tab,
    params,
    goToTab: () => {},
    setParams: () => {},
    hrefFor: (target) => tabSearch(target),
  };
}

const poller = (over: Partial<AvailabilityPoller> & Pick<AvailabilityPoller, 'id' | 'parent_ref'>): AvailabilityPoller => ({
  provider: 'recgov',
  poi_id: STORY_POI_ID,
  active: true,
  next_run_at: '2026-09-08T17:05:00Z',
  last_run_at: '2026-09-08T17:00:00Z',
  attached_watches: 2,
  created_at: '2026-08-01T00:00:00Z',
  updated_at: '2026-09-08T17:00:00Z',
  ...over,
});

export const STORY_POLLERS: AvailabilityPoller[] = [
  poller({ id: 101, parent_ref: '232447', claimed_until: '2026-09-08T17:01:30Z' }),
  poller({ id: 102, parent_ref: '233116', provider: 'aspira', attached_watches: 5 }),
  poller({ id: 103, parent_ref: '251869', active: false, attached_watches: 0, last_run_at: undefined }),
];

export const STORY_POLLERS_LIST: PollersListResponse = {
  total: STORY_POLLERS.length,
  limit: 50,
  offset: 0,
  pollers: STORY_POLLERS,
};

export const STORY_POLLERS_SUMMARY: PollersSummary = { active: 2, dormant: 1, due_now: 1, claimed: 1 };

export const STORY_RUNS: AvailabilityRun[] = [
  { id: 9001, poller_id: 101, status: 'started', snapshot_count: 0, started_at: '2026-09-08T17:00:05Z' },
  {
    id: 9000,
    poller_id: 102,
    status: 'completed',
    snapshot_count: 214,
    duration_ms: 1_840,
    started_at: '2026-09-08T16:55:00Z',
    completed_at: '2026-09-08T16:55:02Z',
  },
  {
    id: 8999,
    poller_id: 101,
    status: 'failed',
    snapshot_count: 0,
    duration_ms: 10_012,
    error: 'upstream_unreachable: connect timed out after 10s while fetching campground 232447 month 2026-09',
    started_at: '2026-09-08T16:50:00Z',
    completed_at: '2026-09-08T16:50:10Z',
  },
];

export const STORY_CHANGES: AvailabilityChange[] = [
  { campsite_id: 101, campsite_name: 'A12', target_date: '2026-09-12', observed_at: '2026-09-06T08:00:00Z', from_status: 'reserved', to_status: 'available' },
  { campsite_id: 101, campsite_name: 'A12', target_date: '2026-09-12', observed_at: '2026-09-06T09:30:00Z', from_status: 'available', to_status: 'reserved' },
  { campsite_id: 102, campsite_name: 'A14', target_date: '2026-09-12', observed_at: '2026-09-07T14:10:00Z', from_status: 'reserved', to_status: 'available' },
  { campsite_id: 102, campsite_name: 'A14', target_date: '2026-09-12', observed_at: '2026-09-08T06:45:00Z', from_status: 'available', to_status: 'closed' },
  { campsite_id: 103, campsite_name: 'B02', target_date: '2026-09-13', observed_at: '2026-09-07T21:00:00Z', to_status: 'first_come' },
];

export const STORY_CHANGES_SUMMARY: ChangesSummaryResponse = {
  poi_id: STORY_POI_ID,
  stats: [
    {
      target_date: '2026-09-12',
      total_runs: 288,
      first_run_at: '2026-09-01T00:00:00Z',
      last_run_at: '2026-09-08T17:00:00Z',
      median_cadence_sec: 300,
      last_open_at: '2026-09-07T14:10:00Z',
      is_currently_open: false,
      min_open_window_sec: 5_400,
      max_open_window_sec: 57_000,
    },
    {
      target_date: '2026-09-13',
      total_runs: 288,
      first_run_at: '2026-09-01T00:00:00Z',
      last_run_at: '2026-09-08T17:00:00Z',
      median_cadence_sec: 300,
      is_currently_open: true,
    },
  ],
};
