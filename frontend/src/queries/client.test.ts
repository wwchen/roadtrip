import { describe, expect, test } from 'vitest';
import { HttpError } from '@/api/http';
import { createQueryClient } from './client';

const defaults = () => createQueryClient().getDefaultOptions().queries!;

const retryFor = (error: unknown, failureCount = 0): boolean => {
  const retry = defaults().retry as (n: number, e: unknown) => boolean;
  return retry(failureCount, error);
};

const httpError = (status: number): HttpError => new HttpError('/x', status);

describe('query defaults', () => {
  test('does not refetch on window focus, matching the legacy pages', () => {
    expect(defaults().refetchOnWindowFocus).toBe(false);
  });

  test('sets a stale time so a pan back to a just-viewed bbox does not refetch', () => {
    expect(defaults().staleTime).toBeGreaterThan(0);
  });
});

describe('mutation defaults', () => {
  test('does not retry', () => {
    expect(createQueryClient().getDefaultOptions().mutations?.retry).toBe(false);
  });
});

describe('retry policy', () => {
  test.each([[400], [401], [403], [404], [422], [499]])(
    'does not retry a %i',
    (status) => {
      expect(retryFor(httpError(status))).toBe(false);
    },
  );

  test('retries a 429', () => {
    expect(retryFor(httpError(429))).toBe(true);
  });

  test.each([[500], [502], [503]])('retries a %i', (status) => {
    expect(retryFor(httpError(status))).toBe(true);
  });

  test('retries a transport failure that is not an HttpError', () => {
    expect(retryFor(new TypeError('Failed to fetch'))).toBe(true);
  });

  test('stops after the retry budget is spent', () => {
    expect(retryFor(httpError(500), 0)).toBe(true);
    expect(retryFor(httpError(500), 1)).toBe(true);
    expect(retryFor(httpError(500), 2)).toBe(false);
  });

  test('does not retry a 429 past the budget either', () => {
    expect(retryFor(httpError(429), 2)).toBe(false);
  });
});

describe('retry backoff', () => {
  const delayFor = (attempt: number): number => {
    const retryDelay = defaults().retryDelay as (n: number, e: unknown) => number;
    return retryDelay(attempt, httpError(500));
  };

  test('backs off exponentially', () => {
    expect(delayFor(0)).toBeLessThan(delayFor(1));
    expect(delayFor(1)).toBeLessThan(delayFor(2));
  });

  test('caps the delay', () => {
    expect(delayFor(50)).toBeLessThanOrEqual(5_000);
  });
});
