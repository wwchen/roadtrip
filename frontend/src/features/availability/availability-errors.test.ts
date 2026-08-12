import { describe, expect, test } from 'vitest';
import { formatAvailabilityError } from './availability-errors';

describe('formatting a provider fault', () => {
  test('names each known code', () => {
    const cases: Array<[string, string]> = [
      ['rate_limited', 'Booking site rate-limited us — try again shortly'],
      ['upstream_blocked', 'Booking site blocked the request'],
      ['upstream_5xx', 'Booking site returned an error'],
      ['upstream_unreachable', 'Could not reach the booking site'],
      ['unsupported', 'Provider not supported'],
      ['provider_misconfigured', 'Provider misconfigured — we are on it'],
      ['ip_throttled', 'Too many requests — slow down'],
    ];
    for (const [code, expected] of cases) {
      expect(formatAvailabilityError({ error: code }, 503)).toBe(expected);
    }
  });

  test('appends the upstream status when there is one', () => {
    expect(formatAvailabilityError({ error: 'upstream_blocked', upstream_status: 403 }, 502)).toBe(
      'Booking site blocked the request (upstream HTTP 403)',
    );
  });

  test('shows an unrecognised code verbatim', () => {
    expect(formatAvailabilityError({ error: 'captcha_wall' }, 503)).toBe('captcha_wall');
  });

  test('falls back to the HTTP status with no code', () => {
    expect(formatAvailabilityError(null, 500)).toBe('HTTP 500');
    expect(formatAvailabilityError({}, 418)).toBe('HTTP 418');
    // A non-string code is no code.
    expect(formatAvailabilityError({ error: 42 }, 500)).toBe('HTTP 500');
  });

  test('ignores a non-numeric upstream status', () => {
    expect(formatAvailabilityError({ error: 'unsupported', upstream_status: 'bad' }, 500)).toBe(
      'Provider not supported',
    );
  });
});
