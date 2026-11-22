import { describe, expect, test } from 'vitest';
import { classifyAvailabilityErrorCode, formatAvailabilityError } from './availability-errors';

describe('formatting a provider fault', () => {
  test('names each known code and keeps the raw code beside it', () => {
    const cases: Array<[string, string]> = [
      ['rate_limited', 'Booking site rate-limited us — try again shortly'],
      ['upstream_blocked', 'Booking site blocked the request'],
      ['upstream_5xx', 'Booking site returned an error'],
      ['upstream_unreachable', 'Could not reach the booking site'],
      ['unsupported', 'Provider not supported'],
      ['provider_misconfigured', 'Provider misconfigured — we are on it'],
      ['ip_throttled', 'Too many requests from this network — slow down'],
      ['availability_unavailable', 'Availability is unavailable right now — try again shortly'],
      ['beyond_booking_horizon', 'That date is past what this provider lets you book'],
      ['start_before_earliest', 'That date is before this campground takes bookings'],
    ];
    for (const [code, expected] of cases) {
      expect(formatAvailabilityError({ error: code }, 503)).toBe(`${expected} (${code} · HTTP 503)`);
    }
  });

  test('carries the detail and the upstream status', () => {
    expect(
      formatAvailabilityError({ error: 'upstream_blocked', detail: 'key rejected', upstream_status: 403 }, 502),
    ).toBe('Booking site blocked the request (upstream_blocked: key rejected · HTTP 502 · upstream HTTP 403)');
  });

  test('shows an unrecognised code verbatim with its detail', () => {
    expect(formatAvailabilityError({ error: 'captcha_wall', detail: 'cf challenge' }, 503)).toBe(
      'captcha_wall: cf challenge · HTTP 503',
    );
  });

  test('falls back to the HTTP status with no code', () => {
    expect(formatAvailabilityError(null, 500)).toBe('HTTP 500');
    expect(formatAvailabilityError({}, 418)).toBe('HTTP 418');
    expect(formatAvailabilityError({ error: 42 }, 500)).toBe('HTTP 500');
  });

  test('ignores a non-numeric upstream status', () => {
    expect(formatAvailabilityError({ error: 'unsupported', upstream_status: 'bad' }, 500)).toBe(
      'Provider not supported (unsupported · HTTP 500)',
    );
  });
});

describe('classifying a provider fault', () => {
  test('only the vendor rate limit is a throttle', () => {
    expect(classifyAvailabilityErrorCode('rate_limited')).toBe('throttled');
    expect(classifyAvailabilityErrorCode('ip_throttled')).toBe('other');
    expect(classifyAvailabilityErrorCode('upstream_blocked')).toBe('other');
  });
});
