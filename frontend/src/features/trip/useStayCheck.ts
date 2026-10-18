// Runs the availability check for the campgrounds in view, and reads its
// answers back for the question being asked now.
//
// A check is one request whose answer streams (`streamStays`): the backend
// answers each campground as its provider does, so cards fill in one by one and
// the head counts them as they land. The check ends at the stream's `done`, which
// says whether it ran to its end (`complete`) or the server ran out of time and
// answered `timed_out` for whoever was still waiting (`deadline_reached`); one that
// ends before `done`, or goes quiet — not even a keep-alive — for
// `CHECK_IDLE_TIMEOUT_MS`, stopped early.
import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { streamStays } from '@/api/availability-api';
import type { StayCheckOutcome } from '@/api/generated/api-types';
import { HttpError } from '@/api/http';
import { DeadlineExceeded, withIdleTimeout } from '@/lib/deadline';
import { useBefore } from '@/lib/use-now';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import {
  sameStayQuery,
  useStayCheckStore,
  type StayCheckFailure,
  type StayQuery,
  type StayResult,
} from '@/stores/stayCheckStore';
import { CHECK_IDLE_TIMEOUT_MS, RECHECK_MAX_AGE_SECONDS, resultOf } from './stay-check';

/** The stay stream's code for a caller over its per-IP limit. */
const THROTTLED_CODE = 'ip_throttled';

/** How long a throttled check waits before it may run again — the endpoint's limit refills over a minute. */
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
  /**
   * True when there is a stay to ask about and the list in view has loaded, but
   * nothing in it a provider can answer for. False while the list is still on its
   * way, or zoomed out past it — that is not "nothing checkable".
   */
  nothingToCheck: boolean;
  check: () => void;
}

/**
 * [checkableIds] is null while the in-view list is not ready (loading, zoomed
 * out, or switched off), and the ids of its cards a provider can answer for once
 * it is. Checking again — the same question, already answered — asks for a short
 * age (`RECHECK_MAX_AGE_SECONDS`) rather than answering from anything within the
 * server's default max age.
 */
export function useStayCheck(checkableIds: readonly number[] | null): StayCheckControl {
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

  // Leaving stops the stream but keeps what landed, marked finished.
  useEffect(
    () => () => {
      const controller = inFlight.current;
      if (!controller || controller.signal.aborted) return;
      controller.abort();
      useStayCheckStore.getState().finish(null);
    },
    [],
  );

  const run = useCallback(async (maxAgeSeconds: number | null) => {
    if (query === null || checkableIds === null || checkableIds.length === 0) return;
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    begin(query, checkableIds);
    try {
      const outcome = await withIdleTimeout(
        async (signal, alive): Promise<StayCheckOutcome | null> => {
          const request = { ...query, poiIds: [...checkableIds], maxAgeSeconds };
          for await (const event of streamStays(request, { signal, onActivity: alive })) {
            // An event already read when the check was cancelled answers nothing now.
            if (controller.signal.aborted) return null;
            if (event.kind === 'done') return event.done.outcome;
            // The server dates an answer by its oldest observation. With no campsite
            // checked there is nothing to date it by, and now is when we looked.
            const observedAt = event.answer.stay?.observed_at ?? new Date().toISOString();
            record({ [event.answer.poi_id]: resultOf(event.answer) }, observedAt);
          }
          return null;
        },
        controller.signal,
        CHECK_IDLE_TIMEOUT_MS,
      );
      if (controller.signal.aborted) return;
      // The backend answers every campground before `done`, so what only it can say is
      // how the check ended: `deadline_reached` means some of those answers are the
      // server's `timed_out` for a campground it stopped waiting on, not a real one. A
      // stream that closed before `done` (null) was cut off — by a proxy, a restart.
      finish(outcome === 'complete' ? null : outcome === 'deadline_reached' ? 'timed_out' : 'failed');
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof HttpError && error.code === THROTTLED_CODE) {
        finish('throttled', Date.now() + THROTTLE_COOLDOWN_MS);
      } else {
        finish(error instanceof DeadlineExceeded ? 'timed_out' : 'failed');
      }
    } finally {
      if (inFlight.current === controller) inFlight.current = null;
    }
  }, [query, checkableIds, begin, record, finish]);

  const checked = current !== null && !current.running;
  return {
    canCheck: query !== null && checkableIds !== null && checkableIds.length > 0 && !coolingDown,
    running: current?.running ?? false,
    checked,
    coolingDown,
    failure: current?.failure ?? null,
    nothingToCheck: query !== null && checkableIds !== null && checkableIds.length === 0,
    check: () => void run(checked ? RECHECK_MAX_AGE_SECONDS : null),
  };
}
