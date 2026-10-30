import { describe, expect, test, vi } from 'vitest';
import { AvailabilityRequestError, GENERIC_AVAILABILITY_ERROR } from '@/lib/availability-errors';
import { failureOf, nightsOf } from './use-place-nights';

const START = '2026-10-25';

describe('nightsOf', () => {
  test('names every night of the stay and not the morning you leave', () => {
    expect(nightsOf(START, '2026-10-28')).toEqual(['2026-10-25', '2026-10-26', '2026-10-27']);
  });

  test('a window that ends where it starts has no nights in it', () => {
    expect(nightsOf(START, START)).toEqual([]);
  });

  test('a window longer than the strip can show is cut, not looped forever', () => {
    expect(nightsOf(START, '2027-10-25').length).toBe(30);
  });
});

describe('a grid the booking site would not answer', () => {
  const retry = vi.fn();
  const RATE_LIMITED = 'Booking site rate-limited us — try again shortly';
  const SERVICE_UNAVAILABLE = 503;

  test('nothing failed, nothing to say', () => {
    expect(failureOf(null, retry)).toBeNull();
    expect(failureOf(undefined, retry)).toBeNull();
  });

  test('carries the reason the server stated, verbatim', () => {
    const failure = failureOf(
      new AvailabilityRequestError(RATE_LIMITED, SERVICE_UNAVAILABLE, 'rate_limited'),
      retry,
    );

    expect(failure?.reason).toBe(RATE_LIMITED);
  });

  test('offers no retry while the provider is holding us off', () => {
    const throttled = new AvailabilityRequestError(RATE_LIMITED, SERVICE_UNAVAILABLE, 'rate_limited');

    expect(failureOf(throttled, retry)?.retry).toBeNull();
  });

  test('offers a retry when the failure is their end, not their rate limit', () => {
    const failed = new AvailabilityRequestError('Booking site returned an error', SERVICE_UNAVAILABLE, 'upstream_5xx');

    expect(failureOf(failed, retry)?.retry).toBe(retry);
  });

  test('a failure that is not the server speaking still says something and offers a retry', () => {
    const failure = failureOf(new Error(''), retry);

    expect(failure?.reason).toBe(GENERIC_AVAILABILITY_ERROR);
    expect(failure?.retry).toBe(retry);
  });
});
