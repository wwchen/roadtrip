import { describe, expect, test, vi } from 'vitest';
import { AvailabilityRequestError } from '@/lib/availability-errors';
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

  function refusal(code: string, detail?: string) {
    return new AvailabilityRequestError(SERVICE_UNAVAILABLE, JSON.stringify({ error: code, ...(detail ? { detail } : null) }));
  }

  test('nothing failed, nothing to say', () => {
    expect(failureOf(null, retry)).toBeNull();
    expect(failureOf(undefined, retry)).toBeNull();
  });

  test("carries the server's own words, code and detail together", () => {
    const failure = failureOf(refusal('rate_limited', 'slow down'), retry);

    expect(failure?.reason).toBe(`${RATE_LIMITED} (rate_limited: slow down · HTTP ${SERVICE_UNAVAILABLE})`);
  });

  test('offers no retry while the provider is holding us off', () => {
    expect(failureOf(refusal('rate_limited'), retry)?.retry).toBeNull();
    expect(failureOf(refusal('ip_throttled'), retry)?.retry).toBeNull();
    expect(failureOf(refusal('upstream_blocked'), retry)?.retry).toBeNull();
  });

  test('offers a retry when the failure is their end, not their rate limit', () => {
    expect(failureOf(refusal('upstream_5xx'), retry)?.retry).toBe(retry);
  });

  test('an answer too broken to parse reaches the screen as its own text', () => {
    const failure = failureOf(new AvailabilityRequestError(502, '<html>Bad gateway</html>'), retry);

    expect(failure?.reason).toBe('<html>Bad gateway</html> · HTTP 502');
    expect(failure?.retry).toBe(retry);
  });

  test('a failure that is not the server speaking still carries what it knows', () => {
    const failure = failureOf(new Error('socket hung up'), retry);

    expect(failure?.reason).toBe('Error: socket hung up');
    expect(failure?.retry).toBe(retry);
  });
});
