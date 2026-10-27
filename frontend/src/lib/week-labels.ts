// Date labels for the nights a place shows.
//
// Every one of these formats a `YYYY-MM-DD` string, and every one of them does it
// **in UTC**. That looks wrong and is not: an ISO date with no time is a calendar
// date, not an instant, and parsing it in the browser's zone makes `2026-08-11`
// render as "Aug 10" for every user west of Greenwich. Pinning `timeZone: 'UTC'`
// keeps the rendered label equal to the source string.
import { WEEKDAY_LABELS } from '@/lib/format';

const UTC = 'UTC';

const utcDate = (iso: string): Date => new Date(`${iso}T00:00:00Z`);

const format = (date: Date, options: Intl.DateTimeFormatOptions): string =>
  date.toLocaleDateString('en-US', { ...options, timeZone: UTC });

/** "Tue" for a date's column header. */
export function dowLabel(iso: string): string {
  const day = utcDate(iso).getUTCDay();
  return WEEKDAY_LABELS[day] ?? '';
}

/** The day-of-month for a column header, or the raw string if it will not parse. */
export function dayOfMonthLabel(iso: string): string {
  const day = parseInt(iso.slice(8, 10), 10);
  return Number.isFinite(day) ? String(day) : iso;
}

/** "Tue, Aug 11" — a night spelled out in full. */
export function longDayLabel(iso: string): string {
  return format(utcDate(iso), { weekday: 'short', month: 'short', day: 'numeric' });
}
