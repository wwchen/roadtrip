// The QueryClient and its defaults.
//
// One staleness policy for every caller, rather than each deciding for itself.
import { QueryClient } from '@tanstack/react-query';
import { HttpError } from '@/api/http';

/**
 * How long a response is served without a background refetch.
 *
 * 30s suits this app's data: POIs, settings, and watch lists change on human
 * timescales, so a pan back to a just-viewed bbox should not re-request. Live
 * data that genuinely needs to be fresher (availability during an active
 * booking window) sets its own staleTime at the query.
 */
const DEFAULT_STALE_TIME_MS = 30_000;

/** Keep an unused response around this long before evicting it. */
const DEFAULT_GC_TIME_MS = 5 * 60_000;

const MAX_RETRIES = 2;
const RETRY_BASE_DELAY_MS = 300;
const RETRY_MAX_DELAY_MS = 5_000;

/** 4xx statuses that a retry cannot fix. */
const NON_RETRYABLE_MIN_STATUS = 400;
const NON_RETRYABLE_MAX_STATUS = 500;
const RETRYABLE_TOO_MANY_REQUESTS = 429;

/**
 * Retry transport failures, not answers.
 *
 * A 4xx means the request was wrong and will be wrong again — retrying it just
 * multiplies the error. 429 is the exception: it is explicitly "try again", and
 * the backoff below is what it is asking for.
 */
function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_RETRIES) return false;
  if (error instanceof HttpError) {
    if (error.status === RETRYABLE_TOO_MANY_REQUESTS) return true;
    return !(error.status >= NON_RETRYABLE_MIN_STATUS && error.status < NON_RETRYABLE_MAX_STATUS);
  }
  return true;
}

const retryDelay = (attemptIndex: number): number =>
  Math.min(RETRY_BASE_DELAY_MS * 2 ** attemptIndex, RETRY_MAX_DELAY_MS);

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: DEFAULT_STALE_TIME_MS,
        gcTime: DEFAULT_GC_TIME_MS,
        retry: shouldRetry,
        retryDelay,
        // The map's viewport fetch loop is already driven by moveend, so
        // refetching every query on focus would re-request for nothing.
        refetchOnWindowFocus: false,
      },
      mutations: {
        // A mutation is a user action with a visible result; retrying it silently
        // can double-write. Surface the failure instead.
        retry: false,
      },
    },
  });
}
