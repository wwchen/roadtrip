import type { AvailabilityProviderCapabilities } from '@roadtrip/domain';
import { addDays, type PoiDateContext } from '../booking/dateContext';

export const DEFAULT_WINDOW_DAYS = 7;
export const UNSERVED_WINDOW_MAX_DAYS = 60;
export const UNSERVED_WINDOW_HORIZON_DAYS = 365;
const MS_PER_DAY = 86_400_000;

export interface WeekWindow {
  startDate: string;
  endDate: string;
  latestDate: string | null;
}

export class BadDateWindowError extends Error {
  constructor(
    readonly code: string,
    readonly extra: Record<string, string | number> = {},
  ) {
    super(code);
    this.name = 'BadDateWindowError';
  }
}

const daysBetween = (start: string, end: string) => Math.round((Date.parse(end) - Date.parse(start)) / MS_PER_DAY);

export function resolveWeekWindow(
  provider: AvailabilityProviderCapabilities | null,
  startDate: string | null,
  endDate: string | null,
  context: PoiDateContext,
): WeekWindow {
  const horizonDays = provider?.bookingHorizonDays ?? UNSERVED_WINDOW_HORIZON_DAYS;
  const maxDays = provider?.maxPollWindowDays ?? UNSERVED_WINDOW_MAX_DAYS;
  if (startDate !== null && startDate < context.earliestDate) {
    throw new BadDateWindowError('start_before_earliest', { earliest_date: context.earliestDate, time_zone: context.timeZone });
  }
  const start = startDate ?? context.earliestDate;
  const end = endDate ?? addDays(start, DEFAULT_WINDOW_DAYS);
  if (end <= start) throw new BadDateWindowError('end_before_start');
  const horizon = addDays(context.earliestDate, horizonDays);
  if (end > horizon) throw new BadDateWindowError('beyond_booking_horizon', { latest_date: horizon });
  const days = daysBetween(start, end);
  if (days < 1 || days > maxDays) throw new BadDateWindowError('window_too_long', { max_days: maxDays });
  return { startDate: start, endDate: end, latestDate: provider === null ? null : horizon };
}
