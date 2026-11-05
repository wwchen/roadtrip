// Fetching one visible week of availability.
//
// The response is not cached client-side beyond Query's own cache on purpose: the
// *backend* caches these upstream calls and reports the age of what it served, which
// is what the freshness pill shows. A second, invisible client-side TTL on top would
// make that number a lie.
import { useEffect, useRef } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { requestAvailabilityRefresh, requestPoiCampsitesAvailability } from '@/api/availability-api';
import type { AvailabilityCache, PoiCampsitesAvailabilityResponse } from '@/api/availability-api';
import { addLocalDays, localYmd } from '@/lib/local-date';
import { queryKeys } from '@/queries/keys';
import { formatAvailabilityError } from './availability-errors';
import {
  NO_WATCH_CAPABILITIES,
  normalizeWatchCapabilities,
  type WatchCapabilities,
} from '@/lib/watch-windows';

/** Days in the visible window. Seven, and the grid's column count follows it. */
export const WEEK_DAYS = 7;
/** How long a fetch must run before the skeleton is worth showing. */
export const SKELETON_RENDER_DELAY_MS = 150;
/** Cache age at which the freshness pill starts warning. */
export const STALE_THRESHOLD_MIN = 10;
export const REFRESH_POLL_INTERVAL_MS = 2_000;
export const REFRESH_POLL_LIMIT = 15;

export interface RefreshPolling {
  pollIntervalMs: number;
  pollLimit: number;
}

const DEFAULT_REFRESH_POLLING: RefreshPolling = { pollIntervalMs: REFRESH_POLL_INTERVAL_MS, pollLimit: REFRESH_POLL_LIMIT };

export function isStale(cache: AvailabilityCache | null | undefined): boolean {
  return cache == null || cache.age_seconds > cache.ttl_seconds;
}

interface RefreshState {
  key: string;
  accepted: boolean;
  polls: number;
}

/**
 * The response, plus its capability block read into the shape the gates use.
 *
 * Nothing else is derived: `state`, `days`, `cells` and `latest_date` are rendered
 * as served.
 */
export interface WeekAvailability extends PoiCampsitesAvailabilityResponse {
  watchCapabilities: WatchCapabilities;
}

/** The window a week starting on `weekStart` asks for: seven days, end-exclusive. */
export function weekWindow(weekStart: Date): { startDate: string; endDate: string } {
  return {
    startDate: localYmd(weekStart),
    endDate: localYmd(addLocalDays(weekStart, WEEK_DAYS)),
  };
}

/**
 * A failed availability request, carrying copy fit to show.
 *
 * A distinct class rather than a bare `Error` so the component can tell a formatted
 * provider fault from a thrown network error and still render both — the message is
 * already the sentence in the first case.
 */
export class AvailabilityRequestError extends Error {
  constructor(
    message: string,
    readonly httpStatus: number,
    /** The backend's `error` code, when it sent one — `null` for a bare HTTP failure. */
    readonly code: string | null = null,
  ) {
    super(message);
    this.name = 'AvailabilityRequestError';
  }
}

/**
 * The fused week for a POI.
 *
 * `retry: false` is deliberate and not a default left unset. Half the failure modes
 * here are the booking site rate-limiting or blocking us, and three automatic
 * retries turn one throttled user into four requests against a provider that just
 * asked us to slow down. The grid offers a Retry link instead, so the person who
 * wants another attempt is the one who asks for it.
 */
export function useWeekAvailability(
  poiId: string | number | null | undefined,
  weekStart: Date,
  polling: RefreshPolling = DEFAULT_REFRESH_POLLING,
): UseQueryResult<WeekAvailability, Error> & { refreshFailed: boolean } {
  const { startDate, endDate } = weekWindow(weekStart);
  const refreshKey = `${poiId ?? ''}|${startDate}|${endDate}`;
  const refresh = useRef<RefreshState>({ key: '', accepted: false, polls: 0 });
  const refreshAccepted = refresh.current.key === refreshKey && refresh.current.accepted;
  const query = useQuery<WeekAvailability, Error>({
    queryKey: queryKeys.availability.forPoi(poiId ?? '', startDate, endDate),
    enabled: poiId != null,
    retry: false,
    refetchInterval: (current) => {
      const state = refresh.current;
      if (state.key !== refreshKey || !state.accepted || state.polls >= polling.pollLimit) return false;
      return isStale(current.state.data?.cache) ? polling.pollIntervalMs : false;
    },
    queryFn: async ({ signal }): Promise<WeekAvailability> => {
      if (refresh.current.key === refreshKey && refresh.current.accepted) refresh.current.polls += 1;
      const response = await requestPoiCampsitesAvailability(poiId!, { startDate, endDate, signal });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new AvailabilityRequestError(
          formatAvailabilityError(body, response.status),
          response.status,
          typeof body?.error === 'string' ? body.error : null,
        );
      }
      const json = (await response.json()) as PoiCampsitesAvailabilityResponse;
      return {
        ...json,
        days: Array.isArray(json?.days) ? json.days : [],
        watchCapabilities: json?.watch_capabilities
          ? normalizeWatchCapabilities(json.watch_capabilities)
          : NO_WATCH_CAPABILITIES,
      };
    },
  });
  const data = query.data;
  const { refetch } = query;
  useEffect(() => {
    if (poiId == null || data === undefined || data.state === 'empty' || !isStale(data.cache)) return;
    if (refresh.current.key === refreshKey) return;
    refresh.current = { key: refreshKey, accepted: false, polls: 0 };
    void requestAvailabilityRefresh(poiId, { startDate, endDate }).then((accepted) => {
      if (refresh.current.key !== refreshKey || !accepted) return;
      refresh.current.accepted = true;
      void refetch();
    });
  }, [poiId, data, refreshKey, startDate, endDate, refetch]);
  const refreshFailed = refreshAccepted && refresh.current.polls >= polling.pollLimit && isStale(data?.cache);
  return Object.assign(query, { refreshFailed });
}

/** Cache age in whole minutes, floored at 1 — "checked 0m ago" reads as a bug. */
export function cacheAgeMinutes(ageSeconds: number | null | undefined): number {
  return Math.max(1, Math.round((ageSeconds ?? 0) / 60));
}
