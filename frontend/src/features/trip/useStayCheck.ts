// Runs the availability check for the campgrounds in view, and reads its
// answers back for the question being asked now.
//
// A check is a few requests in a row, not one: each answers a batch of
// campgrounds (`batchesOf`), so cards fill in as their batch lands and the head
// can count them. Requests go one at a time because the backend fans each one
// out against a small connection pool; overlapping them would only queue there.
import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { checkStays } from '@/api/availability-api';
import { HttpError } from '@/api/http';
import { useBefore } from '@/lib/use-now';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import {
  sameStayQuery,
  useStayCheckStore,
  type StayCheckFailure,
  type StayQuery,
  type StayResult,
} from '@/stores/stayCheckStore';
import { batchesOf, MAX_REQUESTS_PER_CHECK, observedAtOf, resultsOf } from './stay-check';

/** The bulk endpoint's code for a caller over its per-IP limit. */
const THROTTLED_CODE = 'ip_throttled';

/**
 * How long a throttled check waits before it may run again — the endpoint's
 * limit refills over a minute, and a check needs several requests' worth.
 */
const THROTTLE_COOLDOWN_MS = 60_000;

/** The question a check would ask now: null until both dates are set. */
export function useCurrentStayQuery(): StayQuery | null {
  const { dateWindow, siteType, groupSize } = useCampgroundFilterStore(
    useShallow((s) => ({ dateWindow: s.dateWindow, siteType: s.siteType, groupSize: s.groupSize })),
  );
  return useMemo(
    () => (dateWindow ? { start: dateWindow.start, end: dateWindow.end, siteType, groupSize } : null),
    [dateWindow, siteType, groupSize],
  );
}

export interface CurrentStayCheck {
  query: StayQuery;
  asked: readonly number[];
  results: Readonly<Record<number, StayResult>>;
  running: boolean;
  observedAt: string | null;
  failure: StayCheckFailure | null;
}

/** The check's answers when they answer the current question; null otherwise. */
export function useCurrentStayCheck(): CurrentStayCheck | null {
  const current = useCurrentStayQuery();
  const check = useStayCheckStore(
    useShallow((s) => ({
      query: s.query,
      asked: s.asked,
      results: s.results,
      running: s.running,
      observedAt: s.observedAt,
      failure: s.failure,
    })),
  );
  return useMemo(
    () => (check.query !== null && sameStayQuery(check.query, current) ? { ...check, query: check.query } : null),
    [check, current],
  );
}

export interface StayCheckControl {
  /**
   * True when there is a stay to ask about, something in view that can be
   * checked, and no throttle still cooling down.
   */
  canCheck: boolean;
  running: boolean;
  /** True once a check of the current question has come back, whole or not. */
  checked: boolean;
  /** True while a throttled check's cooldown runs — through new dates too. */
  coolingDown: boolean;
  /** Why the current question's check stopped early, if it did. */
  failure: StayCheckFailure | null;
  /** True when there is a stay to ask about but nothing in view a provider can answer for. */
  nothingToCheck: boolean;
  check: () => void;
}

export function useStayCheck(checkableIds: readonly number[]): StayCheckControl {
  const query = useCurrentStayQuery();
  const current = useCurrentStayCheck();
  const { begin, record, finish, reset } = useStayCheckStore(
    useShallow((s) => ({ begin: s.begin, record: s.record, finish: s.finish, reset: s.reset })),
  );
  const retryAt = useStayCheckStore((s) => s.retryAt);
  const coolingDown = useBefore(retryAt);
  const inFlight = useRef<AbortController | null>(null);

  // New dates or site filters make a running check's answers about nothing.
  useEffect(() => {
    const asked = useStayCheckStore.getState().query;
    if (asked === null || sameStayQuery(asked, query)) return;
    inFlight.current?.abort();
    reset();
  }, [query, reset]);

  // Leaving stops the requests but keeps what landed, marked finished.
  useEffect(
    () => () => {
      const controller = inFlight.current;
      if (!controller || controller.signal.aborted) return;
      controller.abort();
      useStayCheckStore.getState().finish(null);
    },
    [],
  );

  const run = useCallback(async () => {
    if (query === null || checkableIds.length === 0) return;
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    begin(query, checkableIds);
    try {
      for (const batch of batchesOf(checkableIds, MAX_REQUESTS_PER_CHECK)) {
        const response = await checkStays({ ...query, poiIds: batch }, { signal: controller.signal });
        if (controller.signal.aborted) return;
        record(resultsOf(response), observedAtOf(response, Date.now()));
      }
      finish(null);
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof HttpError && error.code === THROTTLED_CODE) {
        finish('throttled', Date.now() + THROTTLE_COOLDOWN_MS);
      } else {
        finish('failed');
      }
    } finally {
      if (inFlight.current === controller) inFlight.current = null;
    }
  }, [query, checkableIds, begin, record, finish]);

  return {
    canCheck: query !== null && checkableIds.length > 0 && !coolingDown,
    running: current?.running ?? false,
    checked: current !== null && !current.running,
    coolingDown,
    failure: current?.failure ?? null,
    nothingToCheck: query !== null && checkableIds.length === 0,
    check: () => void run(),
  };
}
