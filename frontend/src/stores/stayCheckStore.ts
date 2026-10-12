// The availability check for the campgrounds in view (M3): the stay it asked
// about, what each campground answered, and how far a running check has got.
// Read by the in-view list (M3) and the map's pins (M4). Session-only.
//
// Answers are only true of the question they were asked under. The list reads
// them through `sameStayQuery` against the current dates and site filters, so a
// change to either leaves the old answers behind rather than showing them as if
// they were about the new stay.
import { create } from 'zustand';
import type { StaySummary } from '@/api/availability-api';
import type { CampsiteKind } from '@/api/generated/api-types';

/** What a check asks: the stay, and the site filters that narrow which campsites count. */
export interface StayQuery {
  /** `YYYY-MM-DD`, the first night. */
  start: string;
  /** `YYYY-MM-DD`, the morning you leave. */
  end: string;
  siteType: CampsiteKind | null;
  groupSize: number | null;
}

/** One campground's answer: how the stay looks there, or the code it failed with. */
export type StayResult = { kind: 'summary'; summary: StaySummary } | { kind: 'error'; code: string };

/** Why a check stopped before every campground answered. */
export type StayCheckFailure = 'throttled' | 'timed_out' | 'failed';

export interface StayCheckState {
  /** The question the answers below belong to; null before any check. */
  query: StayQuery | null;
  /** Every id the check asked about, in the order asked. */
  asked: readonly number[];
  results: Readonly<Record<number, StayResult>>;
  running: boolean;
  /** When the oldest answer was observed, as an ISO instant; null until one lands. */
  observedAt: string | null;
  failure: StayCheckFailure | null;
  /** When a throttled check may be tried again, in epoch ms; null when nothing is cooling down. */
  retryAt: number | null;
  begin: (query: StayQuery, ids: readonly number[]) => void;
  /** Adds answers as they land, observed no later than [observedAt]. */
  record: (results: Readonly<Record<number, StayResult>>, observedAt: string) => void;
  finish: (failure: StayCheckFailure | null, retryAt?: number | null) => void;
  reset: () => void;
}

const INITIAL = {
  query: null,
  asked: [],
  results: {},
  running: false,
  observedAt: null,
  failure: null,
  retryAt: null,
} satisfies Pick<StayCheckState, 'query' | 'asked' | 'results' | 'running' | 'observedAt' | 'failure' | 'retryAt'>;

export function sameStayQuery(a: StayQuery | null, b: StayQuery | null): boolean {
  if (a === null || b === null) return false;
  return a.start === b.start && a.end === b.end && a.siteType === b.siteType && a.groupSize === b.groupSize;
}

/** The earlier of two ISO instants; either may be absent. */
function earlier(a: string | null, b: string): string {
  return a !== null && a < b ? a : b;
}

export const useStayCheckStore = create<StayCheckState>((set) => ({
  ...INITIAL,
  begin: (query, ids) => set({ ...INITIAL, query, asked: [...ids], running: true }),
  record: (results, observedAt) =>
    set((state) => ({
      results: { ...state.results, ...results },
      observedAt: earlier(state.observedAt, observedAt),
    })),
  finish: (failure, retryAt = null) => set({ running: false, failure, retryAt }),
  // The cooldown outlives the question: the endpoint's limit is per caller, not per stay.
  reset: () => set((state) => ({ ...INITIAL, retryAt: state.retryAt })),
}));
