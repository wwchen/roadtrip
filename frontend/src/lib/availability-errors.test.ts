import { describe, expect, test } from 'vitest';
import {
  AvailabilityRequestError,
  classifyAvailabilityErrorCode,
  formatAvailabilityError,
  retryingHelps,
} from './availability-errors';

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

  test('their end failing, and their end being unreachable', () => {
    expect(classifyAvailabilityErrorCode('upstream_5xx')).toBe('server_error');
    expect(classifyAvailabilityErrorCode('upstream_unreachable')).toBe('unreachable');
  });

  test('anything else, including no code at all', () => {
    expect(classifyAvailabilityErrorCode('captcha_wall')).toBe('other');
    expect(classifyAvailabilityErrorCode(null)).toBe('other');
  });
});

describe('whether to offer a retry', () => {
  test('not for the three codes that mean stop asking', () => {
    for (const code of ['rate_limited', 'ip_throttled', 'upstream_blocked']) {
      expect(retryingHelps(code)).toBe(false);
    }
  });

  test('for every other code, and when the server named none', () => {
    expect(retryingHelps('upstream_5xx')).toBe(true);
    expect(retryingHelps('upstream_unreachable')).toBe(true);
    expect(retryingHelps('captcha_wall')).toBe(true);
    expect(retryingHelps(null)).toBe(true);
  });
});

describe('a refused availability request', () => {
  test('reads its code and detail out of the body', () => {
    const error = new AvailabilityRequestError(503, JSON.stringify({ error: 'rate_limited', detail: 'slow down' }));
    expect(error.code).toBe('rate_limited');
    expect(error.message).toBe('Booking site rate-limited us — try again shortly (rate_limited: slow down · HTTP 503)');
  });

  test('a body that is not JSON still reaches the screen as its own text', () => {
    const error = new AvailabilityRequestError(502, '<html>Bad gateway</html>');
    expect(error.code).toBeNull();
    expect(error.message).toBe('<html>Bad gateway</html> · HTTP 502');
  });

  test('no body at all leaves the status to say it', () => {
    const error = new AvailabilityRequestError(500);
    expect(error.code).toBeNull();
    expect(error.message).toBe('HTTP 500');
  });
});
