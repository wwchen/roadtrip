// Fetching one visible week of availability.
//
// Replaces `fetchWeek` in web/availability/availability-week.js, which hand-rolled
// what a query key gives for free: a `weekRequestSeq` counter compared at three
// separate await points, so a slow response for last week could not paint over this
// one. The key changes with the week, so a superseded response cannot reach the
// component at all.
//
// The response is not cached client-side beyond Query's own cache on purpose: the
// *backend* caches these upstream calls and reports the age of what it served, which
// is what the freshness pill shows. A second, invisible client-side TTL on top would
// make that number a lie.
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { requestPoiCampsitesAvailability } from '@/api/availability-api';
import type { PoiCampsitesAvailabilityResponse } from '@/api/availability-api';
import { addLocalDays, localYmd } from '@/lib/local-date';
import { queryKeys } from '@/queries/keys';
import { formatAvailabilityError } from './availability-errors';
import { fusePoiCampsitesAvailability, type FusedWeek } from './fuse';
import {
  NO_WATCH_CAPABILITIES,
  normalizeWatchCapabilities,
  type WatchCapabilities,
} from './watch-windows';

/** Days in the visible window. Seven, and the grid's column count follows it. */
export const WEEK_DAYS = 7;
/** How long a fetch must run before the skeleton is worth showing. */
export const SKELETON_RENDER_DELAY_MS = 150;
/** Cache age at which the freshness pill starts warning. */
export const STALE_THRESHOLD_MIN = 10;

export interface WeekAvailability extends FusedWeek {
  /** What this provider will let a watch do, from the same response. */
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
): UseQueryResult<WeekAvailability, Error> {
  const { startDate, endDate } = weekWindow(weekStart);
  return useQuery({
    queryKey: queryKeys.availability.forPoi(poiId ?? '', startDate, endDate),
    enabled: poiId != null,
    retry: false,
    queryFn: async ({ signal }): Promise<WeekAvailability> => {
      const response = await requestPoiCampsitesAvailability(poiId!, { startDate, endDate, signal });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new AvailabilityRequestError(
          formatAvailabilityError(body, response.status),
          response.status,
        );
      }
      const json = (await response.json()) as PoiCampsitesAvailabilityResponse;
      return {
        ...fusePoiCampsitesAvailability(json, startDate, endDate),
        watchCapabilities: json?.watch_capabilities
          ? normalizeWatchCapabilities(json.watch_capabilities)
          : NO_WATCH_CAPABILITIES,
      };
    },
  });
}

/** Cache age in whole minutes, floored at 1 — "checked 0m ago" reads as a bug. */
export function cacheAgeMinutes(ageSeconds: number | null | undefined): number {
  return Math.max(1, Math.round((ageSeconds ?? 0) / 60));
}
