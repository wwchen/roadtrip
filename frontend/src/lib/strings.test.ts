import { describe, expect, test } from 'vitest';
import type { StayFailureReason, StayOutcome } from '@/api/availability-api';
import { stayCopy } from './strings';

describe('stayCopy.errorFor', () => {
  test('a refusal says why, by its reason', () => {
    expect(stayCopy.errorFor('refused', 'beyond_booking_horizon')).toBe('Not bookable this far ahead yet');
    expect(stayCopy.errorFor('refused', 'start_before_earliest')).toBe('Too soon to book online');
    expect(stayCopy.errorFor('refused', 'window_too_long')).toBe('Too long a stay to book online');
  });

  test('a refusal with no copy of its own reads as a generic refusal', () => {
    expect(stayCopy.errorFor('refused', 'a_future_reason' as StayFailureReason)).toBe(stayCopy.refusedLine);
    expect(stayCopy.errorFor('refused', null)).toBe(stayCopy.refusedLine);
  });

  test('a timeout says to ask again; a failure, or an outcome it has never seen, is "couldn\'t check"', () => {
    expect(stayCopy.errorFor('timed_out', null)).toBe(stayCopy.timedOutLine);
    expect(stayCopy.errorFor('failed', 'rate_limited')).toBe(stayCopy.error);
    expect(stayCopy.errorFor('some_future_outcome' as StayOutcome, null)).toBe(stayCopy.error);
  });
});
