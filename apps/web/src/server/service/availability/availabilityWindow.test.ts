import { AVAILABILITY_PROVIDERS } from '@roadtrip/domain';
import { expect, test } from 'vitest';
import { BadDateWindowError, resolveWeekWindow } from './availabilityWindow';

const ASPIRA = AVAILABILITY_PROVIDERS.find((provider) => provider.id === 'aspira')!;
const CONTEXT = { timeZone: 'America/Vancouver', earliestDate: '2026-11-05' };

const failure = (run: () => unknown) => {
  try {
    run();
  } catch (error) {
    return error as BadDateWindowError;
  }
  throw new Error('expected a BadDateWindowError');
};

test('no dates default to a week from the earliest date, with the provider horizon as latest', () => {
  expect(resolveWeekWindow(ASPIRA, null, null, CONTEXT)).toEqual({ startDate: '2026-11-05', endDate: '2026-11-12', latestDate: '2027-11-05' });
});

test('a start before the earliest date names the earliest date and zone', () => {
  const error = failure(() => resolveWeekWindow(ASPIRA, '2026-11-04', '2026-11-06', CONTEXT));
  expect(error.code).toBe('start_before_earliest');
  expect(error.extra).toEqual({ earliest_date: '2026-11-05', time_zone: 'America/Vancouver' });
});

test.each([
  ['2026-11-08', '2026-11-08', 'end_before_start'],
  ['2026-11-08', '2026-12-09', 'window_too_long'],
  ['2027-11-01', '2027-11-07', 'beyond_booking_horizon'],
])('%s..%s is %s', (start, end, code) => {
  expect(failure(() => resolveWeekWindow(ASPIRA, start, end, CONTEXT)).code).toBe(code);
});

test('the window may run to the provider maximum and the horizon exactly', () => {
  expect(resolveWeekWindow(ASPIRA, '2026-11-05', '2026-12-05', CONTEXT).endDate).toBe('2026-12-05');
  expect(resolveWeekWindow(ASPIRA, '2027-10-30', '2027-11-05', CONTEXT).endDate).toBe('2027-11-05');
});

test('with no provider the window falls back to a 365-day horizon, 60-day maximum and no latest date', () => {
  expect(resolveWeekWindow(null, '2026-11-05', '2027-01-04', CONTEXT)).toEqual({ startDate: '2026-11-05', endDate: '2027-01-04', latestDate: null });
});
